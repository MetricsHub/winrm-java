keywords: legacy, static, executeWql, WinRMWqlExecutor, WinRMCommandExecutor, checked exceptions
description: The legacy static API that predates WinRMClient — still supported, summarized for reference.

# Legacy API

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Before the fluent [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html), the library was
used through two static one-shot helpers. They remain **fully supported**, with the same signatures
as in 1.x, but each call opens a connection, authenticates, runs one operation, and closes
everything. New code should use the [fluent API](index.html); see
[Migrating from 1.x](migrating-from-1x.html) for the runtime changes (for example, HTTPS
certificates are now validated) and the mapping.

## WQL queries

[`WinRMWqlExecutor.executeWql(...)`](apidocs/org/metricshub/winrm/wql/WinRMWqlExecutor.html):

```java
WinRMWqlExecutor result = WinRMWqlExecutor.executeWql(
    protocol,        // WinRMHttpProtocolEnum.HTTP or HTTPS (null → HTTP)
    hostname,        // mandatory
    port,            // Integer, null → 5985 (HTTP) / 5986 (HTTPS)
    username,        // "DOMAIN\\user" or "user", mandatory
    password,        // char[]
    namespace,       // null → ROOT\CIMV2
    wqlQuery,        // mandatory
    timeout,         // long, milliseconds, > 0
    ticketCache,     // java.nio.file.Path, Kerberos ticket cache; null → log in with the password
    authentications  // List<AuthenticationEnum>, null/empty → NTLM
);

result.getHeaders();        // List<String> — column names
result.getRows();           // List<List<String>> — values in header order
result.getExecutionTime();  // long, milliseconds
```

Declared exceptions:
[`WinRMException`](apidocs/org/metricshub/winrm/exceptions/WinRMException.html),
[`WqlQuerySyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlQuerySyntaxException.html),
`java.util.concurrent.TimeoutException` — all **checked**.

## Remote commands

[`WinRMCommandExecutor.execute(...)`](apidocs/org/metricshub/winrm/command/WinRMCommandExecutor.html):

```java
WindowsRemoteCommandResult result = WinRMCommandExecutor.execute(
    command,              // mandatory
    protocol,             // null → HTTP
    hostname,             // mandatory
    port,                 // null → protocol default
    username,             // mandatory
    password,             // char[]
    workingDirectory,     // nullable; ignored when localFileToCopyList is not empty
    timeout,              // long, milliseconds, > 0
    localFileToCopyList,  // List<String> of local files to copy first, nullable
    ticketCache,          // nullable
    authentications       // null/empty → NTLM
);

result.getStdout();
result.getStderr();
result.getStatusCode();     // process exit code
result.getExecutionTime();  // float, seconds
```

Files listed in `localFileToCopyList` are copied to the host first, and each reference to them in
`command` is rewritten to the remote copy — the same engine as the fluent `upload(...)`; see
[File Transfers](file-transfers.html).

Declared exceptions: `java.io.IOException`, `java.util.concurrent.TimeoutException`,
[`WindowsRemoteException`](apidocs/org/metricshub/winrm/exceptions/WindowsRemoteException.html) —
all **checked**.

## Behavior notes

* **One connection per call**: every invocation performs a full authentication handshake. Code
  that polls the same host repeatedly pays that cost on every call — the main reason to move to
  the reusable `WinRMClient`.
* Command output is decoded as UTF-8 (the remote shell runs under console code page 65001), as
  with the fluent client by default; see [Character encoding](commands.html#character-encoding).
* The static helpers take no TLS settings: trust a certificate through the JVM trust store
  (`-Djavax.net.ssl.trustStore=...`), or, for testing only, disable validation with
  `-Dorg.metricshub.winrm.tls.insecure=true` (see [TLS / HTTPS](tls.html#trusting-a-certificate)).
* Authentication schemes come from
  [`AuthenticationEnum`](apidocs/org/metricshub/winrm/service/client/auth/AuthenticationEnum.html)
  (`NTLM`, `KERBEROS`, `BASIC`), with the same
  [ordered-fallback semantics](authentication.html#ordered-fallback) as the fluent
  [`AuthScheme`](apidocs/org/metricshub/winrm/AuthScheme.html): a list that contains `KERBEROS`
  requires HTTPS (over HTTP the call fails, even when `NTLM` comes first).
* For advanced use, the underlying reusable executor is also public:
  [`WinRMExecutorFactory.createInstance(...)`](apidocs/org/metricshub/winrm/service/WinRMExecutorFactory.html)
  returns a [`WindowsRemoteExecutor`](apidocs/org/metricshub/winrm/WindowsRemoteExecutor.html) —
  but the fluent `WinRMClient` is the supported way to get connection reuse.
