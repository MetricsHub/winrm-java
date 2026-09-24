keywords: remote file, read file, byte range, tail, digest, base64, powershell, stream
description: How the WinRM Java Client reads files on the remote host through the WinRM channel itself — whole files, byte ranges, tails, streams, and digests.

# Remote Files

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

WinRM has no file-access operation of its own (nothing like SFTP's `READ`), so the client reads
remote files **through the WinRM command shell**: a small PowerShell script opens the file on the
host and writes the requested bytes base64-encoded, and the client decodes them as they arrive.
No SMB, no extra port, no share. This is the reverse direction of
[File Transfers](file-transfers.html).

## Reading a file

[`client.file(path)`](apidocs/org/metricshub/winrm/RemoteFile.html) designates a file on the
remote host; nothing is sent until a terminal is called:

```java
// Whole file, bytes (byte-exact: nothing converted, a BOM is kept)
byte[] content = client.file("C:\\Windows\\Temp\\collect.bin").readBytes();

// Whole file, text — the charset is always explicit, never guessed
String text = client.file("C:\\inetpub\\logs\\u_ex260729.log").readText(StandardCharsets.UTF_8);

// Integrity: only the digest is transferred (MD5, SHA1, SHA256, SHA384, SHA512)
String sha256 = client.file("C:\\Windows\\Temp\\collect.ps1").digest("SHA256");
```

## Byte ranges and tails

`offset(long)` and `length(long)` select a byte range, served by a **seek** on the host — reading
the end of a multi-gigabyte log costs the same as reading the start of a small one:

```java
// 64 KiB from the 1 GiB position
byte[] chunk = client.file("D:\\logs\\huge.log").offset(1_073_741_824L).length(65_536).readBytes();

// The last 8 KiB — a negative offset counts from the end
byte[] tail = client.file("D:\\logs\\huge.log").offset(-8192).readBytes();
```

| Setting | Result |
|---|---|
| `offset(-n)` | starts at `max(0, size - n)`: a file shorter than `n` is read whole. The size is read by the same remote invocation that seeks, so a growing log is tailed from its current end. |
| `offset(-8192).length(1024)` | the first 1 KiB of the last 8 KiB |
| offset past the end | empty — not an error |
| `length` beyond the end | what exists |
| `length(0)`, an empty file | empty |

Ranges are **byte** ranges: a range boundary can split a multibyte character, which then decodes
to `U+FFFD` at the edges of a `readText`. Reads are not snapshots: a file that changes between two
reads is read as it is at each read.

## Streaming large files

`readBytes()` and `readText()` hold the whole content in memory, so they are capped:
**64 MiB by default**, adjustable with `maxBytes(long)` — reading more fails with a clear
exception instead of an `OutOfMemoryError` (the cap is sent to the host too, so no more than one
byte past it is transferred). For anything larger, stream:

```java
try (InputStream in = client.file("D:\\logs\\huge.log").openStream()) {
    in.transferTo(out);
}

try (BufferedReader reader = client.file("D:\\logs\\huge.log").openReader(StandardCharsets.UTF_8)) {
    reader.lines().filter(line -> line.contains("ERROR")).forEach(System.out::println);
}
```

The stream decodes the content block by block as it arrives (48 KiB per block): memory stays
bounded whatever the file size. Like a [`RemoteProcess`](commands.html), **it must be closed** —
it holds the client's connection until it reaches its end or is closed, and closing it early stops
the remote read. The file is opened before `openStream()` returns, so a missing file fails there;
a failure midway is reported by `read()`, never as a silently short read.

## Timeouts

The blocking terminals (`readBytes`, `readText`, `digest`) run under a **wall-clock deadline**:
the client's timeout, or `timeout(Duration)` on the request — raise it for large reads.
`openStream()`/`openReader()` use the **inactivity** semantics of the other streaming terminals:
the timeout bounds the silence between two blocks, not the whole read. See
[Timeouts and Errors](timeouts-and-errors.html).

## Locked files, errors and requirements

* The file is opened with a share mode that tolerates other writers, so **a log being written by
  a running service can be read**. A file held with an exclusive lock (`pagefile.sys`, live
  registry hives) fails with a *sharing violation*.
* Every failure is a `WinRMClientException` naming its cause: file not found, the path is a
  directory, access denied, sharing violation, PowerShell not available, or PowerShell in
  Constrained Language Mode.
* The host needs **PowerShell 2.0 or later in `FullLanguage` mode** (Windows Server 2008 R2 and
  later ship it). When AppLocker or WDAC puts PowerShell in Constrained Language Mode, the .NET
  calls the reader relies on are blocked: remote file access is then not available.
* Non-ASCII paths and content are safe: the path travels base64-encoded (UTF-8) inside the script
  and the content comes back base64-encoded, so neither depends on the remote console code page.
* A read never writes anything on the host: the reader script travels on the command line, which
  limits the path to about **1,450 characters** (about 720 with accented letters, 480 with CJK
  characters — well above the classic 260-character `MAX_PATH`). A longer path fails with a
  `WinRMClientException` before anything is sent.

## Performance

The mechanism is designed for **configuration files, logs and small data files — not bulk data**.
Measured over HTTP with NTLM encryption, `openStream()` reads about **1.4–1.5 MB/s**: a 20 MiB
file in 14–15 seconds, on Windows Server 2008 R2 (PowerShell 2.0), 2019 and 2022 (PowerShell 5.1)
alike. A small file costs about a second, mostly the PowerShell startup. For gigabytes, use SMB.

The limit is on the host, not in the network or the client: the WinRM service reads a command's
output pipe itself, **at most 32 KiB per read and about 60 reads per second** per output stream —
about 2 MB/s of output whatever the command. The reader is shaped for it: each block of the file
travels as one 65,533-byte base64 line that fills exactly two reads (a larger 77,825-byte line
needing three reads was 20–25% slower). Neither the client's `MaxEnvelopeSize` nor the host's
`MaxEnvelopeSizekb` changes the limit — raising them only makes each response larger and
proportionally slower to come.

The same limit applies to any command output: a command that writes its output in small pieces —
line by line, like `Write-Output` or `type` — gets only what accumulated in the 4 KiB pipe at each
read, about 0.3 MB/s. Parallel commands, each on its own `WinRMClient`, add up: each command gets
its own output pipeline.

## See also

* [File Transfers](file-transfers.html) — the other direction: copying local files to the host
* [Remote Commands](commands.html) — the command shell the reads ride
