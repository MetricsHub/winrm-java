keywords: timeout, exception, error, winrmclientexception, wsmanfault
description: Timeout semantics and the exception surface of the WinRM Java Client.

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
first operation), every WSMan round trip, and — for commands — the file uploads, all budgeted
against the same deadline. When it elapses, the operation
fails with
[`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html) and
no part of it (in particular: the command itself) runs afterward.

The timeout also drives the wire-level behavior: the WSMan `OperationTimeout` header and the
socket timeouts follow each operation's own deadline.

### Streaming terminals: inactivity timeout

The streaming terminals — `stream()` on a WQL request and `start()` on a command (see
[WQL Queries](wql.html) and [Remote Commands](commands.html)) — interpret the same `timeout(...)`
value differently, because an overall deadline would make long-running streams impossible: there
it is an **inactivity timeout**, the longest silence tolerated from the server between two
responses. A query result can be consumed, or a command can keep streaming output, for arbitrarily
long — but as soon as the server stays silent for a whole timeout, the operation fails with
[`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html).
For commands, `RemoteProcess.waitFor(Duration)` provides an overall deadline on top when one is
needed.

## Retrying transient connection failures

By default there is **no retry**: a transient network failure — a dropped connection, a DNS
hiccup, a momentarily unreachable host — fails the operation immediately. The opt-in
`retries(...)` builder setting rides out such failures, which long-running monitoring and
automation workloads usually want:

```java
WinRMClient client = WinRMClient.builder("server.example.com")
    .credentials("DOMAIN\\Administrator", password)
    .retries(2, Duration.ofSeconds(5))   // up to 2 retries, pausing 5 s before each
    .build();
```

The policy is deliberately narrow, to preserve **at-most-once execution** for non-idempotent
commands: a WSMan round trip is retried only when it failed to **establish and authenticate the
connection** — TCP connect, DNS resolution, the TLS handshake, or a transport error during the
authentication handshake — because there the request provably never reached the server. WQL
queries get no broader surface even though they are idempotent: re-issuing a WS-Enumeration
`Pull` whose response was lost could skip or duplicate rows.

The policy is connection-scoped: it applies to every round trip of every operation on the
client, including reconnections in the middle of one (WinRM connections are re-established
transparently when the server drops an idle one). What is **never** retried:

* a request that reached the wire — a command that may have started, a query that may be
  executing;
* credential rejections
  ([`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html));
* WSMan faults
  ([`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html)).

Retries stay inside each operation's **wall-clock deadline**: when the timeout elapses mid-pause,
the operation fails with
[`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html)
exactly as it would without a retry policy. For the streaming terminals (whose timeout is an
inactivity timeout with no overall deadline), each connection attempt is bounded by that timeout,
so a retried reconnection can extend the tolerated silence accordingly.

Users migrating from winrm4j: `retries(1, Duration.ofSeconds(5))` is the closest equivalent of
its default `failureRetryPolicy` (one retry, 5-second pause).

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

The standalone jar maps these outcomes to stable process exit codes — see the
[Command-Line Client](cli.html) manual.

## See also

* [Preparing the Windows Host](preparing-the-host.html) — a symptom-to-cause table for host-side
  causes: WinRM disabled, a closed firewall, UAC token filtering, and missing WMI rights
* [Authentication](authentication.html) — how authentication failures are reported
