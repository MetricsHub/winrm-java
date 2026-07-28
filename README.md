# WinRM Java Client

![GitHub release (with filter)](https://img.shields.io/github/v/release/metricshub/winrm-java)
![Build](https://img.shields.io/github/actions/workflow/status/metricshub/winrm-java/deploy.yml)
![GitHub top language](https://img.shields.io/github/languages/top/metricshub/winrm-java)
![License](https://img.shields.io/github/license/metricshub/winrm-java)

See **[Project Documentation](https://metricshub.org/winrm-java)** and the [Javadoc](https://metricshub.org/winrm-java/apidocs) for more information on how to use this library in your code.

The Windows Remote Management (WinRM) Java Client is a library that enables to:
* Connect to a remote Windows server using one of the two authentication types (NTLM, KERBEROS)
* Execute WMI Query Language (WQL) queries which uses HTTP/HTTPS protocols.

> ## ⚠️ Upgrading from 1.x
>
> Version 2.0.0 **removed the legacy Apache CXF backend**: the dependency-free **light** client is
> the only implementation (same documented entry points; a few CXF/SMB-only public types were
> removed). The main consequence:
>
> * Unlike the CXF-based client, which silently trusted every TLS certificate, the light client
>   **validates the server certificate and verifies the hostname by default**.
>   **WinRM-over-HTTPS connections to hosts with self-signed or otherwise untrusted certificates
>   will fail** during the TLS handshake unless you install the server certificate (or its issuing
>   CA) into a Java trust store (e.g. `-Djavax.net.ssl.trustStore=...`) or disable TLS validation
>   with `-Dorg.metricshub.winrm.tls.insecure=true` (**insecure — for testing only**).
> * The collections returned by `WinRMWqlExecutor.getHeaders()`/`getRows()` and
>   `WqlQuery.getSelectedProperties()`/`getSubPropertiesMap()` are now **unmodifiable views**
>   (and `WinRMWqlExecutor` copies the lists passed to its constructor): callers that mutated
>   the returned collections must now copy them first.

## Quick start

The fluent `WinRMClient` is the entry point of the library: one client authenticates once and can
run any number of WQL queries and commands over the same connection. All failures are reported
through the unchecked `WinRMClientException` hierarchy (`WinRMAuthenticationException`,
`WinRMFaultException` with the WSMan fault code and detail as fields, `WinRMTimeoutException`,
`WqlSyntaxException`).

```java
import java.nio.file.Path;
import java.time.Duration;
import org.metricshub.winrm.*;

try (WinRMClient client = WinRMClient.builder("server01.acme.com")
        .credentials("ACME\\admin", password)          // char[], wiped by you afterward
        .timeout(Duration.ofSeconds(30))               // default for all operations
        .build()) {

    // WQL query
    WqlResult services = client.wql("SELECT Name, State FROM Win32_Service").execute();
    for (WqlRow row : services) {
        System.out.println(row.string("Name") + " is " + row.string("State"));
    }

    // Remote command
    CommandResult result = client.command("ipconfig /all").execute();
    System.out.println(result.stdout());

    // Copy a file to the host (through the WinRM channel itself — no SMB)
    client.uploadFile(Path.of("collect.ps1"), "C:\\Windows\\Temp\\collect.ps1");
}
```

Connection-scoped options on the builder: `https()`, `port(int)`,
`authentication(AuthScheme.KERBEROS, AuthScheme.NTLM)` (ordered fallback; NTLM is the default),
`ticketCache(Path)`, `namespace(String)`, `trustAllCertificates()` (per-client alternative to the
`org.metricshub.winrm.tls.insecure` system property; insecure, testing only), and
`sslContext(SSLContext)` for a dedicated trust store.

Per-operation options: `namespace(...)`, `timeout(...)`, and for WQL enumeration tuning
`pageSize(int)` (WS-Enumeration `MaxElements`, 32000 by default) and `pullTimeout(Duration)`
(`MaxTime` per Pull). Commands accept `workingDirectory(String)`, `charset(Charset)` (detected
from the remote code set by default), and `upload(Path...)` to copy local script files and rewrite
the command to reference the remote copies.

### Streaming

Both operations also have a streaming terminal for large result sets and long-running commands —
everything upstream (authentication, TLS, namespace, options) is shared with the blocking
terminals:

```java
// WQL rows are pulled from the server page by page as the stream advances:
// memory stays bounded by one page (pageSize(int)), not by the whole result set.
try (Stream<WqlRow> rows = client.wql("SELECT * FROM Win32_NTLogEvent").stream()) {
    rows.filter(r -> "Error".equals(r.string("Type")))
        .limit(100)
        .forEach(System.out::println);
}

// Commands can be consumed while they run, java.lang.Process-style:
try (RemoteProcess p = client.command("wevtutil qe System /f:text").start()) {
    try (BufferedReader out = p.stdout()) {
        out.lines().forEach(System.out::println);
    }
    int exitCode = p.waitFor();               // or waitFor(Duration) for a deadline
}

// Middle ground: tail the output live, keep the blocking terminal and its full result.
client.command("longRunningThing.exe")
    .onStdout(chunk -> log.info(chunk))
    .onStderr(chunk -> log.warn(chunk))
    .execute();
```

Streams and processes **must be closed** (try-with-resources): they hold the client's serial
connection while open, and closing early releases the server-side enumeration (WS-Enumeration
`Release`) or terminates the remote command (WinRM terminate `Signal`). For the streaming
terminals the configured timeout is an **inactivity** timeout — the longest silence tolerated from
the server between two responses — not an overall deadline, so long tails can stream indefinitely.
Output is decoded incrementally: a multibyte character split across protocol chunks is decoded
correctly.

The pre-existing static helpers (`WinRMWqlExecutor.executeWql(...)`,
`WinRMCommandExecutor.execute(...)`) keep working unchanged — see **Legacy API** below.

## The WinRM client

The client has **zero runtime dependencies** (no Apache CXF / JAX-WS / JAXB, no BouncyCastle, no
SLF4J — problems are reported through exceptions only) and is immune by construction to JAXP
`ServiceLoader` conflicts (it uses the JDK-default XML factories). It supports **NTLM over HTTP
(with message encryption) and HTTPS** and **Kerberos (SPNEGO) over HTTPS**.

Files passed to `upload(...)` (or `localFileToCopyList` in the legacy API) are copied to the
remote host **through the WinRM channel itself** (chunked base64 through the command shell,
decoded with `certutil` and verified with a digest): no SMB, no TCP port 445, no administrative
share — and it works from any client OS. A file already present on the remote host with an
identical digest is not transferred again. This transport is designed for small script files, not
bulk data. The full mechanics — destination directory, temporary files, integrity verification,
cleanup, and command-line substitution — are documented on the
[File Transfers](https://metricshub.org/winrm-java/file-transfers.html) page. Over HTTPS it validates the certificate and verifies the hostname by default (see the
upgrade warning above); `trustAllCertificates()` on the builder or
`-Dorg.metricshub.winrm.tls.insecure=true` trusts all certificates (insecure, testing only).
Kerberos uses the ambient Kerberos configuration (`krb5.conf` / `-Djava.security.krb5.*`) unless
the command-line KDC and realm options described below are used.

### Legacy API

The static one-shot helpers that predate `WinRMClient` remain available and unchanged, with their
checked exceptions: `WinRMWqlExecutor.executeWql(...)` and `WinRMCommandExecutor.execute(...)`.
They open a connection, run one operation, and close it — prefer `WinRMClient` for anything that
runs more than one operation against the same host.

## Command-line client

Every build produces the regular library JAR and an additional self-contained executable:

```text
target/winrm-java-<version>.jar
target/winrm-java-<version>-standalone.jar
```

Run a WQL query:

```bash
java -jar target/winrm-java-<version>-standalone.jar \
  --hostname server.example.net --username 'DOMAIN\user' \
  --password-file password.txt --ntlm \
  wql 'SELECT Name,State FROM Win32_Service'
```

Run a remote command (`cmd`, `exec`, and `run` are aliases for `command`):

```bash
java -jar target/winrm-java-<version>-standalone.jar \
  -h server.example.net -u Administrator -pf password.txt --https \
  exec ipconfig /all
```

Use `--help` for the option list and `--version` for the build version. The CLI is built on the
streaming API: WQL rows are written to stdout as UTF-8 [JSON Lines](https://jsonlines.org/) **as
the enumeration pages arrive**, and remote command stdout and stderr are forwarded **live** to the
corresponding local streams while the command runs. Diagnostics go only to stderr, and the exit
codes are stable for scripting.

The full manual — options, password handling, Kerberos configuration, streaming and timeout
semantics, exit codes — is the
[Command-Line Client](https://metricshub.org/winrm-java/cli.html) page.

## Build instructions

This is a simple Maven project. Build with:

```bash
mvn verify
```

### Protocol tests

The build includes in-process protocol tests (`WsmanProtocolTest`) that exercise the client's
full WSMan path — NTLM handshake, message encryption, `multipart/encrypted` framing, WQL
Enumerate/Pull paging, the command shell lifecycle, and fault mapping — against a fake WSMan
server, so no Windows host is needed in CI.

### Live run against a real host

`WinRMLiveTest` runs a WQL query and a command against a **real** WinRM host (the successor of
the pre-2.0.0 CXF-vs-light differential harness). It is skipped unless `winrm.live.host` is set:

```bash
mvn test -Dtest=WinRMLiveTest \
  -Dwinrm.live.host=myhost.example.com \
  -Dwinrm.live.protocol=https \
  -Dwinrm.live.username='MYDOMAIN\myuser' \
  -Dwinrm.live.password-file=/path/to/password.txt
```

Optional properties: `winrm.live.port`, `winrm.live.password` (inline), `winrm.live.namespace`,
`winrm.live.wql`, `winrm.live.command`, and `winrm.live.tls.insecure=true` (skip TLS validation
for hosts with self-signed certificates).

## Release instructions

The artifact is deployed to Sonatype's [Maven Central](https://central.sonatype.com/).

The actual repository URL is https://s01.oss.sonatype.org/, with server Id `ossrh` and requires credentials to deploy
artifacts manually.

But it is strongly recommended to only use [GitHub Actions "Release to Maven Central"](actions/workflows/release.yml) to perform a release:

* Manually trigger the "Release" workflow
* Specify the version being released and the next version number (SNAPSHOT)
* Release the corresponding staging repository on [Sonatype's Nexus server](https://s01.oss.sonatype.org/)
* Merge the PR that has been created to prepare the next version

## License

License is Apache-2. Each source file must include the Apache-2 header (build will fail otherwise).
To update source files with the proper header, simply execute the below command:

```bash
mvn license:update-file-header
```
