keywords: command, execute, cmd, stdout, stderr, exit code, file copy, script
description: Execute remote commands with WinRMCommandExecutor, capture output and exit codes, and copy local files to the host.

# Remote Commands

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

The client can run an arbitrary command on the remote host and hand back its standard output,
standard error, and exit code. It can also copy local script files to the host first and rewrite the
command so it references them.

## `WinRMCommandExecutor.execute(...)`

Commands are run with the static method
[`WinRMCommandExecutor.execute(...)`](apidocs/org/metricshub/winrm/command/WinRMCommandExecutor.html),
which returns a
[`WindowsRemoteCommandResult`](apidocs/org/metricshub/winrm/WindowsRemoteCommandResult.html).

```java
import static java.util.Collections.singletonList;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;

import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.command.WinRMCommandExecutor;

WindowsRemoteCommandResult result = WinRMCommandExecutor.execute(
    "ipconfig /all",                 // command (mandatory)
    HTTPS,                           // protocol
    "server.example.com",            // hostname (mandatory)
    null,                            // port (null → 5985 for HTTP, 5986 for HTTPS)
    "DOMAIN\\Administrator",         // username (mandatory)
    "the-password".toCharArray(),    // password
    null,                            // working directory (nullable)
    30_000L,                         // timeout in milliseconds
    null,                            // local files to copy (nullable)
    null,                            // Kerberos ticket cache (null for NTLM)
    singletonList(NTLM)              // authentication schemes
);

System.out.println("exit code: " + result.getStatusCode());
System.out.print(result.getStdout());
System.err.print(result.getStderr());
```

### Parameters

| Parameter | Type | Notes |
| --- | --- | --- |
| `command` | `String` | The command line to run. **Mandatory.** |
| `protocol` | [`WinRMHttpProtocolEnum`](apidocs/org/metricshub/winrm/WinRMHttpProtocolEnum.html) | `HTTP` or `HTTPS`. `null` defaults to `HTTP`. |
| `hostname` | `String` | Host name or IP address. **Mandatory.** |
| `port` | `Integer` | `null` uses the protocol default. |
| `username` | `String` | `DOMAIN\user` or `user`. **Mandatory.** |
| `password` | `char[]` | The password. |
| `workingDirectory` | `String` | Working directory of the spawned process on the remote host. May be `null`. |
| `timeout` | `long` | Timeout in milliseconds. Must be **greater than zero**. |
| `localFileToCopyList` | `List<String>` | Local files to copy to the host before running (see below). May be `null`. |
| `ticketCache` | `java.nio.file.Path` | Kerberos ticket cache path. `null` for NTLM. |
| `authentications` | `List<`[`AuthenticationEnum`](apidocs/org/metricshub/winrm/service/client/auth/AuthenticationEnum.html)`>` | Requested schemes. `null` or empty means NTLM only. |

## The result

[`WindowsRemoteCommandResult`](apidocs/org/metricshub/winrm/WindowsRemoteCommandResult.html) is an
immutable value:

| Method | Returns | Description |
| --- | --- | --- |
| `getStdout()` | `String` | The command's standard output. |
| `getStderr()` | `String` | The command's standard error. |
| `getStatusCode()` | `int` | The process exit code. |
| `getExecutionTime()` | `float` | The measured execution time of the command. |

## Character set

The output character set does not need to be specified: the client detects the remote host's active
code page before the command runs and decodes standard output and standard error accordingly.

## Copying local files to the host

Pass one or more local paths in `localFileToCopyList` to have them copied to the remote host before
the command runs. Every reference to a listed file in the `command` string is rewritten to the path
where the file lands on the host — typically under `C:\Windows\Temp`. For example:

```java
WinRMCommandExecutor.execute(
    "CSCRIPT c:\\MyScript.vbs",
    /* protocol   */ HTTPS,
    /* hostname   */ "server.example.com",
    /* port       */ null,
    /* username   */ "DOMAIN\\Administrator",
    /* password   */ password,
    /* workingDir */ null,
    /* timeout    */ 30_000L,
    /* files      */ java.util.List.of("c:\\MyScript.vbs"),
    /* ticket     */ null,
    /* auth       */ singletonList(NTLM)
);
```

copies `c:\MyScript.vbs` to the host and runs the equivalent of:

```text
CSCRIPT "C:\Windows\Temp\...\MyScript.vbs"
```

The fluent API does the same with `upload(...)` on the command builder, and can also copy a file
to an explicit destination with `WinRMClient.uploadFile(localPath, remotePath)`.

In short: files travel **through the WinRM command shell itself** (chunked base64, decoded with
`certutil`, digest-verified — no SMB, no TCP port 445, no administrative share), land under a
**content-addressed name** (e.g. `MyScript.1a2b3c4d5e6f.vbs`), and a file already present with an
identical digest is **not transferred again**. The mechanism is designed for small script files,
not bulk data.

See **[File Transfers](file-transfers.html)** for the full mechanics: the exact destination
directory, the temporary files, the integrity verification, the 30-day cleanup, and the
command-line substitution rules.

## Exceptions

`execute(...)` declares:

| Exception | When |
| --- | --- |
| `java.io.IOException` | An I/O error, including a copied-file problem. |
| `java.util.concurrent.TimeoutException` | The operation did not complete within `timeout`. |
| [`WindowsRemoteException`](apidocs/org/metricshub/winrm/exceptions/WindowsRemoteException.html) | Any problem on the remote host (in practice a [`WinRMException`](apidocs/org/metricshub/winrm/exceptions/WinRMException.html)). |

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
