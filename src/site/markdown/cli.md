keywords: cli, command line, standalone, jar, wql, exec, shell, interactive, stdin, ls, stat, cat, get, remote files, exit codes, manual
description: Manual page of the winrm-java standalone command-line client - subcommands, options, passwords, authentication schemes (NTLM, Kerberos, Basic), streaming output, the interactive shell, remote files (ls, stat, cat, get), and exit codes.

# Command-Line Client

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Every release ships a self-contained executable jar that bundles the client and a small CLI:
download `${project.artifactId}-${project.version}-standalone.jar` from the
[latest release](https://github.com/metricshub/winrm-java/releases/latest) and run it with Java.
This page is its manual.

## Synopsis

```text
java -jar winrm-java-standalone.jar [options] wql <query>
java -jar winrm-java-standalone.jar [options] command|cmd|exec|run <command line...>
java -jar winrm-java-standalone.jar [options] shell
java -jar winrm-java-standalone.jar [options] ls <directory> [ls options]
java -jar winrm-java-standalone.jar [options] stat <path> [--json]
java -jar winrm-java-standalone.jar [options] cat <file> [cat options]
java -jar winrm-java-standalone.jar [options] get <file> [<local path>]
java -jar winrm-java-standalone.jar --help | --version
```

## Subcommands

| Subcommand | Description |
| --- | --- |
| `wql <query>` | Run a WQL query and print the rows to stdout as UTF-8 [JSON Lines](https://jsonlines.org/). |
| `command <command line...>` | Run a command on the remote host, forwarding its output. `cmd`, `exec`, and `run` are aliases. |
| `shell` | Open an interactive `cmd.exe` session on the remote host (see [Interactive shell](#interactive-shell)). |
| `ls <directory>` | List a remote directory, or a tree, one entry per line (see [Remote files](#remote-files)). |
| `stat <path>` | Print the properties of a remote file or directory. |
| `cat <file>` | Copy the bytes of a remote file, or of a byte range, to stdout. |
| `get <file> [<local path>]` | Download a remote file to a local file, digest-verified. |

For `wql` and `command`, everything after the subcommand is the query or the command line;
quoting follows your local shell's rules, and multi-word command lines are reassembled for the
remote `cmd.exe`. `shell` takes no argument. The file subcommands take a remote path, then their
own options, in any order.

## Options

| Option | Description |
| --- | --- |
| `-h, --hostname <host>` | Target hostname or IP address (required). |
| `-u, --username <user>` | User name, optionally `DOMAIN\user` (required). |
| `-p, --password <password>` | Password. Command-line arguments may be visible to other local processes: avoid in automation. |
| `-pf, --password-file <file>` | Read the password from a UTF-8 file (preferred for automation, see below). |
| `-P, --port <port>` | Target port. Default: 5985 for HTTP, 5986 for HTTPS. |
| `-t, --timeout <ms>` | Operation timeout in milliseconds. Default: 60000. See [Timeout semantics](#timeout-semantics). |
| `-d, --directory <path>` | Working directory the remote command or interactive shell starts in, like `winrs -d` (only with `command` and `shell`). Default: the remote user's profile directory. |
| `--env <NAME=VALUE>` | Environment variable set in the remote shell, like `winrs -env` (only with `command` and `shell`). Repeatable — one occurrence per variable; the value is split on the first `=`, so it may itself contain `=`. |
| `-i, --stdin` | Forward the local standard input to the remote command (only with `command`); see below. |
| `--https` | Connect over HTTPS. |
| `--https-permissive` | Trust any HTTPS certificate and hostname. Intentionally insecure: testing and isolated hosts only. Requires `--https`. |
| `--ntlm` | Authenticate with NTLM (the default). |
| `--kerberos` | Authenticate with Kerberos. Requires `--https`. |
| `--basic` | Authenticate with HTTP Basic. Use with `--https` so the credential is not sent in the clear. |
| `--kerberos-kdc <host>` | Set the Kerberos KDC for this invocation; the realm is inferred from its DNS suffix (see below). |
| `--kerberos-realm <realm>` | Override the realm inferred from `--kerberos-kdc`. |
| `--allow-delegate` | Let the remote command use your Kerberos credentials to reach a further host (a UNC path, another server), like `winrs -allowdelegate`. Requires `--kerberos` and a forwardable ticket (see [Kerberos](#kerberos)). |
| `--help` | Print the usage summary. |
| `--version` | Print the build version. |

`--ntlm`, `--kerberos`, and `--basic` are mutually exclusive, as are the two password options.

### File options

The options of `ls`, `stat` and `cat` come **after** the subcommand, before or after the path:

| Option | Description |
| --- | --- |
| `--glob <pattern>` | `ls`: only the entries whose name matches a Windows wildcard pattern: `*` any sequence, `?` one character, case-insensitive, whole name (`*.log` matches `app.log`, not `app.log.1`). |
| `--recursive` | `ls`: list the whole tree below the directory, depth-first. |
| `--depth <n>` | `ls`: list the tree down to depth `n` (1 is the directory's own entries); implies `--recursive`. |
| `--files-only` | `ls`: files only. |
| `--directories-only` | `ls`: directories only. Mutually exclusive with `--files-only`. |
| `--modified-after <date>` | `ls`: only the entries last modified after an ISO-8601 date (`2026-01-31`, midnight UTC) or date-time with an offset (`2026-01-31T12:00:00Z`, `2026-01-31T14:00:00+02:00`). |
| `--min-size <bytes>` | `ls`: only the files of at least this size (directories are not filtered by size). |
| `--json` | `ls`, `stat`: print UTF-8 [JSON Lines](https://jsonlines.org/) instead of text. |
| `--offset <bytes>` | `cat`: start at this byte; a negative offset counts from the end of the file. |
| `--length <bytes>` | `cat`: read at most this many bytes. |
| `--charset <name>` | `cat`: decode the file with this charset (`UTF-8`, `UTF-16LE`, `windows-1252`...) and print the text, instead of copying the bytes. |

## Passwords

If neither `-p` nor `-pf` is supplied, the CLI securely requests the password from the interactive
console without echoing it. Non-interactive runs must use `--password-file` (or, less securely,
`--password`).

Password files are decoded as UTF-8. Exactly one final LF, CRLF, or CR is removed; every other
byte — including whitespace and earlier line endings — is part of the password.

## Kerberos

By default, Kerberos uses the ambient JDK configuration (`krb5.conf` /
`-Djava.security.krb5.*`). The CLI can instead configure the JDK for the current invocation with
`--kerberos-kdc <host>`. If no `--kerberos-realm` is supplied, the realm is inferred by removing
the KDC hostname's first DNS label and uppercasing the remaining suffix — for example, a KDC of
`camus.internal.example.net` infers the realm `INTERNAL.EXAMPLE.NET`. The inference follows a
common Active Directory DNS naming convention; it is not guaranteed by Kerberos, so specify
`--kerberos-realm` when the realm does not match the KDC's DNS suffix or when the KDC is not a
fully qualified DNS name. Both options are valid only with `--kerberos`, and `--kerberos-realm`
requires `--kerberos-kdc`.

`--allow-delegate` forwards your Kerberos ticket to the host, so the remote command can reach a
further host as you — a UNC path, another server — where it would otherwise get *access denied*.
The ticket must be forwardable, which the JDK asks for only when a `krb5.conf` says
`forwardable = true` in its `[libdefaults]` section (`--kerberos-kdc` does not): otherwise the CLI
exits with an authentication error (77) saying so. Only delegate to hosts you trust; see
[Credential delegation](authentication.html#credential-delegation) for the details.

## Basic

`--basic` authenticates with HTTP Basic, sending the credential in the `Authorization` header of
every request. It has no message protection, so the credential and payload are plaintext over HTTP —
combine it with `--https` (and `--https-permissive` for self-signed hosts) so TLS protects them.
On Windows, use a **bare local account name** with `-u`: the WinRM service rejects Basic for
domain accounts and for any `DOMAIN\`-qualified name.

See [Authentication](authentication.html) for how NTLM, Kerberos, and Basic work on the wire.

## Output

Diagnostics go **only to standard error**, so standard output can always be piped or parsed.

### `wql`

Each result row is printed as one compact UTF-8 JSON object per line
([JSON Lines](https://jsonlines.org/)); property order follows the WinRM response. The rows are
**streamed**: each one is written and flushed as it arrives from the host, so a downstream pipe
starts working immediately and memory stays bounded regardless of the result size. A mid-stream
failure can therefore leave partial output on standard output, signalled by the nonzero exit code.

### `command`

Remote stdout and stderr are forwarded **live** to the corresponding local streams while the
command runs — each chunk is flushed as it arrives, so a long-running command can be followed in
real time. The output is decoded as UTF-8: the remote shell is created with console code page
65001, so no code-page detection is needed and non-ASCII output survives whatever the remote
locale. See [Character encoding](commands.html#character-encoding) for the two legacy tools that
ignore the console code page.

When the local standard input is piped or redirected, it is forwarded as the remote command's
standard input, with pipe semantics, so filters just work:

```bash
java -jar winrm-java-standalone.jar -h server -u 'DOMAIN\user' -pf pw.txt command sort < data.txt
```

Forwarding engages automatically when the standard input is detectably redirected: it is a
seekable file (any `< file` redirection, including an empty file or the null device), or bytes
are already waiting on it at startup (a normal pipe). An interactive terminal is never consumed,
even when only the *output* is redirected (`... command hostname > result.txt`). The one
undetectable case is a pipe whose producer has written nothing by the time the CLI starts: pass
`-i`/`--stdin` to force forwarding there. The input is delivered in full before the output is
read: piping a large input into a command that floods its output at the same time can deadlock
both sides (the classic pipe deadlock), exactly as with `java.lang.Process`.

### `ls`, `stat`, `cat`, `get`

See [Remote files](#remote-files).

## Interactive shell

```bash
java -jar winrm-java-standalone.jar -h server.example.net -u 'DOMAIN\user' -pf pw.txt shell
```

`shell` starts `cmd.exe` on the remote host and bridges it to the local terminal until the remote
shell exits (type `exit`, or send end-of-input — Ctrl+Z then Enter on Windows, Ctrl+D elsewhere —
which the session turns into an `exit`). The remote exit code is propagated through the usual
[exit-code contract](#exit-codes).

* **Echo is off** — the remote shell runs `cmd.exe /Q`, so the input you forward is never
  repeated back: your terminal already shows what you type, and the output stream carries the
  prompts and the command output only.
* **The session runs under a single-byte code page**, the remote machine's ANSI one (queried
  once per session from `Win32_OperatingSystem.CodeSet`, falling back to 1252 when the host
  cannot answer); both what you type and what you see use it. This is deliberate: a remote
  `cmd.exe` decodes the command lines it reads from its standard input **one byte at a time**
  under code page 65001, so every non-ASCII character would be lost. `winrs` has the same
  constraint. The practical limit is that characters outside the host's ANSI code page cannot be
  typed or displayed in an interactive session — `é` on a Western-European host is fine.
  The other subcommands are unaffected: `wql` and `command` keep code page 65001 and full UTF-8
  output, and piped input to `command` transfers bytes unconverted.
* **Line-oriented, like `winrs`** — input is line-buffered by the local terminal and forwarded
  when you press Enter. There is no raw-terminal/PTY mode (with zero dependencies there is none in
  pure Java): full-screen programs, cmd.exe line editing, tab completion, and ANSI cursor control
  are not supported. Command output echoes back with sub-second latency; a line typed while the
  session is idle can wait up to the poll cadence (about one second — the WSMan protocol's floor
  for a bounded poll) before it is forwarded.
* **Ctrl+C interrupts the remote command, not the session** — it is forwarded as the WSMan
  `ctrl_c` signal, which stops the running remote child (like a console Ctrl+C) and returns to the
  remote prompt. On a Java runtime without `sun.misc.Signal`, Ctrl+C keeps its default behavior
  and ends the CLI (terminating the remote shell with it).
* **An idle session does not time out** — `-t`/`--timeout` bounds each protocol round trip, and
  every round trip of an idle session completes with the protocol's "nothing yet" answer. Only a
  server that stops answering altogether trips the timeout. A `--timeout` below 1000 ms is
  rejected for `shell`: one poll round trip cannot complete faster (the WSMan service holds a
  bounded request for at least 500 ms before answering "nothing yet").

## Remote files

`ls`, `stat`, `cat`, and `get` reach the remote file system through the WinRM connection itself —
no SMB, no share, no extra port — with the library's [remote file access](files.html): a small
PowerShell script does the work on the host, which needs PowerShell 2.0 or later in
`FullLanguage` mode. Nothing is written on the host.

```bash
# List: long format, machine-readable timestamps and sizes
java -jar winrm-java-standalone.jar -h server -u 'DOMAIN\user' -pf pw.txt \
  ls 'C:\inetpub\logs' --glob '*.log' --recursive --depth 3

# One path's properties
java -jar winrm-java-standalone.jar ... stat 'C:\Windows\Temp\collect.log'

# Content to stdout
java -jar winrm-java-standalone.jar ... cat 'C:\Windows\Temp\collect.log'
java -jar winrm-java-standalone.jar ... cat 'D:\logs\huge.log' --offset -8192   # the last 8 KiB
java -jar winrm-java-standalone.jar ... cat 'D:\logs\huge.log' --offset 1073741824 --length 65536
java -jar winrm-java-standalone.jar ... cat 'C:\legacy\report.txt' --charset windows-1252

# Whole file to a local file, digest-verified
java -jar winrm-java-standalone.jar ... get 'C:\Windows\Temp\collect.log' ./collect.log
```

### `ls`

`ls` lists the entries of a directory — or, with `--recursive` or `--depth`, the whole tree,
depth-first — one line per entry, **as the host walks the tree**: each line is written and
flushed as it arrives, so a pipe starts working immediately and memory stays bounded. The format
is fixed and independent of the locale:

```text
d-----            0 2026-01-02T03:04:05.6789012Z C:\inetpub\logs\LogFiles
-a----      1048576 2026-01-02T03:04:05.6789012Z C:\inetpub\logs\LogFiles\u_ex260101.log
```

1. The mode, like the `Mode` column of Windows PowerShell: `d` directory, `a` archive, `r`
   read-only, `h` hidden, `s` system, `l` reparse point (a junction or a symbolic link), `-`
   otherwise.
2. The size in bytes (0 for a directory), right-aligned on 12 characters.
3. The last modification time: ISO-8601 UTC with the 100 ns precision of Windows file times,
   always 28 characters, so it sorts as a string.
4. The full path, as the host reports it: the rest of the line.

Every filter is evaluated on the host, so only the matching entries travel. The filters select
what is *reported*, not where the walk goes: with `--recursive`, every subdirectory is traversed,
whatever the glob. Reparse points are listed, never descended into, so a junction looping back to
its parent cannot make the walk run forever.

A subdirectory that cannot be read (typically, access denied) does not stop the walk: its path is
reported on stderr (`winrm-java: cannot read directory C:\...`), the rest of the tree is listed,
and `ls` then exits with `1`. The directory named on the command line must be readable: otherwise
`ls` fails. An administrator's WinRM session holds the backup privilege, so directory permissions
mostly stop other accounts.

With `--json`, each entry is one UTF-8 JSON object per line
([JSON Lines](https://jsonlines.org/)):

```json
{"path":"C:\\inetpub\\logs\\LogFiles\\u_ex260101.log","mode":"-a----","attributes":32,"size":1048576,"lastModified":"2026-01-02T03:04:05.6789012Z","created":"2025-12-01T08:00:00.0000000Z","lastAccessed":"2026-01-02T03:04:05.6789012Z"}
```

`attributes` is the raw Windows `FileAttributes` value, for the flags the mode leaves out (e.g.
`2048` compressed, `16384` encrypted). `attributes` and `size` are numbers; the timestamps have
the format above, so they compare correctly as strings — `jq 'select(.lastModified >
"2026-01-01")'`. The text output encodes the paths for the local console, which may not
represent every character; `--json` is always UTF-8.

### `stat`

`stat` prints the properties of one file or directory, one `name: value` per line, with the
fields and formats of `ls --json` (which `stat` also accepts as `--json`):

```text
path: C:\Windows\Temp\collect.log
mode: -a----
attributes: 32
size: 1048576
lastModified: 2026-01-02T03:04:05.6789012Z
created: 2025-12-01T08:00:00.0000000Z
lastAccessed: 2026-01-02T03:04:05.6789012Z
```

A path that does not exist exits with `66`, any other failure (access denied, for example) with
`70`: `stat` doubles as an existence test.

### `cat`

`cat` writes the **bytes** of the file to stdout as they arrive — no charset conversion, no
newline translation — so binary content and redirection both work: `... cat 'C:\x.bin' > x.bin`
produces a byte-exact copy in POSIX shells and in cmd.exe. **Not in Windows PowerShell 5.1**,
whose `>` decodes a native program's output as text and writes it back re-encoded: use `get`
there, or run the redirection in cmd.exe. PowerShell 7.4 and later keep the bytes.

* `--offset <n>` starts at byte `n`. A negative offset counts from the end: `--offset -8192` reads
  the last 8 KiB, the size being read by the same remote invocation that seeks, so a growing log
  is tailed from its current end. The seek happens on the host: the end of a huge log costs the
  same as its start. An offset past the end reads nothing.
* `--length <n>` reads at most `n` bytes, from the offset or from the start of the file.
* `--charset <name>` decodes the file with that charset and prints the text in the local
  console's encoding, instead of the bytes: the way to read a file whose encoding is not the
  local one. A range boundary can split a multibyte character, printed as `U+FFFD`.

When the output is closed early — `... cat 'D:\logs\huge.log' | head` — `cat` stops the remote
read instead of transferring the rest of the file for nobody, and exits with `74`. The transfer
runs at about 1.5 MB/s (see [Read performance](files.html#read-performance)): logs and
configuration files, not bulk data.

### `get`

`get` downloads the whole file to a local file:
[digest-verified, atomic, and skipped when the local copy is already identical](file-transfers.html#downloading-a-file).
Without a local path, the file is written in the current directory under its remote name; an
existing directory receives it under its remote name too. Nothing is printed on success.
`--timeout` is the deadline of the whole download: at about 1.5 MB/s, the default 60 seconds
covers files up to about 80 MB — raise it for larger ones.

### Quoting remote paths

Remote paths reach the host untouched (the client quotes them for PowerShell itself), but the
local shell parses them first:

* **POSIX shells** (bash, zsh, Git Bash): single-quote Windows paths —
  `'C:\path with spaces\x.log'`, `'\\server\share\x.log'`. Inside double quotes, `\\` becomes `\`
  and `\"` is a literal quote: `"\\server\share"` loses a backslash, and `"C:\logs\"` does not end
  where it seems.
* **cmd.exe and Windows PowerShell 5.1**: a backslash just before a closing double quote escapes
  the quote — `"C:\my logs\"` arrives as `C:\my logs"`, merged with the arguments that follow.
  This happens with PowerShell's single quotes too (`'C:\my logs\'`), which it turns into double
  quotes for a path with spaces. Leave the trailing backslash out (`"C:\my logs"`), or double it
  (`"C:\my logs\\"`).

## Timeout semantics

`-t`/`--timeout` follows the operation:

* For `wql`, `ls`, and `cat`, it is the **inactivity timeout** of the stream — the longest
  tolerated silence between two server responses. A large result or file can stream for longer
  than the timeout, as long as the server keeps answering; a walking `ls` signals it is alive
  every second.
* For `command`, `stat`, and `get`, it is the **overall deadline** covering the whole operation
  (for `command`, the command itself and any file uploads).
* For `shell`, it bounds **each protocol round trip**; an idle interactive session never trips it
  (see [Interactive shell](#interactive-shell)).

See [Timeouts and Errors](timeouts-and-errors.html) for the underlying semantics.

## Exit codes

| Exit code | Meaning |
| ---: | --- |
| `0` | Success. |
| `0`–`255` | Remote command exit code (`command`, `shell`), when it fits in that range. |
| `1` | `ls`: some directories could not be read (reported on stderr); the rest of the tree was listed. |
| `64` | Invalid CLI usage. |
| `66` | Remote path not found (`ls`, `stat`, `cat`, `get`). |
| `69` | Connection, DNS, socket, or TLS failure. |
| `70` | WinRM protocol or other remote failure (including access denied to a remote path, and a remote exit code not representable in 0–255). |
| `74` | Local I/O failure: stdout closed or not writable, or a file-system error on the local file of `get` (access denied, a missing drive). |
| `77` | Authentication failure. |
| `124` | Operation timeout. |

## Examples

Run a WQL query over NTLM and HTTP, reading the password from a file:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  --hostname server.example.net --username 'DOMAIN\user' \
  --password-file password.txt --ntlm \
  wql 'SELECT Name,State FROM Win32_Service'
```

Run a remote command over HTTPS:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.net -u Administrator -pf password.txt --https \
  exec ipconfig /all
```

Kerberos with an explicit KDC (realm inferred as `INTERNAL.EXAMPLE.NET`):

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.internal.example.net -u 'DOMAIN\user' -pf password.txt \
  --https --kerberos --kerberos-kdc camus.internal.example.net \
  command whoami
```

List a share on a third machine from the remote host, with Kerberos credential delegation (the
`krb5.conf` says `forwardable = true`):

```bash
java -Djava.security.krb5.conf=krb5.conf -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.internal.example.net -u 'DOMAIN\user' -pf password.txt \
  --https --kerberos --allow-delegate \
  exec dir '\\fileserver\share'
```

Follow a long-running command live and capture the streamed WQL rows with `jq`:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.net -u 'DOMAIN\user' -pf password.txt \
  wql 'SELECT * FROM Win32_NTLogEvent' | jq -r .Message
```
