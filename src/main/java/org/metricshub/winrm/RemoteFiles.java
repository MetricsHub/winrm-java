package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2026 MetricsHub
 * ჻჻჻჻჻჻
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;

/**
 * The remote file access primitive: small PowerShell scripts that open a remote file and write
 * what the caller asked for as <b>base64 lines</b> on stdout, and the {@link InputStream} that
 * decodes those lines as the output chunks arrive. The metadata scripts ({@link #infoScript},
 * {@link #listScript}) write one ASCII record per entry instead, parsed by {@link #parseEntry}
 * and {@link #parseInaccessible}: plain integers, and only the path base64-encoded.
 * <p>
 * Base64 is what makes a text console a binary-safe channel: every byte value survives, and the
 * output is pure ASCII, so recovering the exact bytes never depends on the remote console code
 * page. Each line is an independent base64 block, decoded on its own. The caller-supplied path is
 * embedded base64-encoded too (UTF-8), so no path ever needs quoting or escaping.
 * <p>
 * Failures are reported by the script's exit code, checked when the output ends (see the
 * {@code EXIT_*} constants), and turned into a {@link WinRMClientException} naming the cause.
 */
final class RemoteFiles {

	private RemoteFiles() {}

	/** The path does not exist. */
	static final int EXIT_NOT_FOUND = 2;

	/** The file is held with an exclusive lock by another process (sharing violation). */
	static final int EXIT_SHARING_VIOLATION = 3;

	/** Access to the path is denied. */
	static final int EXIT_ACCESS_DENIED = 4;

	/** PowerShell runs in Constrained Language Mode: the .NET calls the scripts rely on are blocked. */
	static final int EXIT_CONSTRAINED_LANGUAGE = 5;

	/** The path is a directory, not a file. */
	static final int EXIT_IS_DIRECTORY = 6;

	/** The path is a file, not a directory (listing). */
	static final int EXIT_NOT_DIRECTORY = 7;

	/** The exit code of {@code cmd.exe} when {@code powershell.exe} cannot be found. */
	static final int EXIT_COMMAND_NOT_FOUND = 9009;

	/**
	 * Bytes read and written per base64 line: 49,149, a multiple of 3 so a full block encodes
	 * without padding, into 65,532 characters — with the newline, a 65,533-byte line that fits in
	 * exactly two of the WinRM service's output reads. The service's shell plugin reads a
	 * command's stdout pipe with at most one 32 KiB read per timer tick (64 per second at the
	 * default 15.625 ms), so each line should fill whole reads: a 57 KiB block (a 77,825-byte
	 * line) needs three, and is measurably slower.
	 */
	static final int BLOCK_SIZE = 49_149;

	/**
	 * The hash algorithms {@link #digestScript(String, String)} accepts: the names .NET's
	 * {@code HashAlgorithm.Create} knows on every Windows version (PowerShell 2.0 included). The
	 * name is embedded in the script, hence the closed list.
	 */
	static final Set<String> DIGEST_ALGORITHMS = Set.of("MD5", "SHA1", "SHA256", "SHA384", "SHA512");

	/**
	 * The opening shared by every script: check the language mode, define {@code fail}, which maps
	 * a .NET failure to the documented exit code (a sharing violation is HRESULT
	 * {@code 0x80070020}, 32; a byte-range lock violation {@code 0x80070021}, 33), and decode the
	 * path into {@code $p}. {@code exit} in a function exits the whole script. {@code %s} is the
	 * base64 of the path's UTF-8 bytes.
	 */
	private static final String PREAMBLE = "if($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage'){exit 5};"
		+
		"$ErrorActionPreference='Stop';" +
		"function fail($e){if($e.InnerException){$e=$e.InnerException};" +
		"[Console]::Error.WriteLine($e.Message);" +
		"if($e -is [IO.FileNotFoundException] -or $e -is [IO.DirectoryNotFoundException]){exit 2};" +
		"if($e -is [UnauthorizedAccessException]){exit 4};" +
		"$h=[Runtime.InteropServices.Marshal]::GetHRForException($e) -band 0xFFFF;" +
		"if($h -eq 32 -or $h -eq 33){exit 3};" +
		"exit 1};" +
		"$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'));";

