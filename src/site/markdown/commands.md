keywords: command, execute, cmd, stdout, stderr, exit code, file copy, script
description: Execute remote commands with the fluent WinRMClient API, capture output and exit codes, and copy local files to the host.

# Remote Commands

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

The client can run an arbitrary command on the remote host and hand back its standard output,
standard error, and exit code. It can also copy local script files to the host first and rewrite
the command so it references them.

## Running a command

Build a [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html), then prepare the command
with `command(...)` and run it with `execute()`:

```java
import java.time.Duration;
import org.metricshub.winrm.CommandResult;
import org.metricshub.winrm.WinRMClient;

try (WinRMClient client = WinRMClient.builder("server.example.com")
        .https()
        .credentials("DOMAIN\\Administrator", password)
        .timeout(Duration.ofSeconds(30))
        .build()) {

    CommandResult result = client.command("ipconfig /all").execute();

    System.out.println("exit code: " + result.exitCode());
    System.out.print(result.stdout());
    System.err.print(result.stderr());
}
```

The command line is run through `cmd.exe` by the remote shell. One client can run any number of
commands (and [WQL queries](wql.html)) over the same authenticated connection — see the
[Overview](index.html) for the builder options.

### Command options

Everything between `command(...)` and `execute()` is optional:

| Option | Default | Meaning |
| --- | --- | --- |
| `timeout(Duration)` | the client's timeout | Wall-clock deadline covering file uploads, encoding detection, and the command itself with `execute()`; inactivity timeout with `start()`. |
| `charset(Charset)` | detected from the remote code set | The charset used to decode the command output (see below). |
| `workingDirectory(String)` | remote default | Working directory of the remote process. The remote shell is created by the client's **first** command and reused afterward, so this only takes effect on that first command. |
| `upload(Path...)` | none | Local files to copy to the host before running (see below). |
| `onStdout(Consumer<String>)` / `onStderr(Consumer<String>)` | none | Callbacks receiving each chunk of output live while `execute()` runs (see below). |

## The result

[`CommandResult`](apidocs/org/metricshub/winrm/CommandResult.html) is an immutable value:

| Method | Returns | Description |
| --- | --- | --- |
| `stdout()` | `String` | The command's standard output. |
| `stderr()` | `String` | The command's standard error. |
| `exitCode()` | `int` | The process exit code (Windows HRESULT codes reported as unsigned 32-bit values are narrowed to the equivalent signed `int`). |
| `elapsed()` | `java.time.Duration` | Wall-clock time of the operation. |

## Streaming the output

`execute()` collects the complete output in memory and returns only when the command has exited.
For long-running or verbose commands, end the same request with `start()` instead: it returns a
[`RemoteProcess`](apidocs/org/metricshub/winrm/RemoteProcess.html) — shaped like
`java.lang.Process` — whose output can be consumed **while the command is still running**:

```java
try (RemoteProcess process = client.command("wevtutil qe System /f:text").start()) {
    try (BufferedReader out = process.stdout()) {
        out.lines().forEach(this::process);
    }
    int exitCode = process.waitFor();       // or waitFor(Duration) for an overall deadline
}
```

Points to know:

* **Close the process** — use try-with-resources. Closing before completion sends the WinRM
  terminate `Signal`, which actually stops the remote command; a command drained to its end cleans
  up on its own. Closing the readers does *not* close the process.
* `stdout()` and `stderr()` are fed by the same protocol loop: reading either channel (or calling
  `waitFor()`) advances it, and output arriving for the channel not being read is buffered until
  read — memory is bounded by the *unread* channel, not by the total output.
* Output is **decoded incrementally** with the request's charset; a multibyte character split
  across protocol chunks is decoded correctly.
* The process **holds the client's serial connection** until completion or close: other operations
  on the same client wait in the meantime.
* The timeout is an **inactivity** timeout — the longest silence tolerated from the server — not
  an overall deadline: a command may run (and stream) far longer than the timeout as long as it
  keeps producing output. Use `waitFor(Duration)` when you need a hard deadline. See
  [Timeouts and Errors](timeouts-and-errors.html).

### Tailing the output of a blocking execution

When you only want to *observe* the output live — logging, progress reporting — but still want the
blocking call and its complete [`CommandResult`](apidocs/org/metricshub/winrm/CommandResult.html),
register `onStdout(...)` / `onStderr(...)` callbacks and keep `execute()` as the terminal:

```java
CommandResult result = client.command("longRunningThing.exe")
    .onStdout(chunk -> log.info(chunk))
    .onStderr(chunk -> log.warn(chunk))
    .execute();
```

Each callback receives the output chunk by chunk as the server delivers it (not necessarily whole
lines), on an internal worker thread, never concurrently. The wall-clock timeout of `execute()`
applies unchanged.

## Character set

By default the output character set does not need to be specified: the client detects the remote
host's active code page (one WQL query, always in `ROOT\CIMV2`) before the first command runs, and
**caches the result for the lifetime of the client** — later commands pay nothing. Set an explicit
`charset(...)` to skip the detection entirely.

## Copying local files to the host

Pass one or more local files to `upload(...)` to have them copied to the remote host before the
command runs. Every reference to an uploaded file in the command line is rewritten to the path
where the file lands on the host:

```java
CommandResult result = client.command("CSCRIPT c:\\scripts\\collect.vbs")
    .upload(Path.of("c:\\scripts\\collect.vbs"))
    .execute();
```

copies `c:\scripts\collect.vbs` to the host and runs the equivalent of:

```text
CSCRIPT "C:\Windows\Temp\...\collect.1a2b3c4d5e6f.vbs"
```

The client can also copy a file to an explicit destination of your choice, independently of any
command:

```java
client.uploadFile(Path.of("collect.ps1"), "C:\\Windows\\Temp\\collect.ps1");
```

In short: files travel **through the WinRM command shell itself** (chunked base64, decoded with
`certutil`, digest-verified — no SMB, no TCP port 445, no administrative share), land under a
**content-addressed name** (e.g. `collect.1a2b3c4d5e6f.vbs`), and a file already present with an
identical digest is **not transferred again**. The mechanism is designed for small script files,
not bulk data.

See **[File Transfers](file-transfers.html)** for the full mechanics: the exact destination
directory, the temporary files, the integrity verification, the 30-day cleanup, and the
command-line substitution rules.

## Exceptions

`execute()` reports failures through the unchecked
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html)
hierarchy:

| Exception | When |
| --- | --- |
| [`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html) | The credentials were rejected. |
| [`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) | The remote service answered with a WSMan fault — the fault code and detail are available as fields. |
| [`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html) | The operation did not complete within its timeout. |
| [`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html) | Any other failure (connection, TLS, protocol, unreadable local file). |

See [Timeouts and Errors](timeouts-and-errors.html) for details.

## From the command line

The standalone jar runs a command with the `command` subcommand (aliases: `cmd`, `exec`, `run`).
Standard output and standard error are forwarded to the corresponding local streams, and the
process exits with the remote exit code when it fits in 0–255:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt --https \
  exec ipconfig /all
```
