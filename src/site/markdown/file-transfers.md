keywords: file transfer, file copy, upload, certutil, base64, digest, content-addressed, temporary files
description: How the WinRM Java Client copies local files to the remote host through the WinRM channel itself — destination paths, temporary files, integrity verification, and command-line substitution.

# File Transfers

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

The client can copy local files to the remote host **through the WinRM connection itself** — no
SMB, no TCP port 445, no administrative share — so it works from any client OS and needs no port
beyond the WinRM one. This page explains exactly how the transfer works: where files land, which
temporary files are created, how integrity is guaranteed, and how the command line is rewritten.

## The two entry points

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

Both use the same transfer engine described below; they differ only in where the file lands.

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
  directory. Clients that report the same computer name (cloned machines, containers) share one —
  which is safe, because the content-addressed file names below prevent them from ever
  overwriting each other's payloads; they simply also share the cache and the 30-day cleanup.
  Versions before 2.0.0 used `<windir>\Temp\SEN_ShareFor_<CLIENT-COMPUTER-NAME>$`, exposed as a
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
drive-rooted (`C:\...`) or UNC (`\\server\share\...`); relative and drive-relative (`C:x.ps1`)
paths are rejected, because they would resolve against the remote shell's current directory. The
destination directory is created if missing.

## How the bytes travel

The transfer rides the already-authenticated (and, over HTTP, NTLM-encrypted) WinRM command
shell — every step below is an ordinary remote command:

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
   the destination provably contains the local bytes**. On any failure the temporary files are
   deleted (best effort). A destination that already carried the correct content is never
   touched; but when a *mismatched* destination is being repaired, the replacement happens before
   the final verification — so a failure during a repair can leave the destination already
   replaced (and still unverified). Either way an unverified destination is never silently
   trusted: the failure is reported, and the next transfer detects the mismatch and repairs it.

An empty local file skips steps 2–4: the destination is created with `TYPE NUL` and verified the
same way.

### Temporary files

Two short-lived artifacts exist next to the destination during a transfer:

```text
<destination>.<unique>.part        the staging file (decoded content, verified before publish)
<destination>.<unique>.part.b64    the base64 sidecar consumed by certutil -decode
```

`<unique>` combines a process-wide counter with 64 random bits, so concurrent transfers — same
JVM or not — never collide. Both files are removed on success and best-effort deleted on failure.

### Housekeeping

Before each transfer-and-run, entries of the transfer directory **not modified for 30 days are
purged** (best effort, via `forfiles`). Content-addressing means every revision of a changing
script gets a new remote name, so without this lifecycle the directory would grow without bound;
the purge also reclaims staging files orphaned by an interrupted transfer. The only cost: a
script unused for 30 days is re-uploaded once.

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

* The match is a literal, case-insensitive substring match on the path exactly as you passed it
  to `upload(...)`/`localFileToCopyList` — use the same spelling in the command (`C:\Scripts\X.vbs`
  and `c:\scripts\x.vbs` both match, but `C:\SCRIPTS\..\SCRIPTS\X.VBS` does not).
* A listed file that the command never references is still uploaded; the command is unchanged.
* Because the transfer legs run first, they are what creates the remote shell — so a
  `workingDirectory(...)` on the same request does not apply (the shell already exists, with its
  default directory, when the actual command runs).

## Constraints and safety checks

* **File names** must be safely embeddable in a quoted cmd.exe argument and creatable on Windows.
  Rejected: names containing `%` or `!` (cmd.exe expands them even between quotes), `"`, control
  characters, the Windows-forbidden characters `< > : " / \ | ? *`, names ending with a dot or a
  space, and reserved device names (`CON`, `NUL`, `COM1`…, with or without extension).
* **Size**: the mechanism is designed for small script files. Base64 over SOAP costs one WinRM
  operation per ~8&nbsp;kB leg — fine for scripts, wrong for bulk data.
* **Server operation quotas**: old hosts cap concurrent WinRM operations per user very low (15 on
  Windows 2008 R2). Transfer steps are batched to minimize operations, and a command rejected by
  the quota *before it could run* is retried with escalating delays (5/10/15/20&nbsp;s).
* **Integrity**: every path through the transfer ends with a digest verification of the actual
  destination; the digest is a transfer-integrity check (the channel itself is authenticated and,
  over HTTP, encrypted).
* **Host requirements**: the account must be able to run remote commands and to write to the
  destination directory, and the host must provide `certutil` (transfer) and `forfiles`
  (housekeeping). See [Preparing the Windows Host](preparing-the-host.html).

## See also

* [Remote Commands](commands.html) — the command builder that carries the transfer
* [Preparing the Windows Host](preparing-the-host.html) — the privileges a transfer needs