	/**
	 * Open the file with a share mode that tolerates other writers ({@code ReadWrite}) — a log
	 * written by a running service is the most common thing to read, and {@code File.OpenRead}
	 * fails on it.
	 */
	private static final String OPEN_FILE = PREAMBLE +
		"if([IO.Directory]::Exists($p)){exit 6};" +
		"try{$f=[IO.File]::Open($p,'Open','Read','ReadWrite')}catch{fail $_.Exception};";

	/**
	 * The opening of the metadata scripts, after {@link #PREAMBLE}: the record writer and the path
	 * resolution. Kept terse: the script must fit the command line with room for long paths.
	 * <ul>
	 * <li>{@code out} writes the buffered records and a newline to the raw stdout stream as one
	 * write (see {@link #READ_RANGE} for why); with nothing buffered, the bare newline is a
	 * keepalive the parser skips, so a long walk that matches nothing does not trip the inactivity
	 * timeout; {@code $v} times the silence since the last write — a {@code Stopwatch}, monotonic
	 * and 64-bit, where {@code Environment.TickCount} is negative for half of its 49.7-day cycle;
	 * <li>the path is made absolute, then given the {@code \\?\} prefix ({@code \\?\UNC\} for a
	 * UNC path) that lifts the 260-character {@code MAX_PATH} limit — where .NET accepts it (4.6.2
	 * and later: older versions reject {@code GetFullPath('\\?\...')}, and paths stay limited
	 * there); {@code $pn} and {@code $pa} strip the prefix from the reported paths again;
	 * <li>{@code $a} gets the attributes of the path, failing with the documented exit codes.
	 * </ul>
	 */
	private static final String METADATA = PREAMBLE +
		"$o=[Console]::OpenStandardOutput();$u=[Text.Encoding]::UTF8;$w=New-Object Text.StringBuilder;" +
		"$v=[Diagnostics.Stopwatch]::StartNew();" +
		"function out{$y=$u.GetBytes(\"$w`n\");$o.Write($y,0,$y.Length);$o.Flush();$w.Length=0;" +
		"$v.Reset();$v.Start()};" +
		"try{$q=[IO.Path]::GetFullPath($p);" +
		"try{$q=[IO.Path]::GetFullPath((($q-replace'^\\\\\\\\(?=[^\\\\?.])','\\\\?\\UNC\\')" +
		"-replace'^(?=[A-Za-z]:\\\\)','\\\\?\\'))}catch{};" +
		"$a=[int][IO.File]::GetAttributes($q)}catch{fail $_.Exception};" +
		"$pn=0;$pa='';if($q -match '^\\\\\\\\\\?\\\\(UNC)?'){$pn=$matches[0].Length;if($matches[1]){$pa='\\'}};";

	/**
	 * Buffer the {@code F} record of the {@code FileSystemInfo} in {@code $e}: plain integers
	 * (PowerShell expands them with the invariant culture) and the base64 of the UTF-8 path.
	 * Inlined wherever it is used: a PowerShell function call costs more than the record itself
	 * (about 45 µs, measured), which would make a large listing 10 times slower.
	 */
	private static final String RECORD = "$l=0;if($e -is [IO.FileInfo]){$l=$e.Length};" +
		"[void]$w.Append(\"F $([int]$e.Attributes) $l $($e.LastWriteTimeUtc.ToFileTimeUtc()) " +
		"$($e.CreationTimeUtc.ToFileTimeUtc()) $($e.LastAccessTimeUtc.ToFileTimeUtc()) " +
		"$([Convert]::ToBase64String($u.GetBytes($pa+$e.FullName.Substring($pn))))`n\")";

