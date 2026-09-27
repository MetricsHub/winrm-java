keywords: remote file, read file, download, byte range, tail, digest, base64, powershell, stream, directory listing, file properties, exists, glob
description: How the WinRM Java Client reads files and lists directories on the remote host through the WinRM channel itself — whole files, byte ranges, tails, streams, downloads, digests, file properties, and filtered recursive listings.

# Remote Files

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

WinRM has no file-access operation of its own (nothing like SFTP's `READ`), so the client reads
remote files and lists directories **through the WinRM command shell**: a small PowerShell script
does the work on the host and writes the result in an encoding-proof form, and the client decodes
it as it arrives. No SMB, no extra port, no share. This is the reverse direction of
[File Transfers](file-transfers.html). The standalone jar exposes it as the `ls`, `stat`, `cat`,
and `get` subcommands — see the [Command-Line Client](cli.html#remote-files) manual.

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

## Downloading to a local file

`downloadTo(Path)` writes the whole file to a local file — digest-verified, skipped when the local
copy is already identical, and atomic: the destination is replaced in one step, never left
half-written. `client.downloadFile(remote, local)` does the same with the client's timeout:

```java
long bytes = client.file("C:\\Windows\\Temp\\collect.log").downloadTo(Path.of("collect.log"));
```

It streams, so memory stays bounded whatever the size of the file, and its timeout is a
wall-clock deadline for the whole transfer. The mechanics, the guarantees and the measured speed
are described in [File Transfers](file-transfers.html#downloading-a-file).

## Timeouts

The blocking terminals (`readBytes`, `readText`, `downloadTo`, `digest`, `info`, `exists`, and
`execute()` on a [directory listing](#listing-a-directory)) run under a **wall-clock deadline**:
the client's timeout, or `timeout(Duration)` on the request — raise it for large reads, downloads
and big trees.
`openStream()`/`openReader()` and a listing's `stream()` use the **inactivity** semantics of the
other streaming terminals: the timeout bounds the silence between two blocks, not the whole
operation. See [Timeouts and Errors](timeouts-and-errors.html).

## Errors and requirements

* The file is opened with a share mode that tolerates other writers, so **a log being written by
  a running service can be read**. A file held with an exclusive lock (`pagefile.sys`, live
  registry hives) fails with a *sharing violation*.
* Every failure is a `WinRMClientException` naming its cause: path not found, a directory where a
  file is expected (or a file given to `list()`), access denied, sharing violation, PowerShell not
  available, or PowerShell in Constrained Language Mode.
* A script rejected at startup by the host's per-user operation quota (as low as 15 concurrent
  operations on Windows Server 2008 R2) has not run: it is retried with escalating delays (5, 10,
  15, 20 seconds) for as long as the timeout allows, like the steps of a
  [file transfer](file-transfers.html).
* The host needs **PowerShell 2.0 or later in `FullLanguage` mode** (Windows Server 2008 R2 and
  later ship it). When AppLocker or WDAC puts PowerShell in Constrained Language Mode, the .NET
  calls the scripts rely on are blocked: remote file access is then not available.
* Non-ASCII paths and content are safe: the path travels base64-encoded (UTF-8) inside the script
  and the content comes back base64-encoded, so neither depends on the remote console code page.
* Remote file access never writes anything on the host: its scripts travel on the command line,
  which limits the path to about **1,450 characters** for a read, **1,150** for `info()`, and
  **450** for `list()`, whose walker script is the largest (roughly half as many with accented
  letters, a third with CJK characters) — all above the classic 260-character `MAX_PATH`. A
  longer path fails with a `WinRMClientException` before anything is sent.

## Read performance

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

## File properties

`info()` returns the properties of a file or directory — or an empty `Optional` when the path
does not exist, which is not an error. `exists()` is `info().isPresent()`.

```java
Optional<RemoteFileInfo> info = client.file("C:\\Windows\\Temp\\collect.log").info();
info.ifPresent(i -> System.out.println(i.size() + " bytes, modified " + i.lastModified()));

boolean there = client.file("C:\\Windows\\Temp\\collect.log").exists();
```

[`RemoteFileInfo`](apidocs/org/metricshub/winrm/RemoteFileInfo.html) is an immutable value:

| Accessor | Content |
|---|---|
| `path()`, `name()` | the full path as reported by the host, and its last element |
| `isDirectory()` | a directory (including a junction or a directory symbolic link) |
| `size()` | the size in bytes, 0 for a directory |
| `lastModified()`, `created()`, `lastAccessed()` | UTC timestamps, 100 ns precision |
| `isHidden()`, `isSystem()`, `isReadOnly()`, `isArchive()`, `isReparsePoint()` | the attribute flags |
| `attributes()` | the raw Windows `FileAttributes` value, for anything else |

Genuine failures still throw a `WinRMClientException`: access denied on the path itself, an
invalid path, PowerShell unavailable or constrained.

## Listing a directory

`list()` prepares the listing of a directory; set its filters, then `execute()` it or
`stream()` it:

```java
RemoteFileList logs = client.file("C:\\inetpub\\logs").list()
    .glob("*.log")
    .recursive()                                    // off by default
    .maxDepth(3)
    .filesOnly()                                    // or directoriesOnly()
    .modifiedAfter(Instant.now().minus(Duration.ofDays(1)))
    .minSize(1024L)
    .execute();
logs.entries();        // List<RemoteFileInfo>
logs.inaccessible();   // List<String>: directories that could not be read

// Large trees stream: memory stays bounded whatever the size of the tree
try (Stream<RemoteFileInfo> tree = client.file("D:\\data").list()
        .recursive()
        .onInaccessible(path -> System.err.println("skipped " + path))
        .stream()) {
    tree.filter(RemoteFileInfo::isDirectory).forEach(System.out::println);
}
```

| Setting | Effect |
|---|---|
| *(none)* | the directory's own entries |
| `recursive()` | the whole tree, depth-first |
| `maxDepth(n)` | the tree down to depth `n` (1 = the directory's own entries); implies `recursive()` |
| `glob(pattern)` | entries whose **name** matches: `*` any sequence, `?` one character, everything else literal, case-insensitive, whole name (`*.log` matches `app.log`, not `app.log.1`) |
| `filesOnly()`, `directoriesOnly()` | one kind of entry |
| `modifiedAfter(instant)`, `modifiedBefore(instant)` | last write time strictly after / before |
| `minSize(bytes)`, `maxSize(bytes)` | file size bounds, inclusive; directories have no size and are not filtered by them |
| `onInaccessible(consumer)` | told of each directory that could not be read, as the host reports it |
| `timeout(duration)` | wall-clock deadline for `execute()`, inactivity timeout for `stream()` |

**Every filter is evaluated on the host**: a big tree is not shipped over the wire just to be
discarded locally. The filters select what is *reported*, not where the walk goes: with
`recursive()`, every subdirectory is traversed, whatever the glob or the type filter.

Like the other streaming terminals, **a `stream()` must be closed** (try-with-resources): it
holds the client's connection until it is exhausted or closed, and closing it early stops the
remote walk. Failures are thrown from the stream's operations, when the host reports them. The
host signals it is alive every second while it walks, so the inactivity timeout only trips when
the host is unresponsive — or when reading a single directory takes longer than the timeout.

### Links and inaccessible directories

* **Reparse points** — junctions, symbolic links, mount points — are reported (with
  `isReparsePoint()` true) but **never descended into**: a junction looping back to its parent,
  like the legacy `C:\Documents and Settings`-style junctions, cannot make a walk run forever.
  Listing a junction explicitly lists its target.
* A subdirectory that cannot be read (typically access denied) **does not stop the walk**: its
  path is reported through `onInaccessible(...)` and `RemoteFileList.inaccessible()`, and its
  content is missing from the entries. The directory being listed is different: when it cannot be
  read, the listing fails.
* An administrator's WinRM session holds the *backup* privilege, enabled, and directory
  enumeration uses backup semantics: permissions that deny reading a directory do not stop an
  administrator. Inaccessible directories are mostly met with non-administrator accounts.

### Listing performance

The walk runs in the host's PowerShell and streams one short text line per reported entry.
Measured over HTTP with NTLM encryption: `C:\Windows\System32` (16,000 entries) in 2.3 seconds,
the whole of `C:\Windows` (126,000 entries) in 15 seconds on Windows Server 2022; the same
System32 in 5.5 seconds on Windows Server 2008 R2 (PowerShell 2.0). With selective filters, only
the walk itself counts: a recursive `*.dll` over 10 MB in System32 takes under a second on 2022.

## How the metadata travels

The listing does not parse `dir` output (locale-dependent columns, dates and decimal separators,
minute granularity), nor use `Get-ChildItem` (whose `-Depth`, `-File` and `-Directory` need
PowerShell 3 to 5, and whose object pipeline is slow on big trees). A small script walks the tree
with the .NET `DirectoryInfo` API and an explicit stack — on every PowerShell version from 2.0 —
and writes **one ASCII line per entry**: the attributes, size and timestamps as plain integers
(Windows file times), and only the path base64-encoded (UTF-8). Non-ASCII names therefore
round-trip exactly, whatever the remote console code page, and a truncated or malformed line
fails with a clear exception instead of producing a half-populated entry.

WMI could serve metadata too (`CIM_DataFile`, `CIM_Directory` and `ASSOCIATORS OF` queries through
[`client.wql(...)`](wql.html)), and remains an alternative where PowerShell is constrained — but
the WMI file provider is notoriously slow (an unindexed `CIM_DataFile` query on a large tree can
take minutes), a recursive listing needs one query per directory, and timestamps come back as
DMTF strings.

## Paths and limitations

* **Long paths**: paths longer than the classic 260-character `MAX_PATH` work where PowerShell
  runs on .NET Framework 4.6.2 or later (Windows Server 2016 and later out of the box; older
  versions with WMF 5.1 and an updated .NET): the scripts use `\\?\`-prefixed paths there, and
  report paths without the prefix. On older hosts (e.g.
  Windows Server 2008 R2 with PowerShell 2.0), a path over 260 characters fails with an explicit
  "path too long" error. The directory *given* to `list()` is limited to about 450
  characters (see [the requirements](#errors-and-requirements)); the entries a
  listing reports can be of any length.
* **UNC paths** (`\\server\share\...`) are a *second hop*: the host must authenticate to the file
  server with your credentials, which NTLM does not allow. They need Kerberos
  [credential delegation](authentication.html#credential-delegation) (`allowDelegation()`):
  without it, access to a UNC path typically fails with access denied.
* `lastAccessed()` is only as good as the host keeps it: many Windows versions disable or delay
  last-access updates.

## See also

* [File Transfers](file-transfers.html) — copying local files to the host, and how downloads work
* [Remote Commands](commands.html) — the command shell the reads and listings ride
* [WQL Queries](wql.html) — the WMI alternative for file metadata
