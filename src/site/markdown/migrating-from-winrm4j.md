keywords: winrm4j, migration, cloudsoft, cxf, WinRmTool, WinRmClientBuilder, executePs, executeCommand
description: Move from cloudsoft/winrm4j to the WinRM Java Client: why, a one-for-one option mapping, and the behavioral differences to know about.

# Migrating from winrm4j

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

[winrm4j](https://github.com/cloudsoft/winrm4j) has served Java projects well, but it is dormant —
the last release (0.12.3) dates back to August 2021 and the last commit to March 2023 — and its
Apache CXF / Java 8-era stack has aged poorly: on modern JDKs it needs manually added JAXB and
JAX-WS dependencies, and its `ServiceLoader`-based XML factory lookup is a known source of
classpath conflicts. This page maps the winrm4j API to the **WinRM Java Client** so an existing
code base can switch in one sitting.

## Why migrate

* **Zero runtime dependencies.** winrm4j pulls in the Apache CXF stack — dozens of jars, roughly
  10&nbsp;MB — plus whatever JAXB/JAX-WS additions your JDK requires. This client is a **single jar
  of a few hundred kB** that speaks WS-Management over the JDK's own HTTP and XML APIs.
* **Modern JDKs, no workarounds.** The library targets Java 11 and runs on any later JDK — no
  `jakarta`/`javax` juggling, no JAXB add-ons.
* **Immune to JAXP conflicts by construction.** It uses the JDK-default XML factories directly,
  so another library's `ServiceLoader`-registered XML implementation cannot break it — a classic
  winrm4j/CXF failure mode.
* **Actively maintained**, with releases published on Maven Central.
* **Features winrm4j never had:** [WQL / WMI queries](wql.html), [file transfers](file-transfers.html)
  through the WinRM channel, [standard input](commands.html#standard-input),
  [`Process`-style streaming](commands.html#streaming-the-output) of live output, and a
  full-featured [command-line client](cli.html).

## The five-minute version

```java
// winrm4j
WinRmTool tool = WinRmTool.Builder.builder("server.example.com", "DOMAIN\\Administrator", "password")
    .authenticationScheme(AuthSchemes.NTLM)
    .port(5985)
    .useHttps(false)
    .build();
WinRmToolResponse response = tool.executeCommand("ipconfig /all");
System.out.println(response.getStdOut());
System.out.println(response.getStatusCode());
```

becomes:

```java
// WinRM Java Client
try (WinRMClient client = WinRMClient.builder("server.example.com")
        .credentials("DOMAIN\\Administrator", "password".toCharArray())
        .build()) {
    CommandResult result = client.command("ipconfig /all").execute();
    System.out.println(result.stdout());
    System.out.println(result.exitCode());
}
```

NTLM over HTTP on port 5985 is the default on both sides, so nothing needs to be spelled out. Two
shape differences are visible immediately:

* The client is **`AutoCloseable`** and meant for try-with-resources — it authenticates once and
  runs any number of commands and queries over the same connection, where winrm4j re-created a
  shell (and re-authenticated) on every `executeCommand(...)` call.
* The password is a **`char[]`**, not a `String`, so the caller can wipe the single authoritative
  copy of the secret after closing the client.

## Option mapping

### Builder options

winrm4j has two builders — `WinRmTool.Builder` (high level) and `WinRmClientBuilder` (low level) —
with largely overlapping options. Both map to the single
[`WinRMClient.builder(hostname)`](apidocs/org/metricshub/winrm/WinRMClient.html):

| winrm4j | WinRM Java Client |
| --- | --- |
| `WinRmTool.Builder.builder(address, username, password)` | `WinRMClient.builder(hostname).credentials(username, password)` — password as `char[]` |
| `builder(address, domain, username, password)` | `credentials("DOMAIN\\user", password)` — the domain rides in the user name |
| `useHttps(true)` | `https()` |
| `port(int)` | `port(int)` |
| `authenticationScheme(AuthSchemes.NTLM)` | `authentication(AuthScheme.NTLM)` — the default; several schemes form an ordered fallback list ([Authentication](authentication.html)) |
| `authenticationScheme(AuthSchemes.KERBEROS)` | `authentication(AuthScheme.KERBEROS)` — requires `https()` (see [behavioral differences](#behavioral-differences)) |
| `authenticationScheme(AuthSchemes.BASIC)` | none — use NTLM; see [behavioral differences](#behavioral-differences) |
| `disableCertificateChecks(true)` | `trustAllCertificates()` |
| `sslContext(SSLContext)` | `sslContext(SSLContext)` — hostname verification stays on |
| `hostnameVerifier(...)`, `sslSocketFactory(...)` | none — covered by `sslContext(...)` / `trustAllCertificates()`; see [TLS / HTTPS](tls.html) |
| `operationTimeout(long)` (milliseconds) | `timeout(Duration)` — different semantics, see [behavioral differences](#behavioral-differences) |
| `connectionTimeout(long)`, `connectionRequestTimeout(long)`, `receiveTimeout(Long)` | none — the single `timeout(Duration)` is a wall-clock deadline covering all of it |
| `retriesForConnectionFailures(int)` | `retries(int, Duration)` — **opt-in**; see [behavioral differences](#behavioral-differences) |
| `failureRetryPolicy(...)`, `retryReceiveAfterOperationTimeout(...)` | none — see [behavioral differences](#behavioral-differences) |
| `workingDirectory(String)` | `workingDirectory(String)` — on the **command**, not the client ([command options](commands.html#command-options)) |
| `environment(Map<String, String>)` | `environment(String, String)` — on the **command**, once per variable |
| `requestNewKerberosTicket(boolean)` | none — Kerberos logs in with the password by default; `ticketCache(Path)` points at an existing ticket cache instead |
| `context(WinRmClientContext)` | none needed — there is no CXF `Bus` to share or shut down; build clients freely and `close()` them |
| `locale(Locale)` | none — the WSMan locale is not configurable |
| `allowChunking(boolean)`, `payloadEncryptionMode(...)` | none — see [behavioral differences](#behavioral-differences) for encryption |

### Running commands

| winrm4j | WinRM Java Client |
| --- | --- |
| `tool.executeCommand(String)` | `client.command(commandLine).execute()` |
| `tool.executeCommand(String, Writer out, Writer err)` | `command(...).onStdout(chunk -> ...).onStderr(chunk -> ...).execute()` — or `start()` for a `java.lang.Process`-style handle ([streaming](commands.html#streaming-the-output)) |
| `tool.executePs(String)` | `client.powerShell(script).execute()` — also base64-encoded (`-EncodedCommand`), so no quoting or escaping ([Running PowerShell](commands.html#running-powershell)) |
| `tool.executePs(List<String>)` | `powerShell(String.join("\n", lines)).execute()` — winrm4j joined the lines for you; here the script is just a string |
| `tool.executeCommand(List<String>)` | one `command(...)` per command line — or join with `" & "` for `cmd.exe` semantics |

### Reading the result

| winrm4j `WinRmToolResponse` | [`CommandResult`](apidocs/org/metricshub/winrm/CommandResult.html) |
| --- | --- |
| `getStdOut()` | `stdout()` |
| `getStdErr()` | `stderr()` |
| `getStatusCode()` | `exitCode()` |

Failures surface through the unchecked
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html)
hierarchy instead of winrm4j's `SOAPFaultException` / `RuntimeException` mix — authentication
rejections, WSMan faults (with the fault code and detail as fields), and timeouts each have their
own type. See [Timeouts and Errors](timeouts-and-errors.html).

Users of the lower-level `WinRmClient` / `createShell()` / `ShellCommand` API map the same way:
one `WinRMClient` plays both roles — `createShell()` + `ShellCommand.execute(cmd, out, err)`
becomes `command(cmd).onStdout(...).onStderr(...).execute()`, and the shell reuse that
`createShell()` provided is automatic (one client keeps one remote shell alive across commands).

## Behavioral differences

Beyond the API shapes, a few runtime behaviors differ deliberately. Worth reading before flipping
the switch:

* **Payload encryption is always on over HTTP.** In winrm4j, NTLM message encryption is a
  configurable `PayloadEncryptionMode` (`OFF` / `OPTIONAL` / `REQUIRED`) that only appeared in its
  final 0.12.x releases. Here, HTTP always uses NTLM message encryption — there is no unencrypted
  mode and nothing to configure, and hosts that require encryption (`AllowUnencrypted=false`, the
  Windows default) work out of the box.
* **No Basic authentication.** Basic sends credentials effectively in the clear and is disabled on
  Windows by default; the client does not implement it. Use NTLM — every account that
  authenticates with Basic also authenticates with NTLM, with no host-side change.
* **Kerberos requires HTTPS.** winrm4j runs Kerberos over plain HTTP; this client refuses at
  `build()`, because it does not implement Kerberos message encryption — without TLS the payload
  would travel unprotected. Connect with `https()` and by the FQDN the KDC knows
  ([Authentication](authentication.html)).
* **TLS certificates are validated by default**, including hostname verification — like winrm4j
  (`disableCertificateChecks` exists on both sides), but worth re-checking if your winrm4j setup
  disabled checks and you want to stop doing that: point `sslContext(...)` at a trust store
  containing the host certificate instead ([TLS / HTTPS](tls.html)).
* **Command output is UTF-8, not code page 437.** winrm4j hardcodes `WINRS_CODEPAGE=437` (US-OEM),
  which mangles any non-ASCII output on non-English hosts. This client creates the remote shell
  with code page **65001 (UTF-8)**, so accented and non-Latin output decodes correctly whatever
  the remote locale — no configuration needed ([Character encoding](commands.html#character-encoding)).
* **Timeout semantics.** winrm4j's `operationTimeout` is the WSMan `Receive` polling timeout (how
  long each poll waits for output), and separate CXF settings govern connect/receive at the HTTP
  level. Here a single `timeout(Duration)` (default 30&nbsp;s) is a **wall-clock deadline for the
  whole operation** with `execute()`, and an **inactivity timeout** with `start()`
  ([Timeouts and Errors](timeouts-and-errors.html)).
* **No silent retries.** winrm4j retries connection failures **once by default** and can be told
  to retry `Receive` after an operation timeout. Here nothing is retried unless you opt in with
  `retries(int, Duration)`, and the policy is deliberately narrow: only attempts that provably
  never reached the server (TCP connect, DNS, TLS handshake, the authentication handshake) are
  retried, preserving **at-most-once execution** for non-idempotent commands. A request that was
  actually sent is never replayed.
* **One shell per client.** winrm4j creates and tears down a remote shell for every
  `executeCommand(...)`. This client creates the shell on the first command and reuses it, which
  is faster — and is why `workingDirectory(...)` and `environment(...)` are per-command options
  that take effect on the client's **first** command ([command options](commands.html#command-options)).

## What you gain

Once on the fluent API, features winrm4j never offered are one call away:

* **WQL / WMI queries** — `client.wql("SELECT Name, State FROM Win32_Service").execute()`, with
  typed rows and streaming ([WQL Queries](wql.html)).
* **File transfers** — `upload(Path...)` copies local scripts to the host through the WinRM
  channel itself (no SMB, no port 445) before the command runs ([File Transfers](file-transfers.html)).
* **Standard input** — `stdin(...)` feeds a remote command its input, with real EOF semantics
  ([Standard input](commands.html#standard-input)).
* **Live streaming** — `start()` returns a `java.lang.Process`-shaped
  [`RemoteProcess`](apidocs/org/metricshub/winrm/RemoteProcess.html) whose output is consumed
  while the command runs, with bounded memory ([Streaming the output](commands.html#streaming-the-output)).
* **A real CLI** — the standalone jar runs commands, PowerShell, WQL queries, and an interactive
  remote shell from the terminal ([Command-Line Client](cli.html)).

## See also

* [Installation](installation.html) — coordinates and supported JDKs
* [Remote Commands](commands.html) — the complete command API
* [Authentication](authentication.html) — NTLM and Kerberos details
* [Timeouts and Errors](timeouts-and-errors.html) — timeout semantics and the exception surface