	/** Write the record of the path itself, a file or a directory. */
	private static final String INFO = "try{if($a -band 16){$e=New-Object IO.DirectoryInfo $q}" +
		"else{$e=New-Object IO.FileInfo $q};" + RECORD + ";out}catch{fail $_.Exception}";

	/**
	 * Walk the directory with an explicit stack of {@code (DirectoryInfo, depth)} pairs:
	 * {@code EnumerateFileSystemInfos} where it exists (.NET 4), {@code GetFileSystemInfos}
	 * otherwise (PowerShell 2.0 runs on .NET 2.0). Every entry is evaluated against the filters
	 * here, on the host; a directory is pushed for traversal whatever the filters, unless it is a
	 * reparse point (junction, symbolic link: never followed, so a junction loop terminates) or
	 * at the maximum depth. A directory that cannot be read becomes a {@code !} record and the walk
	 * goes on — except the root itself, which fails the listing. {@code %s} and {@code %d} are the
	 * base64 name regex (see {@link #globRegex(String)}; empty: any name), the type (0: all, 1:
	 * files, 2: directories), the size bounds, the exclusive FileTime bounds and the maximum depth.
	 */
	private static final String LIST = "if(!($a -band 16)){exit 7};" +
		"$g=$u.GetString([Convert]::FromBase64String('%s'));$k=%d;$mn=%d;$mx=%d;$ta=%d;$tb=%d;$md=%d;" +
		"$m=[IO.DirectoryInfo].GetMethod('EnumerateFileSystemInfos',[Type[]]@());" +
		"$s=New-Object Collections.Stack;$s.Push(@((New-Object IO.DirectoryInfo $q),1));" +
		"while($s.Count){$d,$n=$s.Pop();" +
		"try{if($m){$c=$d.EnumerateFileSystemInfos()}else{$c=$d.GetFileSystemInfos()};" +
		"foreach($e in $c){$i=$e -is [IO.DirectoryInfo];" +
		"if($i -and !([int]$e.Attributes -band 1024) -and $n -lt $md){$s.Push(@($e,($n+1)))};" +
		"$f=$e.LastWriteTimeUtc.ToFileTimeUtc();" +
		"if(($k -eq 0 -or ($k -eq 2) -eq $i) -and ($i -or ($e.Length -ge $mn -and $e.Length -le $mx)) -and " +
		"$f -gt $ta -and $f -lt $tb -and $e.Name -match $g){" + RECORD + "};" +
		"if($w.Length -gt 32000 -or $v.ElapsedMilliseconds -gt 1000){out}}}" +
		"catch{if($n -eq 1){fail $_.Exception};$x=$_.Exception;if($x.InnerException){$x=$x.InnerException};" +
		"[void]$w.Append(\"! $([Convert]::ToBase64String($u.GetBytes($pa+$d.FullName.Substring($pn)))) " +
		"$([Convert]::ToBase64String($u.GetBytes($x.Message)))`n\")}};out";

	/**
	 * Read a byte range: resolve a negative offset from the size of the <i>open</i> stream (so a
	 * growing log is tailed from its current end), seek, and write at most {@code length} bytes
	 * ({@code -1}: to the end) as one base64 line per block. Seeking past the end is legal and
	 * reads nothing. {@code %d} are the offset, the length and the block size.
	 * <p>
	 * Each line goes to the raw stdout stream as ONE write: the WinRM service reads the pipe at
	 * most once per timer tick, taking only what is in the pipe, and {@code [Console]::Out} (an
	 * auto-flushing writer with a small buffer) cuts a line into 256-byte writes that leave each
	 * read with about 4 KiB — 5 to 6 times slower (measured on Windows 2008 R2 and 2022).
	 */
	private static final String READ_RANGE = "try{$n=[long]%d;$l=[long]%d;" +
		"if($n -lt 0){$n=[Math]::Max([long]0,$f.Length+$n)};" +
		"$f.Position=$n;$b=New-Object byte[] %d;$o=[Console]::OpenStandardOutput();" +
		"while($l -ne 0){$c=$b.Length;if($l -gt 0 -and $l -lt $c){$c=[int]$l};" +
		"$r=$f.Read($b,0,$c);if($r -le 0){break};" +
		"$a=[Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($b,0,$r)+\"`n\");" +
		"$o.Write($a,0,$a.Length);if($l -gt 0){$l-=$r}};" +
		"$o.Flush()}catch{fail $_.Exception}finally{$f.Close()}";

