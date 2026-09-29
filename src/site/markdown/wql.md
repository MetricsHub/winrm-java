keywords: wql, wmi, query, win32, namespace, root cimv2, cim
description: Run WQL / WMI queries with the fluent WinRMClient API and read the result rows.

# WQL Queries

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

WQL (WMI Query Language) is the SQL-like language used to query the Windows Management
Instrumentation (WMI) repository. The client runs a query on the remote host and returns the rows.

## Running a query

Build a [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html), then prepare the query
with `wql(...)` and run it with `execute()`:

```java
import java.time.Duration;
import org.metricshub.winrm.WinRMClient;
import org.metricshub.winrm.WqlResult;
import org.metricshub.winrm.WqlRow;

try (WinRMClient client = WinRMClient.builder("server.example.com")
        .credentials("DOMAIN\\Administrator", password)
        .timeout(Duration.ofSeconds(30))
        .build()) {

    WqlResult result = client.wql("SELECT Name, State FROM Win32_Service").execute();

    for (WqlRow row : result) {
        System.out.println(row.string("Name") + " is " + row.string("State"));
    }
}
```

One client can run any number of queries (and [commands](commands.html)) over the same
authenticated connection — see the [client options](index.html#client-options).

### Query options

Everything between `wql(...)` and `execute()` is optional:

| Option | Default | Meaning |
| --- | --- | --- |
| `namespace(String)` | the client's namespace (`ROOT\CIMV2` unless set on the builder) | The WMI namespace to query. |
| `timeout(Duration)` | the client's timeout | Wall-clock deadline for the whole query with `execute()`; inactivity timeout with `stream()`. See [Timeouts and Errors](timeouts-and-errors.html). |
| `pageSize(int)` | 32000 | WS-Enumeration `MaxElements`: how many rows the server may return per protocol round trip. |
| `pullTimeout(Duration)` | server default | WS-Enumeration `MaxTime`: how long the server may hold a single `Pull` open before answering with the rows it has. |

```java
WqlResult events = client.wql("SELECT * FROM Win32_NTLogEvent")
    .namespace("root\\cimv2")
    .pageSize(5000)
    .pullTimeout(Duration.ofSeconds(10))
    .timeout(Duration.ofMinutes(2))
    .execute();
```

`pageSize` and `pullTimeout` matter for very large result sets (for example Windows event logs):
a smaller page bounds each response's size, and a pull timeout keeps the server from holding a
`Pull` open past your deadline while it gathers rows.

## Streaming the rows

`execute()` collects the complete result in memory. For very large result sets — Windows event
logs, software inventories — end the same request with `stream()` instead: it returns a lazy
`java.util.stream.Stream` of [`WqlRow`](apidocs/org/metricshub/winrm/WqlRow.html)s that yields
each row as soon as it is parsed and pulls the next WS-Enumeration page from the server only as
the stream advances. Memory stays bounded by one page (`pageSize(int)`), not by the whole result
set.

```java
try (Stream<WqlRow> rows = client.wql("SELECT * FROM Win32_NTLogEvent")
        .pageSize(5000)
        .stream()) {

    rows.filter(row -> "Error".equals(row.string("Type")))
        .limit(100)
        .forEach(this::process);
}
```

Points to know:

* **Close the stream** — use try-with-resources, exactly like `Files.lines(...)`. Closing before
  the last row tells the server to free the enumeration immediately (WS-Enumeration `Release`);
  an exhausted stream cleans up on its own.
* The stream **holds the client's serial connection** while open: other operations on the same
  client wait until it is closed or exhausted (the same contract as a JDBC `ResultSet` on its
  connection). Never call the same client while consuming the stream; use a second client.
* The timeout is an **inactivity** timeout — the longest silence tolerated from the server between
  two responses — not an overall deadline: consuming a huge result can take arbitrarily long as
  long as the server keeps answering. See [Timeouts and Errors](timeouts-and-errors.html).
* The stream is sequential and ordered; failures while iterating are reported through the same
  unchecked exceptions as `execute()` (see below).

## Reading the result

[`WqlResult`](apidocs/org/metricshub/winrm/WqlResult.html) is immutable and iterable:

| Method | Returns | Description |
| --- | --- | --- |
| `columns()` | `List<String>` | The property (column) names (see [Column order and case](#column-order-and-case)). |
| `rows()` | `List<`[`WqlRow`](apidocs/org/metricshub/winrm/WqlRow.html)`>` | The result rows. |
| `size()` / `isEmpty()` | `int` / `boolean` | Row count. |
| `elapsed()` | `java.time.Duration` | Wall-clock time of the query. |

Each [`WqlRow`](apidocs/org/metricshub/winrm/WqlRow.html) exposes the instance properties:

| Method | Returns | Description |
| --- | --- | --- |
| `string(String)` | `String` | The property value, or `null` when the row has no such property. |
| `get(String)` | `Object` | The same value (currently always a `String`). |
| `asMap()` | `Map<String, Object>` | All properties, in server order (unmodifiable). |

Property lookup is **case-insensitive**, matching WMI semantics: `row.string("name")` and
`row.string("Name")` return the same value.

Values are the text WinRM sends, unconverted: parse numbers, booleans and dates yourself. A WMI
`NULL` comes back as an empty string.

### Column order and case

These rules apply to `WqlResult.columns()`; each row's `asMap()` keeps the server's order, and
lookups in it are case-sensitive.

* When you select explicit properties (`SELECT Name, State FROM ...`), the columns keep the **order
  of the query** and the **exact case reported by WMI**.
* With `SELECT * FROM ...`, the columns are in **alphabetical order** (case-insensitive).
* If the query returns no rows, the columns are the selected property names in lower case (WMI's
  own casing cannot be recovered from an empty result set), or an empty list for `SELECT *`.

## Supported WQL syntax

The client validates that the query is a simple `SELECT`:

```sql
SELECT * FROM Win32_OperatingSystem
SELECT Name, State, StartMode FROM Win32_Service
SELECT Name FROM Win32_Process WHERE Name = 'explorer.exe'
```

The grammar is a single `SELECT` of either `*` or a comma-separated property list, a `FROM` clause
naming one class, and an optional `WHERE` clause. Line breaks are accepted wherever whitespace is,
so queries written as Java text blocks work as is. Anything else, such as `ASSOCIATORS OF`,
`REFERENCES OF` or event queries (`WITHIN`), is rejected: an invalid query raises a
[`WqlSyntaxException`](apidocs/org/metricshub/winrm/exceptions/WqlSyntaxException.html) before
anything is sent to the host.

## Choosing a namespace

Most Windows classes live under the default `ROOT\CIMV2` namespace. To query a different one — for
example `ROOT\Microsoft\SqlServer` or `ROOT\WMI` — set it per query with `namespace(...)`, or set a
client-wide default with `namespace(...)` on the builder. Both `ROOT\WMI` and `ROOT/WMI` are
accepted.

## Exceptions

`execute()` and `stream()` report failures as unchecked
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html)s; see
[Timeouts and Errors](timeouts-and-errors.html#the-exception-surface) for the full list. An
unknown class or namespace raises a
[`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) whose
`getFaultDetail()` carries the WMI error, e.g. `WBEM_E_INVALID_CLASS` or
`WBEM_E_INVALID_NAMESPACE`.

## From the command line

The standalone jar runs queries in the default `ROOT\CIMV2` namespace through its `wql` subcommand,
streaming the rows to stdout as JSON Lines — see the [Command-Line Client](cli.html) manual.
