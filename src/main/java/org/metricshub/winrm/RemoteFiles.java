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
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.metricshub.winrm.exceptions.WinRMClientException;

/**
 * The remote file access primitive: small PowerShell scripts that open a remote file and write
 * what the caller asked for as <b>base64 lines</b> on stdout, and the {@link InputStream} that
 * decodes those lines as the output chunks arrive.
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
	 * {@code 0x80070020}, 32; a byte-range lock violation {@code 0x80070021}, 33), then open the
	 * file with a share mode that tolerates other writers ({@code ReadWrite}) — a log written by a
	 * running service is the most common thing to read, and {@code File.OpenRead} fails on it.
	 * {@code exit} in a function exits the whole script. {@code %s} is the base64 of the path's
	 * UTF-8 bytes.
	 */
	private static final String OPEN_FILE = "if($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage'){exit 5};"
		+
		"$ErrorActionPreference='Stop';" +
		"function fail($e){if($e.InnerException){$e=$e.InnerException};" +
		"[Console]::Error.WriteLine($e.Message);" +
		"if($e -is [IO.FileNotFoundException] -or $e -is [IO.DirectoryNotFoundException]){exit 2};" +
		"if($e -is [UnauthorizedAccessException]){exit 4};" +
		"$h=[Runtime.InteropServices.Marshal]::GetHRForException($e) -band 0xFFFF;" +
		"if($h -eq 32 -or $h -eq 33){exit 3};" +
		"exit 1};" +
		"$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s'));" +
		"if([IO.Directory]::Exists($p)){exit 6};" +
		"try{$f=[IO.File]::Open($p,'Open','Read','ReadWrite')}catch{fail $_.Exception};";

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
		return String.format(OPEN_FILE, Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)));
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
		final RemoteProcess process = client.powerShell(script).timeout(timeout).start();
		try {
			return new DecodingStream(process, path, client.hostname());
		} catch (final RuntimeException e) {
			process.close();
			throw e;
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
			return new WinRMClientException(String.format("Remote file not found on %s: %s", hostname, path));
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
		case EXIT_IS_DIRECTORY:
			return new WinRMClientException(String.format("Remote path %s on %s is a directory, not a file", path, hostname));
		case EXIT_COMMAND_NOT_FOUND:
			return new WinRMClientException(
				String.format("PowerShell is not available on %s: remote file access needs powershell.exe", hostname)
			);
		default:
			return new WinRMClientException(
				String.format(
					"Failed to read remote file %s on %s (exit code %d)%s",
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
				final String line = readLine();
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

		private String readLine() {
			try {
				return stdout.readLine();
			} catch (final IOException e) {
				// Unreachable: the reader reports failures unchecked (see RemoteProcess).
				throw new WinRMClientException(e.getMessage(), e);
			}
		}

		/** The output ended: collect the exit code, release the connection, report a failure. */
		private void end() {
			ended = true;
			final int exitCode = process.waitFor();
			final String stderr = exitCode == 0 ? "" : process.stderr().lines().collect(Collectors.joining("\n"));
			process.close();
			if (exitCode != 0) {
				throw failure(exitCode, path, hostname, stderr);
			}
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