	/** Hash the whole file and write the raw digest as one base64 line. {@code %s} is the algorithm. */
	private static final String DIGEST = "try{$h=[Security.Cryptography.HashAlgorithm]::Create('%s');" +
		"[Console]::Out.WriteLine([Convert]::ToBase64String($h.ComputeHash($f)))}catch{fail $_.Exception}finally{$f.Close()}";

	/**
	 * Build the script writing a byte range of the file.
	 *
	 * @param path the remote file
	 * @param offset the byte position to start from; negative counts from the end of the file
	 * @param length how many bytes to read at most, or {@code -1} to read to the end
	 * @return the PowerShell script
	 */
	static String readScript(final String path, final long offset, final long length) {
		return openFile(path) + String.format(READ_RANGE, offset, length, BLOCK_SIZE);
	}

	/**
	 * Build the script writing the digest of the file.
	 *
	 * @param path the remote file
	 * @param algorithm one of {@link #DIGEST_ALGORITHMS}
	 * @return the PowerShell script
	 */
	static String digestScript(final String path, final String algorithm) {
		if (!DIGEST_ALGORITHMS.contains(algorithm)) {
			throw new IllegalArgumentException(
				"Unsupported digest algorithm: " + algorithm + " (supported: " +
					DIGEST_ALGORITHMS.stream().sorted().collect(Collectors.joining(", ")) + ")"
			);
		}
		return openFile(path) + String.format(DIGEST, algorithm);
	}

	private static String openFile(final String path) {
		return String.format(OPEN_FILE, base64(path));
	}

