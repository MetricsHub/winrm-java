keywords: wql, wmi, query, win32, namespace, root cimv2, cim
description: Run WQL / WMI queries with WinRMWqlExecutor and read the result rows.

# WQL Queries

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

WQL (WMI Query Language) is the SQL-like language used to query the Windows Management
Instrumentation (WMI) repository. The client runs a query on the remote host and returns the rows.

## `WinRMWqlExecutor.executeWql(...)`

A query is executed with the static method
[`WinRMWqlExecutor.executeWql(...)`](apidocs/org/metricshub/winrm/wql/WinRMWqlExecutor.html). It
opens a connection, runs the query, collects every row, closes the connection, and returns a
[`WinRMWqlExecutor`](apidocs/org/metricshub/winrm/wql/WinRMWqlExecutor.html) holding the result.

```java
import static java.util.Collections.singletonList;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTP;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.wql.WinRMWqlExecutor.executeWql;

import org.metricshub.winrm.wql.WinRMWqlExecutor;

WinRMWqlExecutor result = executeWql(
    HTTP,                                        // protocol
    "server.example.com",                        // hostname
    null,                                        // port (null → 5985 for HTTP, 5986 for HTTPS)
    "DOMAIN\\Administrator",                      // username
    "the-password".toCharArray(),                // password
    "ROOT\\CIMV2",                               // namespace (null → ROOT\CIMV2)
    "SELECT Name, State FROM Win32_Service",     // WQL query
    30_000L,                                     // timeout in milliseconds
    null,                                        // Kerberos ticket cache (null for NTLM)
    singletonList(NTLM)                          // authentication schemes
);
```

### Parameters

| Parameter | Type | Notes |
| --- | --- | --- |
| `protocol` | [`WinRMHttpProtocolEnum`](apidocs/org/metricshub/winrm/WinRMHttpProtocolEnum.html) | `HTTP` or `HTTPS`. `null` defaults to `HTTP`. |
| `hostname` | `String` | Host name or IP address. **Mandatory.** |
| `port` | `Integer` | `null` uses the protocol default (5985 for HTTP, 5986 for HTTPS). |
| `username` | `String` | `DOMAIN\user` or `user`. **Mandatory.** See [Authentication](authentication.html). |
| `password` | `char[]` | The password. |
| `namespace` | `String` | WMI namespace. `null` or blank defaults to `ROOT\CIMV2`. Backslashes and forward slashes are both accepted. |
| `wqlQuery` | `String` | The WQL query. **Mandatory.** |
| `timeout` | `long` | Timeout in milliseconds. Must be **greater than zero** (an `IllegalArgumentException` is thrown otherwise). See [Timeouts and Errors](timeouts-and-errors.html). |
| `ticketCache` | `java.nio.file.Path` | Kerberos ticket cache path. `null` for NTLM. See [Authentication](authentication.html). |
| `authentications` | `List<`[`AuthenticationEnum`](apidocs/org/metricshub/winrm/service/client/auth/AuthenticationEnum.html)`>` | Requested schemes. `null` or empty means NTLM only. |

## Reading the result

The returned [`WinRMWqlExecutor`](apidocs/org/metricshub/winrm/wql/WinRMWqlExecutor.html) exposes:

| Method | Returns | Description |
| --- | --- | --- |
| `getHeaders()` | `List<String>` | The property (column) names. |
| `getRows()` | `List<List<String>>` | One `List<String>` per row, with values in the same order as the headers. |
| `getExecutionTime()` | `long` | Wall-clock time of the whole call, in milliseconds. |

```java
List<String> headers = result.getHeaders();       // e.g. [Name, State]
for (List<String> row : result.getRows()) {
    System.out.println(headers + " = " + row);
}
```

### Property order and case

* When you select explicit properties (`SELECT Name, State FROM ...`), the headers keep the **order
  of the query** and the **exact case reported by WMI**.
* With `SELECT * FROM ...`, the properties are returned in **alphabetical order** (case-insensitive).
* If the query returns no rows, the headers fall back to the property names exactly as written in
  the query (WMI's own casing cannot be recovered from an empty result set).

## Supported WQL syntax

The client validates that the query is a simple `SELECT`:

```sql
SELECT * FROM Win32_OperatingSystem
SELECT Name, State, StartMode FROM Win32_Service
SELECT Name FROM Win32_Process WHERE Name = 'explorer.exe'
```

The grammar is a single `SELECT` of either `*` or a comma-separated property list, a `FROM` clause,
and an optional `WHERE` clause. Joins, sub-selects, and other advanced constructs are not part of
the supported syntax. An invalid query raises a
[`WqlQuerySyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlQuerySyntaxException.html).

## Choosing a namespace

Most Windows classes live under the default `ROOT\CIMV2` namespace. To query a different one — for
example `ROOT\Microsoft\SqlServer` or `ROOT\WMI` — pass it as the `namespace` argument. Both
`ROOT\WMI` and `ROOT/WMI` are accepted.

## Exceptions

`executeWql(...)` declares three checked exceptions:

| Exception | When |
| --- | --- |
| [`WqlQuerySyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlQuerySyntaxException.html) | The query does not match the supported `SELECT` syntax. |
| [`WinRMException`](apidocs/org/metricshub/winrm/exceptions/WinRMException.html) | Any WinRM/WSMan problem on the remote host (authentication, WMI error, protocol fault, ...). |
| `java.util.concurrent.TimeoutException` | The operation did not complete within `timeout`. |

See [Timeouts and Errors](timeouts-and-errors.html) for the full exception surface.

## From the command line

The standalone jar exposes the same capability through the `wql` subcommand, printing one compact
UTF-8 JSON object per row ([JSON Lines](https://jsonlines.org/)):

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt --ntlm \
  wql 'SELECT Name, State FROM Win32_Service'
```

Property order follows the WinRM response; diagnostics go only to standard error.
