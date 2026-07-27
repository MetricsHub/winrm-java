keywords: timeout, exception, error, winrmclientexception, wsmanfault, exit code
description: Timeout semantics and the exception surface of the WinRM Java Client, plus the command-line exit codes.

# Timeouts and Errors

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

## Timeouts

Timeouts are `java.time.Duration` values and must be **at least one millisecond**. The builder's
`timeout(...)` sets the default for every operation (30 seconds when unset), and each operation
can override it:

```java
try (WinRMClient client = WinRMClient.builder("server.example.com")
        .credentials("DOMAIN\\Administrator", password)
        .timeout(Duration.ofSeconds(30))                 // client default
        .build()) {

    client.wql("SELECT * FROM Win32_NTLogEvent")
        .timeout(Duration.ofMinutes(2))                  // this query only
        .execute();
}
```

The timeout is a **wall-clock deadline for the whole operation**: it covers authentication (on the
first operation), every WSMan round trip, and — for commands — the file uploads and the
remote-encoding detection, all budgeted against the same deadline. When it elapses, the operation
fails with
[`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html) and
no part of it (in particular: the command itself) runs afterward.

The timeout also drives the wire-level behavior: the WSMan `OperationTimeout` header and the
socket timeouts follow each operation's own deadline.

## The exception surface

The fluent API is **unchecked**: every failure is a
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html), with
subtypes for the cases worth catching specifically:

| Exception | Meaning |
| --- | --- |
| [`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html) | The credentials were rejected (after every scheme of an ordered fallback list). |
| [`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) | The remote service answered with a WSMan fault. |
| [`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html) | The operation exceeded its timeout. |
| [`WqlSyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlSyntaxException.html) | The WQL query does not match the supported `SELECT` syntax. |
| [`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html) | Base type: any other failure (connection, DNS, TLS, protocol, local I/O). |

`IllegalArgumentException` (invalid option values) and `IllegalStateException` (missing
credentials at `build()`, or an operation on a closed client) report programming errors
immediately, before anything touches the network.

### Fault detail

[`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) exposes
the fault **programmatically**, so no message parsing is needed:

| Method | Returns |
| --- | --- |
| `getFaultCode()` | The numeric WSManFault code, e.g. `2150858778`. |
| `getFaultReason()` | The SOAP fault reason text. |
| `getFaultDetail()` | The provider-level detail — where WMI puts mnemonics such as `WBEM_E_INVALID_CLASS` or `WBEM_E_INVALID_NAMESPACE`. |
| `getHttpStatus()` | The HTTP status of the faulting response (typically 500). |

The exception message still carries the same text as the legacy API, so message-based matching
keeps working.

### Authentication failures

A rejected credential surfaces as a
[`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html)
whose message has the stable form `Authentication error on <endpoint> with user name "<user>"`.

### The legacy API

The [legacy static helpers](legacy.html) keep their historical **checked** exceptions
(`WinRMException`, `WqlQuerySyntaxException`, `TimeoutException`, `IOException`); they are
unaffected by the unchecked hierarchy above.

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