	private static String base64(final String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Build the script writing the {@code F} record of the path itself.
	 *
	 * @param path the remote file or directory
	 * @return the PowerShell script
	 */
	static String infoScript(final String path) {
		return String.format(METADATA, base64(path)) + INFO;
	}

	/**
	 * Build the script listing a directory: one {@code F} record per matching entry, one {@code !}
	 * record per directory that could not be read.
	 *
	 * @param path the remote directory
	 * @param glob the wildcard pattern the entry names must match (see {@link #globRegex}), or
	 *        {@code null}
	 * @param type 0: files and directories, 1: files only, 2: directories only
	 * @param minSize the smallest file size, inclusive
	 * @param maxSize the largest file size, inclusive
	 * @param after the exclusive lower bound of the last write time, as a FileTime
	 * @param before the exclusive upper bound of the last write time, as a FileTime
	 * @param maxDepth the deepest level listed, 1 being the directory's own entries
	 * @return the PowerShell script
	 */
	static String listScript(
		final String path,
		final String glob,
		final int type,
		final long minSize,
		final long maxSize,
		final long after,
		final long before,
		final int maxDepth
	) {
		return String.format(METADATA, base64(path)) +
			String.format(LIST, glob == null ? "" : base64(globRegex(glob)), type, minSize, maxSize, after, before, maxDepth);
	}

	/**
	 * Translate a Windows wildcard pattern into the anchored .NET regex the listing script matches
	 * names with (PowerShell's {@code -match}: case-insensitive): {@code *} is any sequence,
	 * {@code ?} any one character, and every other character is literal. ASCII punctuation is
	 * escaped; letters, digits, {@code _} and non-ASCII characters are never special, and .NET
	 * rejects escaping them.
	 *
	 * @param glob the wildcard pattern
	 * @return the regex
	 */
	static String globRegex(final String glob) {
		final StringBuilder regex = new StringBuilder("^");
		for (final char c : glob.toCharArray()) {
			if (c == '*') {
				regex.append(".*");
			} else if (c == '?') {
				regex.append('.');
			} else if (c < 128 && !Character.isLetterOrDigit(c) && c != '_') {
				regex.append('\\').append(c);
			} else {
				regex.append(c);
			}
		}
		return regex.append('$').toString();
	}

	/** The FileTime of the Unix epoch: 100-nanosecond intervals since 1601-01-01 UTC. */
	private static final long FILETIME_EPOCH = 116_444_736_000_000_000L;

	/** 100-nanosecond intervals per second. */
	private static final long FILETIME_PER_SECOND = 10_000_000L;

	/**
	 * Convert a Windows FileTime to an {@link Instant}.
	 *
	 * @param fileTime 100-nanosecond intervals since 1601-01-01 UTC
	 * @return the instant
	 */
	static Instant fromFileTime(final long fileTime) {
		final long sinceEpoch = fileTime - FILETIME_EPOCH;
		return Instant.ofEpochSecond(
			Math.floorDiv(sinceEpoch, FILETIME_PER_SECOND),
			Math.floorMod(sinceEpoch, FILETIME_PER_SECOND) * 100
		);
	}

	/**
	 * Convert an {@link Instant} to a Windows FileTime, truncated to 100 nanoseconds.
	 *
	 * @param instant the instant
	 * @return 100-nanosecond intervals since 1601-01-01 UTC
	 */
	static long toFileTime(final Instant instant) {
		return FILETIME_EPOCH + instant.getEpochSecond() * FILETIME_PER_SECOND + instant.getNano() / 100;
	}

	/**
	 * Parse an {@code F} record:
	 * {@code F <attributes> <size> <lastWrite> <creation> <lastAccess> <base64(UTF-8 path)>}.
	 *
	 * @param line the record
	 * @return the entry
	 * @throws WinRMClientException when the record is truncated or malformed
	 */
	static RemoteFileInfo parseEntry(final String line) {
		final String[] fields = line.split(" ", 7);
		if (fields.length != 7 || !"F".equals(fields[0])) {
			throw malformed(line, null);
		}
		try {
			return new RemoteFileInfo(
				decode(fields[6]),
				Integer.parseInt(fields[1]),
				Long.parseLong(fields[2]),
				fromFileTime(Long.parseLong(fields[3])),
				fromFileTime(Long.parseLong(fields[4])),
				fromFileTime(Long.parseLong(fields[5]))
			);
		} catch (final IllegalArgumentException e) {
			throw malformed(line, e);
		}
	}

	/**
	 * Parse a {@code !} record: {@code ! <base64(UTF-8 path)> <base64(UTF-8 message)>}.
	 *
	 * @param line the record
	 * @return the path of the directory that could not be read
	 * @throws WinRMClientException when the record is truncated or malformed
	 */
	static String parseInaccessible(final String line) {
		final String[] fields = line.split(" ", 3);
		if (fields.length != 3 || !"!".equals(fields[0])) {
			throw malformed(line, null);
		}
		try {
			decode(fields[2]);
			return decode(fields[1]);
		} catch (final IllegalArgumentException e) {
			throw malformed(line, e);
		}
	}

	private static String decode(final String base64) {
		if (base64.isEmpty()) {
			throw new IllegalArgumentException("empty field");
		}
		return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
	}

	private static WinRMClientException malformed(final String line, final Throwable cause) {
		return new WinRMClientException(
			"Malformed remote file record: " + (line.length() > 120 ? line.substring(0, 120) + "..." : line),
			cause
		);
	}

	/**
	 * Start the script and return the stream of the bytes it writes. The first line is fetched
	 * before returning, so a file that cannot be opened fails here, not on the first read.
	 *
	 * @param client the client to run the script on
	 * @param path the remote file, for the error messages
	 * @param script the script, from {@link #readScript} or {@link #digestScript}
	 * @param timeout the inactivity timeout of the stream
	 * @return the decoded stream; it must be closed
	 */
	static InputStream open(final WinRMClient client, final String path, final String script, final Duration timeout) {
		final RemoteProcess process = start(client, path, script, timeout);
		try {
			return new DecodingStream(process, path, client.hostname());
		} catch (final RuntimeException e) {
			process.close();
			throw e;
		}
	}

	/**
	 * Start a script. It must fit the command line: {@code powerShell(...)} would transparently
	 * fall back to uploading it as a file, and file access must stay read-only on the host (no
	 * files written, no certutil), so a script too long is refused instead.
	 *
	 * @param client the client to run the script on
	 * @param path the remote path, for the error messages
	 * @param script the script
	 * @param timeout the inactivity timeout of the process
	 * @return the running process; it must be closed
	 */
	static RemoteProcess start(final WinRMClient client, final String path, final String script, final Duration timeout) {
		if (CommandRequest.encodePowerShell(script) == null) {
			throw new WinRMClientException(
				String.format(
					"Remote path too long to access on %s (%d characters): the script must fit the remote command line",
					client.hostname(),
					path.length()
				)
			);
		}
		return client.powerShell(script).timeout(timeout).start();
	}

	/**
	 * Read the next stdout line of a script.
	 *
	 * @param stdout the script's stdout
	 * @return the line, or {@code null} at the end of the output
	 */
	static String readLine(final BufferedReader stdout) {
		try {
			return stdout.readLine();
		} catch (final IOException e) {
			// Unreachable: the reader reports failures unchecked (see RemoteProcess).
			throw new WinRMClientException(e.getMessage(), e);
		}
	}

	/**
	 * The output of a script ended: collect the exit code and release the connection.
	 *
	 * @param process the script's process
	 * @param path the remote path, for the error messages
	 * @param hostname the remote host, for the error messages
	 * @param notFoundIsEmpty whether {@link #EXIT_NOT_FOUND} is a normal outcome, not a failure
	 * @return the exit code, 0 or {@link #EXIT_NOT_FOUND} when {@code notFoundIsEmpty}
	 * @throws WinRMClientException for any other non-zero exit code
	 */
	static int finish(
		final RemoteProcess process,
		final String path,
		final String hostname,
		final boolean notFoundIsEmpty
	) {
		final int exitCode = process.waitFor();
		// PowerShell serializes its progress records to a redirected stderr as CLIXML (e.g. "Preparing
		// modules for first use"): noise, not the error message.
		final String stderr = exitCode == 0
			? ""
			: process
				.stderr()
				.lines()
				.filter(line -> !line.startsWith("#< CLIXML") && !line.startsWith("<Objs "))
				.collect(Collectors.joining("\n"));
		process.close();
		if (exitCode != 0 && !(notFoundIsEmpty && exitCode == EXIT_NOT_FOUND)) {
			throw failure(exitCode, path, hostname, stderr);
		}
		return exitCode;
	}

	/**
	 * Run a blocking terminal under the wall-clock deadline: a worker runs the exchange and is
	 * cancelled when the deadline fires, exactly like {@link CommandRequest#execute()}.
	 *
	 * @param <T> the result type
	 * @param client the client, for the error messages
	 * @param path the remote path, for the error messages
	 * @param timeout the deadline
	 * @param task the exchange
	 * @return the result of the task
	 */
	static <T> T blocking(final WinRMClient client, final String path, final Duration timeout, final Callable<T> task) {
		try {
			return Utils.execute(task, WinRMClient.toMillis(timeout));
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("Accessing remote path %s timed out after %s on %s", path, timeout, client.hostname()),
				e
			);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WinRMClientException(e.getMessage(), e);
		} catch (final ExecutionException e) {
			final Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new WinRMClientException(String.valueOf(cause.getMessage()), cause);
		}
	}

