keywords: cli, command line, standalone, jar, wql, exec, exit codes, manual
description: Manual page of the winrm-java standalone command-line client - subcommands, options, passwords, Kerberos, streaming output, and exit codes.

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
java -jar winrm-java-standalone.jar --help | --version
```

## Subcommands

| Subcommand | Description |
| --- | --- |
| `wql <query>` | Run a WQL query and print the rows to stdout as UTF-8 [JSON Lines](https://jsonlines.org/). |
| `command <command line...>` | Run a command on the remote host, forwarding its output. `cmd`, `exec`, and `run` are aliases. |

Everything after the subcommand is the query or the command line; quoting follows your local
shell's rules, and multi-word command lines are reassembled for the remote `cmd.exe`.

## Options

| Option | Description |
| --- | --- |
| `-h, --hostname <host>` | Target hostname or IP address (required). |
| `-u, --username <user>` | User name, optionally `DOMAIN\user` (required). |
| `-p, --password <password>` | Password. Command-line arguments may be visible to other local processes: avoid in automation. |
| `-pf, --password-file <file>` | Read the password from a UTF-8 file (preferred for automation, see below). |
| `-P, --port <port>` | Target port. Default: 5985 for HTTP, 5986 for HTTPS. |
| `-t, --timeout <ms>` | Operation timeout in milliseconds. Default: 60000. See [Timeout semantics](#Timeout_semantics). |
| `--https` | Connect over HTTPS. |
| `--https-permissive` | Trust any HTTPS certificate and hostname. Intentionally insecure: testing and isolated hosts only. Requires `--https`. |
| `--ntlm` | Authenticate with NTLM (the default). |
| `--kerberos` | Authenticate with Kerberos. Requires `--https`. |
| `--kerberos-kdc <host>` | Set the Kerberos KDC for this invocation; the realm is inferred from its DNS suffix (see below). |
| `--kerberos-realm <realm>` | Override the realm inferred from `--kerberos-kdc`. |
| `--help` | Print the usage summary. |
| `--version` | Print the build version. |

`--ntlm` and `--kerberos` are mutually exclusive, as are the two password options.

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

See [Authentication](authentication.html) for how NTLM and Kerberos work on the wire.

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

## Timeout semantics

`-t`/`--timeout` follows the operation:

* For `wql`, it is the **inactivity timeout** of the stream — the longest tolerated silence
  between two server responses. A large result can stream for longer than the timeout, as long as
  the server keeps answering.
* For `command`, it is the **overall deadline** covering the command itself and any file
  uploads.

See [Timeouts and Errors](timeouts-and-errors.html) for the underlying semantics.

## Exit codes

| Exit code | Meaning |
| ---: | --- |
| `0` | Successful WQL query or remote command. |
| `0`–`255` | Remote command exit code, when it fits in that range. |
| `64` | Invalid CLI usage. |
| `69` | Connection, DNS, socket, or TLS failure. |
| `70` | WinRM protocol or other remote failure (including a remote exit code not representable in 0–255). |
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

Follow a long-running command live and capture the streamed WQL rows with `jq`:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.net -u 'DOMAIN\user' -pf password.txt \
  wql 'SELECT * FROM Win32_NTLogEvent' | jq -r .Message
```
