keywords: timeout, exception, error, winrmexception, wsmanfault, exit code
description: Timeout semantics and the exception surface of the WinRM Java Client, plus the command-line exit codes.

# Timeouts and Errors

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

## Timeouts

Both `executeWql(...)` and `WinRMCommandExecutor.execute(...)` take a `timeout` in
**milliseconds**. The value must be **greater than zero** — passing `0` or a negative value throws
an `IllegalArgumentException` immediately.

The timeout is a **budget for the whole operation**, not for a single network round trip. Opening
the connection, detecting the remote code page, copying files, and running the query or command all
draw from it. When the budget is exhausted, the call throws
`java.util.concurrent.TimeoutException`.

```java
try {
    executeWql(HTTP, host, null, user, password, null, query, 30_000L, null, singletonList(NTLM));
} catch (java.util.concurrent.TimeoutException e) {
    // the operation did not finish within 30 seconds
}
```

## The exception surface

| Exception | Checked? | Meaning |
| --- | --- | --- |
| [`WindowsRemoteException`](apidocs/org/metricshub/winrm/exceptions/WindowsRemoteException.html) | yes | Base type for a problem on the remote host. |
| [`WinRMException`](apidocs/org/metricshub/winrm/exceptions/WinRMException.html) | yes | A WinRM/WSMan failure — authentication rejection, WMI error, protocol fault, connection or TLS problem. Extends `WindowsRemoteException`. |
| [`WqlQuerySyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlQuerySyntaxException.html) | yes | The WQL query does not match the supported `SELECT` syntax. |
| `java.util.concurrent.TimeoutException` | yes | The operation exceeded its `timeout`. |
| `java.io.IOException` | yes | Declared by `WinRMCommandExecutor.execute(...)` for I/O problems (including copied-file errors). |
| `IllegalArgumentException` | no | A mandatory argument is missing, or `timeout` is not greater than zero. |

`executeWql(...)` declares `WinRMException`, `WqlQuerySyntaxException`, and `TimeoutException`.
`WinRMCommandExecutor.execute(...)` declares `IOException`, `TimeoutException`, and
`WindowsRemoteException`.

### Fault detail

When the remote host returns a WSMan fault, the exception message carries the detailed `WSManFault`
text — including the provider-level detail such as WMI `WBEM_E_*` mnemonics — alongside the SOAP
reason text, so the underlying cause is visible in the message.

### Authentication failures

A rejected credential surfaces as a `WinRMException` whose message is of the form
`Authentication error on <endpoint> with user name "<user>"`.

## Command-line exit codes

The standalone jar maps outcomes to stable process exit codes:

| Exit code | Meaning |
| ---: | --- |
| `0` | Successful WQL query or remote command. |
| `0`–`255` | Remote command exit code, when it fits in that range. |
| `64` | Invalid CLI usage. |
| `69` | Connection, DNS, socket, or TLS failure. |
| `70` | WinRM protocol or other remote failure. |
| `77` | Authentication failure. |
| `124` | Operation timeout. |

Diagnostics are written only to standard error, so a WQL query's JSON Lines output on standard
output is never mixed with error messages.