	/**
	 * Build the exception reporting a non-zero exit code of a script.
	 *
	 * @param exitCode the script's exit code
	 * @param path the remote file
	 * @param hostname the remote host
	 * @param stderr what the script wrote on stderr
	 * @return the exception to throw
	 */
	static WinRMClientException failure(
		final int exitCode,
		final String path,
		final String hostname,
		final String stderr
	) {
		switch (exitCode) {
		case EXIT_NOT_FOUND:
			return new WinRMClientException(String.format("Remote path not found on %s: %s", hostname, path));
		case EXIT_SHARING_VIOLATION:
			return new WinRMClientException(
				String.format(
					"Remote file %s on %s is locked by another process (sharing violation): it cannot be read",
					path,
					hostname
				)
			);
		case EXIT_ACCESS_DENIED:
			return new WinRMClientException(String.format("Access denied to remote file %s on %s", path, hostname));
		case EXIT_CONSTRAINED_LANGUAGE:
			return new WinRMClientException(
				String.format(
					"PowerShell runs in Constrained Language Mode on %s (e.g. AppLocker or WDAC): remote file access needs FullLanguage",
					hostname
				)
			);
		case EXIT_NOT_DIRECTORY:
			return new WinRMClientException(String.format("Remote path %s on %s is a file, not a directory", path, hostname));
		case EXIT_IS_DIRECTORY:
			return new WinRMClientException(String.format("Remote path %s on %s is a directory, not a file", path, hostname));
		case EXIT_COMMAND_NOT_FOUND:
			return new WinRMClientException(
				String.format("PowerShell is not available on %s: remote file access needs powershell.exe", hostname)
			);
		default:
			return new WinRMClientException(
				String.format(
					"Failed to access remote path %s on %s (exit code %d)%s",
					path,
					hostname,
					exitCode,
					stderr.isBlank() ? "" : ": " + stderr.strip()
				)
			);
		}
	}

