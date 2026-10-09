keywords: command, execute, cmd, powershell, encodedcommand, stdout, stderr, stdin, exit code, file copy, script
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
commands (and [WQL queries](wql.html)) over the same authenticated connection — see
[Client options](index.html#client-options).

### Command options

Everything between `command(...)` and `execute()` is optional (see
[`CommandRequest`](apidocs/org/metricshub/winrm/CommandRequest.html)):

| Option | Default | Meaning |
| --- | --- | --- |
| `timeout(Duration)` | the client's timeout | Wall-clock deadline covering file uploads and the command itself with `execute()`; inactivity timeout with `start()`. |
| `charset(Charset)` | `UTF-8` | The charset used to decode the command output (see below). |
| `workingDirectory(String)` | remote default | Working directory of the remote process. The remote shell is created by the client's **first** command (remote file operations and `uploadFile(...)` count too) and reused afterward, so this only takes effect on that first command, and never on a request with `upload(...)`. |
| `environment(String, String)` | none | Environment variable set in the remote shell, like `winrs -env` — call it once per variable, insertion order is preserved. Shell-scoped like `workingDirectory`: only takes effect on the client's **first** command. |
| `upload(Path...)` | none | Local files to copy to the host before running (see below). |
| `stdin(String)` / `stdin(Path)` / `stdin(InputStream)` | none | Standard input fed to the command — the remote equivalent of a `< file` redirection (see below). |
| `stdin()` | console semantics | Declare interactive input through `RemoteProcess.stdin()` (with `start()`): pipe semantics without pre-supplied content (see below). |
| `stdinCharset(Charset)` | the output charset | The charset used to *encode* standard input, when it differs from the output charset (see below). |
| `onStdout(Consumer<String>)` / `onStderr(Consumer<String>)` | none | Callbacks receiving each chunk of output live while `execute()` runs (see below). |

### Loading the user profile

By default the remote shell does **not** load the user profile, the equivalent of
`winrs -noprofile`. A command then sees the user's own profile only if something else already
loaded it on the host, such as an interactive or disconnected session. Otherwise the shell runs
with the default profile: `%USERPROFILE%` and the starting directory are `C:\Users\Default`,
`%APPDATA%` and `%LOCALAPPDATA%` are not set, `%TEMP%` is `C:\Windows\Temp`, and
`HKEY_CURRENT_USER` is not the user's registry hive. When a command needs them, build the client
with `loadUserProfile()`:

```java
try (WinRMClient client = WinRMClient.builder("server.example.com")
        .credentials("DOMAIN\\user", password)
        .loadUserProfile()
        .build()) {
    client.command("reg query HKCU\\Software\\Vendor").execute();
}
```

The profile is loaded when the remote shell is created, so this is a client setting. It applies
to every shell the client creates, for commands, file transfers and remote file operations alike,
including a shell recreated after the server reaped the previous one or the client replaced it
(see [Shell reuse](#shell-reuse)). Microsoft's `winrs`
documentation warns that loading the profile fails for a user who is not a local administrator on
the host: the command then fails with a
[`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) carrying
the fault code and detail. The CLI's `--profile` option does the same.

A command that reaches a further host (a UNC path, another server) fails with *access denied*
unless the client delegates your Kerberos credentials: see
[Credential delegation](authentication.html#credential-delegation).

### Shell reuse

The client runs its commands, file transfers and remote file operations in one remote command
shell, created by the first of them. But every command run in a shell holds one of the user's
WSMan operations until the shell is deleted, even after it completed, and the host caps them per
user, across all of that user's connections: `MaxConcurrentOperationsPerUser` is 15 on Windows
Server 2008 R2 and 1500 later (see [Host quotas](preparing-the-host.html#host-quotas-worth-knowing-about)).
So the client replaces its shell:

* every 10 commands. The builder's `maxCommandsPerShell(int)` changes that number: lower leaves
  more of the quota to the user's other connections, and 1 runs every command in a shell of its
  own, like `winrs`;
* on Windows Server 2008 R2, when the quota refuses a command in a shell that already ran
  commands: the command ran nothing, so it is retried once in a new shell. In a fresh shell, which
  holds nothing to release, the fault is reported. Later versions send the quota fault without its
  WSManFault code, so nothing reliable identifies it: there, replacing the shell every N commands
  is what keeps the client under the quota;
* when a command could not be terminated cleanly (its terminate `Signal` failed or was skipped).

A replacement costs a Delete and a Create (about 100 ms). The new shell gets the same working
directory, environment variables and profile, but deleting the old one ends any process a previous
command left running in it, as closing the client does. A process that must outlive its command
belongs outside the shell, e.g. in a scheduled task.

## Running PowerShell

`powerShell(...)` prepares a PowerShell script execution the same way `command(...)` prepares a
command line. The script travels base64-encoded
(`powershell.exe -NoProfile -NonInteractive -EncodedCommand …`; `-NoProfile` skips PowerShell's
profile scripts, not the Windows user profile of `loadUserProfile()`), so **no quoting or escaping
is ever needed**: quotes, pipes, newlines, and `$variables` reach PowerShell exactly as written.

```java
CommandResult result = client.powerShell(
        "Get-Service | Where-Object { $_.Status -eq 'Running' } | Select-Object -First 5 Name"
    ).execute();
```

It returns the same request object as `command(...)`: every option and terminal described on this
page — `timeout(...)`, `charset(...)`, `stdin(...)`, `onStdout(...)`, `execute()`, `start()` —
works unchanged.

Points to know:

* **Exit code** — `powershell.exe` exits with 0 on success and 1 when the script ends with a
  terminating error; call `exit <n>` in the script for a specific code.
* **Standard error** — `powershell.exe` may write progress records to stderr as CLIXML
  (`#< CLIXML` followed by `<Objs …>`), even when the script succeeds: judge success by the exit
  code, not by an empty `stderr()`.
* **Uploads** — `upload(...)` works as with any command: references to the uploaded files in the
  script are rewritten to the remote copies *before* the script is encoded.
* **Script size** — none to worry about. A script too long for the encoded command line (roughly
  3000 characters and up; the remote shell's limit is 8191) is transferred automatically as a
  temporary `.ps1` file, like `upload(...)`, and [content-addressed](file-transfers.html), so an
  identical re-run skips the transfer. Its content runs as a dot-sourced script block, so it
  behaves like the encoded form: no script path (`$PSScriptRoot` and
  `$MyInvocation.MyCommand.Path` stay empty), top-level `param(...)` works, and the execution
  policy does not apply; only `$MyInvocation.InvocationName` and `Line` differ. As with any
  upload, the transfer creates the remote shell, so `workingDirectory(...)` does not apply.
* **Windows PowerShell** — the script runs in `powershell.exe`, whose version depends on the host
  (5.1 on current Windows, down to 2.0 on Windows Server 2008 R2 and Windows 7): write for the
  oldest one you target. To target PowerShell 7+, invoke `pwsh` yourself with `command(...)`.

## The result

[`CommandResult`](apidocs/org/metricshub/winrm/CommandResult.html) is an immutable value:

| Method | Returns | Description |
| --- | --- | --- |
| `stdout()` | `String` | The command's standard output. |
| `stderr()` | `String` | The command's standard error. |
| `exitCode()` | `int` | The process exit code (Windows HRESULT codes reported as unsigned 32-bit values are narrowed to the equivalent signed `int`). |
| `elapsed()` | `java.time.Duration` | Wall-clock time of the operation. |

A nonzero exit code is not an exception: check `exitCode()`.

## Streaming the output

`execute()` collects the complete output in memory and returns only when the command has exited.
For long-running or verbose commands, end the same request with `start()` instead: it returns a
[`RemoteProcess`](apidocs/org/metricshub/winrm/RemoteProcess.html) — shaped like
`java.lang.Process` — whose output can be consumed **while the command is still running**:

```java
try (RemoteProcess process = client.command("wevtutil qe System /f:text").start()) {
    try (BufferedReader out = process.stdout()) {
        out.lines().forEach(System.out::println);
    }
    int exitCode = process.waitFor();       // blocks until the command exits
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
  on the same client wait in the meantime, so use a second client for any call made while
  consuming the process.
* The timeout is an **inactivity** timeout — the longest silence tolerated from the server — not
  an overall deadline: a command may run (and stream) far longer than the timeout as long as it
  keeps producing output. For a hard deadline, use `waitFor(Duration)`: it returns `true` once the
  command has completed (read the code with `exitCode()`), or `false` when the deadline passes
  first, with the command still running (`close()` stops it). See
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

## Standard input

Commands that read their standard input can be fed in two ways. Either way, input the command
does not read (it exits without reading it, or before the input arrives) is discarded without
error, and the command's output and exit code are reported as usual.

### Pre-supplied input

`stdin(...)` on the request delivers the whole input right after the command starts, ending with
the protocol's end-of-input mark so the remote stdin reaches EOF — the remote equivalent of a
local `< file` redirection. It works with both `execute()` and `start()`:

```java
CommandResult result = client.command("sort")
    .stdin(Path.of("data.txt"))     // also stdin(InputStream) and stdin(String)
    .execute();
```

`stdin(String)` is encoded with `stdinCharset(...)`, which defaults to `charset(...)`; the `Path`
and `InputStream` variants send the bytes exactly as stored. Large input is split into
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
  process **unconverted**. They are encoded with the request's input charset
  (`stdinCharset(...)`, else `charset(...)`; UTF-8 by default), which is what a program reading a
  UTF-8 stream expects, and `stdin(Path)`/`stdin(InputStream)` send the bytes verbatim.
* **Console semantics** (no `stdin` declaration — a command started with `start()` and written
  to through `RemoteProcess.stdin()`) — the WinRM service converts the bytes to console input
  itself, using a code page that depends on the Windows version. Prefer pipe semantics, or set
  `stdinCharset(...)` to match the session's console code page.

Output is unaffected either way: it follows the shell's console code page, 65001 (UTF-8) unless
the client is built with `consoleCodePage(...)`.

> A remote `cmd.exe` reading its **command lines** from standard input cannot handle non-ASCII
> at all under console code page 65001 — Windows decodes that input one byte at a time, turning
> every non-ASCII byte into `U+FFFD`, whatever the encoding used to send it. An interactive
> session must therefore run under a single-byte console code page: build the client with
> `consoleCodePage(...)` (the remote machine's ANSI page) and use the matching charset in both
> directions, as the CLI's `shell` subcommand does. Data piped to an ordinary program (`sort`,
> `findstr`, your own executable) is not affected: it never goes through cmd's parser, so the
> default code page 65001 and UTF-8 are right for it.

## Character encoding

The output character set never needs to be specified, unless the client is built with
`consoleCodePage(...)` (see [Input encoding](#input-encoding)). The remote command shell is
created with console code page **65001**, so its output is UTF-8 whatever the remote machine's
locale, and it is decoded as such — no detection query, no per-host configuration:

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

**Byte order mark.** PowerShell 2.0 (Windows Server 2008 R2, Windows 7) writes a UTF-8 byte
order mark ahead of its redirected output. When the output is decoded as UTF-8, a `U+FEFF` at the
very start of stdout and of stderr is dropped by every terminal, callback and reader (and by the
legacy `WinRMCommandExecutor`). A `U+FEFF` anywhere else is kept as data: PowerShell 2.0 emits it
mid-stream when a direct `[Console]::Out` or `[Console]::Error` write comes first. With any other
`charset(...)`, the bytes are decoded as they are. File reads (`client.file(...)`) return the
file's content untouched, byte order mark included.

> **Changed in 2.0.00** — earlier versions ran a `SELECT CodeSet FROM Win32_OperatingSystem` query
> before each command and decoded the output with the code page it reported. That property is
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
CSCRIPT C:\Windows\Temp\winrm-upload-MYHOST\collect.1a2b3c4d5e6f.vbs
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

`execute()`, `start()` and the `RemoteProcess` methods report failures through the unchecked
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html)
hierarchy:

| Exception | When |
| --- | --- |
| [`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html) | The credentials were rejected. |
| [`WinRMFaultException`](apidocs/org/metricshub/winrm/exceptions/WinRMFaultException.html) | The remote service answered with a WSMan fault — see `getFaultCode()` and `getFaultDetail()`. |
| [`WinRMTimeoutException`](apidocs/org/metricshub/winrm/exceptions/WinRMTimeoutException.html) | The operation did not complete within its timeout. |
| [`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html) | Any other failure (connection, TLS, protocol, unreadable local file). |

See [Timeouts and Errors](timeouts-and-errors.html) for details.

## From the command line

The standalone jar runs a command with its `command` subcommand, forwarding the output live and
propagating the exit code — see the [Command-Line Client](cli.html) manual.
