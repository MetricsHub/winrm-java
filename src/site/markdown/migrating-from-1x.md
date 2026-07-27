keywords: migration, upgrade, 1.x, 2.0, cxf, tls, smb, breaking changes
description: What changed in WinRM Java Client 2.0.0 and how to upgrade from the 1.x releases.

# Migrating from 1.x

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Version 2.0.0 is a major cleanup: the legacy Apache CXF backend and the SMB-based file copy are
gone, leaving a **dependency-free** client. The **1.x entry points are unchanged**, so typical
calling code is unaffected — but a few CXF/SMB-only public types were removed (see the *Removed types*
section below), and two runtime behaviors changed. Read this page before upgrading.

Version 2.0.0 also introduces the **fluent [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html)
API**, which the documentation is now written around. Upgrading does not require adopting it —
the [legacy API](legacy.html) keeps working — but moving is straightforward and worthwhile; see
[Moving to the fluent API](#moving-to-the-fluent-api) below.

## TL;DR

* The Apache CXF backend was **removed**; the dependency-free client is the only implementation.
* HTTPS now **validates the certificate and verifies the hostname by default** (1.x trusted every
  certificate). Self-signed hosts that used to work will now fail the TLS handshake until you trust
  the certificate or opt out.
* File copy for `localFileToCopyList` now goes **through the WinRM channel** instead of SMB.
* A few CXF/SMB-only classes were removed.
* A new **fluent API** (`WinRMClient`) is the recommended way to use the library; the 1.x static
  helpers remain supported.

## TLS is validated by default

The 1.x CXF client silently trusted every TLS certificate and skipped hostname verification. The
2.0.0 client uses the JDK's default validating socket factory instead, so **HTTPS connections to
hosts with self-signed or otherwise untrusted certificates now fail** during the handshake.

To restore connectivity, either:

* install the server certificate (or its issuing CA) into a Java trust store —
  `-Djavax.net.ssl.trustStore=...` (recommended); or
* disable validation with `-Dorg.metricshub.winrm.tls.insecure=true` (**insecure — testing only**).

See [TLS / HTTPS](tls.html) for details.

## The CXF backend was removed

The dependency-free client introduced in the previous release is now the only implementation. There
is no switch to fall back to the Apache CXF backend — if you still need it, stay on winrm-java 1.x.

## File copy no longer uses SMB

Files listed in `localFileToCopyList` for
[`WinRMCommandExecutor.execute(...)`](commands.html) are no longer copied over SMB. They are
transferred **through the WinRM command shell** (chunked base64, decoded on the host with `certutil`
and verified with a digest). The consequences:

* **No SMB requirement** — TCP port 445 no longer needs to be reachable, no administrative or
  temporary share is created on the host, and the copy now works from **any client OS** (1.x wrote
  through a Windows UNC path and only worked from a Windows client).
* The remote copy is **content-addressed**: a fragment of the content digest is inserted before the
  file extension (for example `script.1a2b3c4d.vbs`). Same-named files with different content can no
  longer overwrite each other, and a script that reads its own name (`WScript.ScriptName`) will see
  the digest fragment.
* A file already present on the host with an identical digest is not transferred again.
* The transport is meant for **small script files**, not bulk data.

## Fewer dependencies

Removing CXF and SMB leaves the library with **zero runtime dependencies**: the Apache CXF /
JAX-WS / JAXB stack is gone, and so are `smbj`, BouncyCastle, SLF4J, `mbassador`, and `asn-one`. The
standalone CLI jar shrinks from around 9 MB to a few hundred kB, and the library no longer references
any logging API — problems are reported through [exceptions](timeouts-and-errors.html) only.

## Removed types

These types and members were public in 1.x but are **removed** in 2.0.0. Code that referenced them
will not compile against 2.0.0 (all were CXF- or SMB-specific):

* `KerberosCredentialsException` — was thrown only by CXF internals.
* `SmbTempShare` and `WindowsRemoteProcessUtils.copyLocalFilesToShare(...)` — replaced by the
  WinRM-channel file transfer. `WindowsTempShare` is unchanged.
* The Apache CXF-based `WinRMService` and its `service.client` internals, along with the generated
  WSDL/XSD resources.

The 1.x entry points — `WinRMWqlExecutor`, `WinRMCommandExecutor`, `WinRMEndpoint`,
`WindowsRemoteCommandResult`, the enums, and the exception types — are unchanged.

## Moving to the fluent API

The 1.x static helpers open a connection, authenticate, run **one** operation, and tear everything
down. The fluent [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html) authenticates once
and runs any number of operations over the same connection — a large win for anything that polls a
host — and replaces the 10-argument positional calls with builders. Migration is mechanical:

### WQL queries

```java
// 1.x style (still works)
WinRMWqlExecutor result = WinRMWqlExecutor.executeWql(
    HTTP, "server", null, "DOMAIN\\user", password,
    null, "SELECT Name, State FROM Win32_Service",
    30_000L, null, singletonList(NTLM));
List<String> headers = result.getHeaders();
List<List<String>> rows = result.getRows();

// Fluent
try (WinRMClient client = WinRMClient.builder("server")
        .credentials("DOMAIN\\user", password)
        .timeout(Duration.ofSeconds(30))
        .build()) {
    WqlResult result = client.wql("SELECT Name, State FROM Win32_Service").execute();
    List<String> columns = result.columns();
    for (WqlRow row : result) {
        row.string("Name");   // by name, case-insensitive — no more index arithmetic
    }
}
```

### Commands

```java
// 1.x style (still works)
WindowsRemoteCommandResult result = WinRMCommandExecutor.execute(
    "CSCRIPT c:\\collect.vbs", HTTPS, "server", null, "DOMAIN\\user", password,
    null, 30_000L, List.of("c:\\collect.vbs"), null, singletonList(NTLM));

// Fluent
try (WinRMClient client = WinRMClient.builder("server").https()
        .credentials("DOMAIN\\user", password)
        .timeout(Duration.ofSeconds(30))
        .build()) {
    CommandResult result = client.command("CSCRIPT c:\\collect.vbs")
        .upload(Path.of("c:\\collect.vbs"))
        .execute();
    result.stdout();
    result.exitCode();
}
```

### What maps to what

| 1.x | Fluent |
| --- | --- |
| `protocol` argument (`WinRMHttpProtocolEnum`) | `https()` / `http()` on the builder |
| `port` argument | `port(int)` |
| `namespace` argument | `namespace(String)` on the builder or per query |
| `timeout` in milliseconds | `timeout(Duration)` on the builder or per operation |
| `ticketCache` argument | `ticketCache(Path)` |
| `List<AuthenticationEnum>` | `authentication(AuthScheme...)` — same ordered-fallback semantics |
| `localFileToCopyList` | `upload(Path...)` on the command |
| `-Dorg.metricshub.winrm.tls.insecure=true` | `trustAllCertificates()` per client (or `sslContext(...)` for a dedicated trust store) |
| `getHeaders()` / `getRows()` (parallel lists) | `WqlResult.columns()` / iterable `WqlRow` with lookup by property name |
| `getStatusCode()` | `CommandResult.exitCode()` |

### Exceptions become unchecked

The fluent API reports failures through the unchecked
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html)
hierarchy instead of the 1.x checked exceptions — no more mandatory `try`/`catch` around every
call, and WSMan faults expose their code and detail as fields:

| 1.x checked exception | Fluent unchecked exception |
| --- | --- |
| `WinRMException` with `Authentication error on ...` message | [`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html) |
| `WinRMException` carrying WSMan fault text | [`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) — `getFaultCode()`, `getFaultDetail()` instead of `contains()` on the message |
| `java.util.concurrent.TimeoutException` | [`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html) |
| `WqlQuerySyntaxException` | [`WqlSyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlSyntaxException.html) |

The exception **messages are unchanged**, so code that matches on message text keeps working after
the switch. See [Timeouts and Errors](timeouts-and-errors.html) for the complete picture.