	/**
	 * The bytes a script writes, decoded line by line as the output arrives: memory is bounded by
	 * one line, not by the file. The exit code is checked when the output ends, so a failure
	 * midway is reported instead of looking like a short file.
	 */
	private static final class DecodingStream extends InputStream {

		private final RemoteProcess process;
		private final BufferedReader stdout;
		private final String path;
		private final String hostname;
		private byte[] block = new byte[0];
		private int position;
		private boolean ended;

		private DecodingStream(final RemoteProcess process, final String path, final String hostname) {
			this.process = process;
			this.stdout = process.stdout();
			this.path = path;
			this.hostname = hostname;
			fill();
		}

		@Override
		public int read() {
			return fill() ? block[position++] & 0xFF : -1;
		}

		@Override
		public int read(final byte[] buffer, final int offset, final int length) {
			Objects.checkFromIndexSize(offset, length, buffer.length);
			if (length == 0) {
				return 0;
			}
			if (!fill()) {
				return -1;
			}
			final int count = Math.min(length, block.length - position);
			System.arraycopy(block, position, buffer, offset, count);
			position += count;
			return count;
		}

		@Override
		public int available() {
			return block.length - position;
		}

		/** Make sure unread bytes are buffered: {@code false} at the end of a successful output. */
		private boolean fill() {
			while (position == block.length) {
				if (ended) {
					return false;
				}
				final String line = readLine(stdout);
				if (line == null) {
					end();
					return false;
				}
				final String data = line.strip();
				if (data.isEmpty()) {
					continue;
				}
				try {
					block = Base64.getDecoder().decode(data);
				} catch (final IllegalArgumentException e) {
					throw new WinRMClientException(
						String.format(
							"Unexpected output while reading remote file %s on %s: %s",
							path,
							hostname,
							data.length() > 80 ? data.substring(0, 80) + "..." : data
						),
						e
					);
				}
				position = 0;
			}
			return true;
		}

		/** The output ended: collect the exit code, release the connection, report a failure. */
		private void end() {
			ended = true;
			finish(process, path, hostname, false);
		}

		@Override
		public void close() {
			ended = true;
			block = new byte[0];
			position = 0;
			process.close();
		}
	}
}
