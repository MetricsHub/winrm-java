keywords: file transfer, file copy, upload, download, certutil, base64, digest, content-addressed, temporary files, atomic
description: How the WinRM Java Client copies files to and from the remote host through the WinRM channel itself — destination paths, temporary files, integrity verification, atomic downloads, and command-line substitution.

# File Transfers

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

The client can copy local files to the remote host, and remote files back, **through the WinRM
connection itself** — no SMB, no TCP port 445, no administrative share — so it works from any
client OS and needs no port beyond the WinRM one. This page explains exactly how uploads work:
where files land, which temporary files are created, how integrity is guaranteed, and how the
command line is rewritten. [Downloading a file](#downloading-a-file) covers the other direction.

## Uploading a file

**Transfer-and-run** — copy script files and rewrite the command to reference the remote copies
(this is what `upload(...)` on the fluent command builder and `localFileToCopyList` in the legacy
[`WinRMCommandExecutor.execute(...)`](apidocs/org/metricshub/winrm/command/WinRMCommandExecutor.html)
do):

```java
client.command("CSCRIPT c:\\scripts\\collect.vbs")
      .upload(Path.of("c:\\scripts\\collect.vbs"))
      .execute();
```

**Explicit destination** — copy one file to a path you choose
([`WinRMClient.uploadFile(...)`](apidocs/org/metricshub/winrm/WinRMClient.html), or
[`ShellFileCopy.copyLocalFileToRemoteFile(...)`](apidocs/org/metricshub/winrm/ShellFileCopy.html)
with a legacy executor):

```java
client.uploadFile(Path.of("collect.ps1"), "C:\\Windows\\Temp\\collect.ps1");
```

Both use the same transfer engine described below; transfer-and-run adds the per-client transfer
directory (with its housekeeping) and the command-line rewriting.

## Where files land

With **transfer-and-run**, files are copied to a per-client-machine transfer directory on the
remote host:

```text
<windir>\Temp\winrm-upload-<CLIENT-COMPUTER-NAME>
```

* `<windir>` is discovered on the remote host with the WQL query
  `SELECT WindowsDirectory FROM Win32_OperatingSystem` — typically `C:\Windows`.
* `<CLIENT-COMPUTER-NAME>` is the name of the machine **running the client** (the `COMPUTERNAME`
  environment variable, or the local host name), which usually gives each client machine its own
  directory. Clients that report the same computer name (cloned machines, containers) share one
  directory, safely: the content-addressed names below keep their payloads apart.
  Versions before 2.0.00 used `<windir>\Temp\SEN_ShareFor_<CLIENT-COMPUTER-NAME>$`, exposed as a
  hidden SMB share — no share is created anymore, and a directory left behind by an older
  version is neither reused nor cleaned up: it can simply be deleted.
* The directory is created if missing (`IF NOT EXIST ... MKDIR ...`).

Inside that directory the remote file name is **content-addressed**: a 12-hex-digit fragment of
the file's SHA-256 digest is inserted before the extension:

```text
collect.vbs  →  <windir>\Temp\winrm-upload-MYHOST\collect.1a2b3c4d5e6f.vbs
```

Because the name identifies the content, two files with the same name but different content get
different remote paths — concurrent clients (even ones whose computer names collide) can never
overwrite each other's payload between verification and execution. The flip side: a script that
inspects its own file name (e.g. `WScript.ScriptName`) sees the digest fragment.

Overlong names are truncated (on Unicode code-point boundaries) so that the complete remote path —
including the temporary-file suffixes described below — stays within the traditional Windows
`MAX_PATH` limit (260 characters) that old hosts still enforce; the digest fragment keeps
truncated names unique.

With an **explicit destination** (`uploadFile`), the file lands exactly at the path you give —
no content-addressing, no renaming. The destination must be an absolute Windows path, either
drive-rooted (`C:\...`) or UNC (`\\server\share\...`), of at most 229 characters (room for the
temporary-file suffixes within `MAX_PATH`); relative and drive-relative (`C:x.ps1`) paths are
rejected, because they would resolve against the remote shell's current directory. The
destination directory is created if missing. A UNC destination is a second hop from the host:
without Kerberos [credential delegation](authentication.html#credential-delegation) it typically
fails with access denied.

## How the bytes travel

The transfer rides the already-authenticated (and, over HTTPS or with NTLM, encrypted) WinRM
command shell — every step below is an ordinary remote command:

1. **Skip check.** The destination is hashed on the host
   (`certutil -hashfile <dest> SHA256`, with a `SHA1` fallback for pre-2012 hosts in the same
   command). If it already carries the digest of the local file, the **upload is skipped
   entirely** — re-running an identical script costs only the fixed bookkeeping (the
   directory-discovery query, the cleanup/`MKDIR` leg, and this digest probe), never the upload
   legs. A destination present with a *different* digest (e.g. corrupted in place) is remembered
   and repaired by replacement in step 4.
2. **Upload.** The file content is base64-encoded locally (76-character lines) and appended to a
   remote **base64 sidecar file** with chunked `echo` commands, batched into as few command legs
   as possible, each under cmd.exe's command-line length limit (~8&nbsp;kB per leg).
3. **Decode and verify.** One command leg decodes the sidecar with `certutil -f -decode` into the
   **staging file**, deletes the sidecar, and hashes the staging file — the digest is compared
   against the locally computed one before going any further.
4. **Publish and verify again.** The verified staging file is moved onto the destination:
   * if the destination did not exist, `MOVE` — and if a concurrent transfer of the *same
     content* won the race, the staging copy is simply discarded (a destination that already
     carries the right digest is **never rewritten**, so a copy verified by another operation
     cannot be invalidated);
   * if the destination pre-existed with a mismatched digest, `MOVE /Y` force-replaces it
     (repair).

   The same command leg hashes the destination one last time: **the operation only succeeds if
   the destination provably contains the local bytes**. A failure during a repair can leave the
   destination replaced but unverified: the failure is reported, and the next transfer detects the
   mismatch and repairs it.

An empty local file skips steps 2–4: the destination is created with `TYPE NUL` and verified the
same way.

### Temporary files

Two short-lived artifacts exist next to the destination during a transfer:

```text
<destination>.<unique>.part        the staging file (decoded content, verified before publish)
<destination>.<unique>.part.b64    the base64 sidecar consumed by certutil -decode
```

`<unique>` combines a process-wide counter with 64 random bits, so concurrent transfers — same
JVM or not — never collide. Both files are removed on success and best-effort deleted on failure;
an interrupted transfer can leave them behind. The 30-day purge below reclaims them in the
transfer directory; next to an `uploadFile(...)` destination, they stay until deleted.

### Housekeeping

Before each transfer-and-run, entries of the transfer directory **not modified for 30 days are
purged** (best effort, via `forfiles`). Content-addressing means every revision of a changing
script gets a new remote name, so without this lifecycle the directory would grow without bound.
The only cost: a script unused for 30 days is re-uploaded once.

## Command-line substitution

With transfer-and-run, after the files are copied, every occurrence of each local path in the
command string is replaced — literally and **case-insensitively** — by the corresponding remote
path, and the result is executed through `CMD.EXE /C (...)`:

```text
given:    CSCRIPT c:\scripts\collect.vbs /debug
uploads:  c:\scripts\collect.vbs
executes: CMD.EXE /C (CSCRIPT C:\Windows\Temp\winrm-upload-MYHOST\collect.1a2b3c4d5e6f.vbs /debug)
```

Notes:

* The match is a literal, case-insensitive substring match on the path as you passed it to
  `upload(...)` (its `toString()`: on a Windows client `scripts/x.vbs` becomes `scripts\x.vbs`) or
  `localFileToCopyList` — use the same spelling in the command (`C:\Scripts\X.vbs` and
  `c:\scripts\x.vbs` both match, but `C:\SCRIPTS\..\SCRIPTS\X.VBS` does not).
* A listed file that the command never references is still uploaded; the command is unchanged.
* Because the transfer legs run first, they are what creates the remote shell — so a
  `workingDirectory(...)` on the same request does not apply (the shell already exists, with its
  default directory, when the actual command runs).

## Upload constraints and safety checks

* **File names** must be safely embeddable in a quoted cmd.exe argument and creatable on Windows.
  Rejected: names containing `%` or `!` (cmd.exe expands them even between quotes), control
  characters, the Windows-forbidden characters `< > : " / \ | ? *`, names ending with a dot or a
  space, and reserved device names (`CON`, `NUL`, `COM1`…, with or without extension).
* **Size**: the mechanism is designed for small script files. Base64 over SOAP costs one WinRM
  operation per ~8&nbsp;kB leg — fine for scripts, wrong for bulk data. The transfer runs under
  the command's `timeout(...)` with `upload(...)`, and under the client's timeout with
  `uploadFile(...)` (no per-call override).
* **Server operation quotas**: old hosts cap concurrent WinRM operations per user very low (15 on
  Windows Server 2008 R2). Transfer steps are batched to minimize operations, and a command
  rejected by the quota *before it could run* is retried with escalating delays (5/10/15/20&nbsp;s).
* **Integrity**: every path through the transfer ends with a digest verification of the actual
  destination; the digest is a transfer-integrity check (the channel itself is authenticated and,
  over HTTPS or with NTLM, encrypted).
* **Host requirements**: the account must be able to run remote commands and to write to the
  destination directory, and the host must provide `certutil` (transfer) and `forfiles`
  (housekeeping); transfer-and-run also needs WMI access, for its WQL query. See
  [Preparing the Windows Host](preparing-the-host.html).

## Downloading a file

[`WinRMClient.downloadFile(...)`](apidocs/org/metricshub/winrm/WinRMClient.html) copies a remote
file to a local one — the counterpart of `uploadFile(...)`, with the same guarantees: the content
is verified, an identical copy is not transferred again, and a failure never leaves a truncated
file behind. The same terminal exists on
[`client.file(path)`](files.html#downloading-to-a-local-file), where the timeout can be set:

```java
client.downloadFile("C:\\Windows\\Temp\\collect.log", Path.of("collect.log"));

long bytes = client.file("D:\\exports\\big.csv")
    .timeout(Duration.ofMinutes(10))
    .downloadTo(Path.of("big.csv"));   // bytes transferred, 0 when the local copy was already identical
```

When the local path is an existing directory, the file is written into it under its remote name,
like `cp`: `downloadFile("C:\\Windows\\Temp\\collect.log", Path.of("logs"))` writes
`logs/collect.log`. A remote name with a colon — an alternate data stream (`a.txt:meta`) or a
drive-relative path (`C:a.txt`) — is refused there: name the local file explicitly. The
destination's directory is created when needed.

### How a download works

1. **Probe.** One PowerShell invocation on the host reports the size and the SHA-256 digest of
   the file. It opens the file exactly like the [remote file reads](files.html) do, with a share
   mode that tolerates other writers — so a log held open by a running service can be downloaded
   if it is not written to meanwhile (`certutil -hashfile`, which the uploads use, fails on such
   a file with a sharing violation).
2. **Skip check.** If the local destination already exists with the same size and digest, nothing
   is transferred: the download returns 0.
3. **Transfer.** The file is read with [`openStream()`](files.html#streaming-large-files) and
   written, block by block, to a temporary file **next to the destination**:
   `<name>.<random>.part` (the name cut to 64 characters, so a long one still fits the file-name
   limits). Memory stays bounded whatever the size of the file — a 64 MiB file was downloaded by a
   JVM limited to a 32 MiB heap.
4. **Verify and publish.** The received bytes must match the probed size and digest; the
   temporary file is then flushed to disk (`fsync`) and moved onto the destination in one atomic
   step (`ATOMIC_MOVE`), replacing any previous file. On Linux and macOS a replaced file keeps its
   permissions — the temporary file is created with them, so a `0600` file stays private
   throughout. On Windows, the new file gets the permissions the directory gives new
   files: an explicit ACL set on the replaced file is not carried over.

**The destination is never seen truncated or half-written**: until the final move it keeps its
previous content (or does not exist), and after it, it has the complete, verified content. On any
failure — a digest mismatch, a read error, a timeout — the destination is left as it was and the
temporary file is deleted as the transfer stops; only a process killed in the middle of a download
can leave a `.part` file behind.

The local copy is always **exactly the bytes the probe hashed**: one consistent version of the
remote file, never a mix of two. A change that reaches bytes not transferred yet — a log being
appended to, a file rewritten — fails the integrity check instead of delivering a torn copy. A
change confined to bytes already transferred leaves that consistent version in place, like any
copy of a file that changes after it was read.

Downloads are **not resumable**: a download that fails or times out starts over from the first
byte next time.

A download is read-only on the host: it writes nothing there, and needs what the remote file
reads need — PowerShell 2.0 or later in `FullLanguage` mode, and read access to the file (see
[Remote Files](files.html#errors-and-requirements)).

### Timeout

The timeout of a download is a **wall-clock deadline for the whole transfer**: the client's
timeout for `downloadFile(...)` (30 seconds by default), or `timeout(Duration)` on the request.
A large file needs a raised timeout — at the speed below, 30 seconds is about 50 MB. When the
deadline fires, the exception says how far the transfer got:

```text
Download of D:\exports\big.csv from server01 timed out after PT30S, 41943040 of 104857600 bytes
transferred: raise the timeout for large files
```

The deadline and the final move exclude each other: a timeout is only reported when the
destination was left untouched. A deadline that fires while the verified file is being moved into
place lets the move complete, and the download succeeds.

### Download performance

Measured over HTTP with NTLM encryption, a download runs at about **1.6–1.9 MB/s**: 20 MiB in
12 seconds on Windows Server 2022 and 2008 R2 (PowerShell 2.0), 13 seconds on 2019, and 64 MiB in
35 seconds on 2022 — probe included. A small file costs under a second (two PowerShell
invocations: the probe and the read), and skipping an identical copy about 0.4 seconds. The limit
is on the host, in the way the WinRM service forwards a command's output — see
[Read performance](files.html#read-performance).

That is one to two orders of magnitude slower than SMB on a local network: **downloads are not a
bulk transport either**. They suit logs, configuration files and command results; to move
gigabytes, use SMB (or any file-transfer protocol the host offers).

## See also

* [Remote Files](files.html) — reading remote files (whole, byte ranges, streams) and listing directories through the WinRM channel
* [Remote Commands](commands.html) — the command builder that carries the transfer
* [Preparing the Windows Host](preparing-the-host.html) — the privileges a transfer needs
