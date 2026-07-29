keywords: command, execute, cmd, stdout, stderr, stdin, exit code, file copy, script
description: Execute remote commands with the fluent WinRMClient API, capture output and exit codes, feed standard input, and copy local files to the host.

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
| `timeout(Duration)` | the client's timeout | Wall-clock deadline covering file uploads and the command itself with `execute()`; inactivity timeout with `start()`. |
| `charset(Charset)` | `UTF-8` | The charset used to decode the command output (see below). |
| `workingDirectory(String)` | remote default | Working directory of the remote process. The remote shell is created by the client's **first** command and reused afterward, so this only takes effect on that first command. |
| `upload(Path...)` | none | Local files to copy to the host before running (see below). |
| `stdin(String)` / `stdin(Path)` / `stdin(InputStream)` | none | Standard input fed to the command — the remote equivalent of a `< file` redirection (see below). |
| `stdin()` | console semantics | Declare interactive input through `RemoteProcess.stdin()` (with `start()`): pipe semantics without pre-supplied content (see below). |
| `stdinCharset(Charset)` | the output charset | The charset used to *encode* standard input, when it differs from the output charset (see below). |
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

## Standard input

Commands that read their standard input can be fed in two ways.

### Pre-supplied input

`stdin(...)` on the request delivers the whole input right after the command starts, ending with
the protocol's end-of-input mark so the remote stdin reaches EOF — the remote equivalent of a
local `< file` redirection. It works with both `execute()` and `start()`:

```java
CommandResult result = client.command("sort")
    .stdin(Path.of("data.txt"))     // also stdin(InputStream) and stdin(String)
    .execute();
```

`stdin(String)` is encoded with the request's charset (see `charset(...)`); the `Path` and
`InputStream` variants send the bytes exactly as stored. Large input is split into
protocol-sized chunks automatically.

Supplying input switches the remote stdin to **pipe semantics**
(`WINRS_CONSOLEMODE_STDIN=FALSE`): filters like `sort`, `findstr`, or `more` consume it and
terminate on EOF, exactly as with a local redirection. Without it, the historical console
semantics are kept.

### Interactive input

For a request started with `start()`, `RemoteProcess.stdin()` completes the `java.lang.Process`
shape: written text is buffered locally, `flush()` carries it to the host (one WSMan `Send`),
and `close()` marks the end of input. Declare the interactive input with the no-argument
`stdin()` on the request — it switches the remote stdin to pipe semantics, so closing the writer
actually delivers EOF:

```java
try (RemoteProcess p = client.command("some-repl.exe").stdin().start()) {
    try (BufferedWriter in = p.stdin()) {
        in.write("first request\n");
        in.flush();                              // delivers the buffered text
        System.out.println(p.stdout().readLine());
    }                                            // close() = end of input (EOF)
    p.waitFor();
}
```

Without the declaration, the remote stdin keeps the historical console semantics: writes are
still delivered, but a filter waiting for its input to *end* (`sort`, `findstr`, `more`) never
sees the EOF. Text is encoded statefully across flushes — a charset mark is emitted once and a
surrogate pair split by a flush is completed by the next write, exactly as one whole-string
encode.

Writes and reads alternate on the caller's thread, exactly like `java.lang.Process` pipes —
including the classic deadlock, which is the caller's to avoid: blocking on a read while the
remote command itself is blocked waiting for input (or feeding a large input to a command that
floods its output in the meantime) hangs both sides until the inactivity timeout fires.

`RemoteProcess` also exposes `interrupt()` — the WSMan `ctrl_c` Signal, the remote equivalent of
a console Ctrl+C: it interrupts the command's child process without terminating the command or
the process handle.

### Input encoding

Input encoding is not symmetric with output encoding, because Windows treats the two directions
differently:

* **Pipe semantics** (any `stdin(...)`, including the no-argument form) — the bytes reach the
  process **unconverted**. They are encoded with the request's charset (UTF-8 by default), which
  is what a program reading a UTF-8 stream expects, and `stdin(Path)`/`stdin(InputStream)` send
  the bytes verbatim.
* **Console semantics** (no `stdin` declaration — a command started with `start()` and written
  to through `RemoteProcess.stdin()`) — the WinRM service converts the bytes to console input
  itself, using a code page that depends on the Windows version. Prefer pipe semantics, or set
  `stdinCharset(...)` to match the session's console code page.

Output is unaffected either way: it follows the shell's console code page, which this client
pins to UTF-8.

> A remote `cmd.exe` reading its **command lines** from standard input cannot handle non-ASCII
> at all under console code page 65001 — Windows decodes that input one byte at a time, turning
> every non-ASCII byte into `U+FFFD`, whatever the encoding used to send it. An interactive
> session must therefore run under a single-byte console code page: build the client with
> `consoleCodePage(...)` (the remote machine's ANSI page) and use the matching charset in both
> directions, as the CLI's `shell` subcommand does. Data piped to an ordinary program (`sort`,
> `findstr`, your own executable) is not affected: it never goes through cmd's parser, so the
> default code page 65001 and UTF-8 are right for it.

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

## Character encoding

The output character set never needs to be specified. The remote command shell is created with
console code page **65001**, so its output is UTF-8 whatever the remote machine's locale, and it is
decoded as such — no detection query, no per-host configuration:

```java
// On a French Windows host:
client.command("vol").execute().stdout();   // " Le numéro de série du volume est …"
```

A handful of legacy console tools — `net.exe` and `chcp.com` are the known ones — ignore the
console code page and write their text pre-converted to the machine's **OEM** code page. Their
accented characters are not valid UTF-8 and arrive as `U+FFFD`; decode those commands with the
matching OEM charset instead:

```java
client.command("net user Administrateur")
    .charset(Charset.forName("IBM850"))     // French/Western European OEM code page
    .execute();
```

`charset(...)` is also what you want for a command that repoints the console itself (a leading
`chcp`) or writes raw bytes in a known encoding to its standard output. WQL results are unaffected
by all of this: they travel as UTF-8 inside the SOAP envelope.

> **Changed in 2.0.00** — earlier versions ran a `SELECT CodeSet FROM Win32_OperatingSystem` query
> before the first command and decoded the output with the code page it reported. That property is
> the remote machine's *ANSI* code page, which never matched what the shell emitted, so non-ASCII
> command output was mangled on every non-English host.

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

The standalone jar runs a command with its `command` subcommand, forwarding the output live and
propagating the exit code — see the [Command-Line Client](cli.html) manual.
