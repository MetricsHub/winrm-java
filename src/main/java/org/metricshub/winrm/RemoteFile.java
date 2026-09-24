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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;

/**
 * A file or directory on the remote host, obtained with {@link WinRMClient#file(String)}: read a
 * file's content — whole, as a byte range, or as a stream — or compute its digest, get its
 * properties ({@link #info()}, {@link #exists()}), or {@link #list()} a directory. Nothing is sent
 * until a terminal ({@link #readBytes()}, {@link #readText(Charset)}, {@link #openStream()},
 * {@link #openReader(Charset)}, {@link #digest(String)}, {@link #info()}, {@link #exists()}) is
 * called.
 *
 * <pre>{@code
 * byte[] content = client.file("C:\\Windows\\Temp\\collect.bin").readBytes();
 *
 * // The last 8 KiB of a log: one seek, no scan
 * String tail = client.file("D:\\logs\\huge.log").offset(-8192).readText(StandardCharsets.UTF_8);
 *
 * try (BufferedReader reader = client.file("D:\\logs\\huge.log").openReader(StandardCharsets.UTF_8)) {
 * 	reader.lines().filter(l -> l.contains("ERROR")).forEach(System.out::println);
 * }
 * }</pre>
 * <p>
 * The content travels through the WinRM connection itself (no SMB, no extra port): a small
 * PowerShell script on the host writes the bytes base64-encoded, which is binary-safe and
 * independent of the remote console code page. It is designed for configuration files, logs and
 * small data files — <b>not a bulk transport</b>: base64 through a command shell is far slower
 * than SMB. The host needs PowerShell (2.0 or later) in {@code FullLanguage} mode.
 * <p>
 * The file is opened with a share mode that tolerates other writers, so a log being written by a
 * running service can be read; a file held with an exclusive lock (e.g. {@code pagefile.sys})
 * fails with a sharing violation. A read writes nothing on the host: its script travels on the
 * command line, which limits the path to about 1,450 characters (fewer with non-Latin
 * characters) — a longer path fails before anything is sent. Ranges are <b>byte</b> ranges, and
 * reads are not snapshots: a
 * file that grows or shrinks between two reads is read as it is at each read.
 * <p>
 * A request is not thread-safe; configure it and call its terminals from one thread.
 */
public final class RemoteFile {

	/** Default cap of {@link #readBytes()} and {@link #readText(Charset)}: 64 MiB. */
	public static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

	/** The largest byte array the JVM reliably allocates. */
	private static final long MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8L;

	private final WinRMClient client;
	private final String path;
	private long offset;
	private long length = -1;
	private long maxBytes = DEFAULT_MAX_BYTES;
	private Duration timeout;

	/**
	 * Create the request.
	 *
	 * @param client the client the file is read through
	 * @param path the path of the file on the remote host
	 */
	RemoteFile(final WinRMClient client, final String path) {
		Utils.checkNonBlank(path, "path");
		this.client = client;
		this.path = path;
		this.timeout = client.defaultTimeout();
	}

	/**
	 * Get the path of the file on the remote host.
	 *
	 * @return the path, as given to {@link WinRMClient#file(String)}
	 */
	public String path() {
		return path;
	}

	/**
	 * Start reading at the given byte position instead of the beginning of the file. A
	 * <b>negative</b> offset counts from the end: {@code offset(-n)} starts at
	 * {@code max(0, size - n)}, the size being read by the same remote invocation that seeks (so a
	 * growing log is tailed from its current end); a file shorter than {@code n} is read whole. An
	 * offset past the end of the file reads nothing — it is not an error. Applies to every read
	 * terminal, not to {@link #digest(String)}.
	 *
	 * @param offset the byte position; negative counts from the end of the file
	 * @return this request
	 */
	public RemoteFile offset(final long offset) {
		this.offset = offset;
		return this;
	}

	/**
	 * Read at most the given number of bytes (from the {@link #offset(long)}, once resolved)
	 * instead of reading to the end of the file. A length beyond the end of the file reads what
	 * exists; a zero length reads nothing. Applies to every read terminal, not to
	 * {@link #digest(String)}.
	 *
	 * @param length how many bytes to read at most (zero or more)
	 * @return this request
	 * @throws IllegalArgumentException when the length is negative
	 */
	public RemoteFile length(final long length) {
		if (length < 0) {
			throw new IllegalArgumentException("length must not be negative.");
		}
		this.length = length;
		return this;
	}

	/**
	 * Set the size cap of {@link #readBytes()} and {@link #readText(Charset)}, which hold the whole
	 * content in memory: reading more than the cap fails instead of risking an
	 * {@link OutOfMemoryError}. Defaults to {@link #DEFAULT_MAX_BYTES} (64 MiB). The cap is also
	 * sent to the host, so no more than one byte past it is ever transferred. For larger content,
	 * use {@link #openStream()}, which reads with bounded memory and has no cap.
	 *
	 * @param maxBytes the largest content accepted, in bytes
	 * @return this request
	 * @throws IllegalArgumentException when the cap is negative or exceeds the largest possible
	 *         byte array
	 */
	public RemoteFile maxBytes(final long maxBytes) {
		if (maxBytes < 0 || maxBytes >= MAX_ARRAY_SIZE) {
			throw new IllegalArgumentException("maxBytes must be between 0 and " + (MAX_ARRAY_SIZE - 1) + ".");
		}
		this.maxBytes = maxBytes;
		return this;
	}

	/**
	 * Override the client's timeout for this request. For the blocking terminals
	 * ({@link #readBytes()}, {@link #readText(Charset)}, {@link #digest(String)}) it is a
	 * wall-clock deadline for the whole read; for {@link #openStream()} and
	 * {@link #openReader(Charset)} it is an <i>inactivity</i> timeout, the longest silence
	 * tolerated from the host between two chunks of content.
	 *
	 * @param timeout the timeout (at least one millisecond)
	 * @return this request
	 */
	public RemoteFile timeout(final Duration timeout) {
		this.timeout = WinRMClient.checkPositive(timeout, "timeout");
		return this;
	}

	/**
	 * Read the content (the whole file, or the configured range) into memory, byte-exact: nothing
	 * is converted, and a byte order mark is kept.
	 *
	 * @return the content
	 * @throws WinRMClientException when the content exceeds {@link #maxBytes(long)}, or the file
	 *         cannot be read (not found, a directory, access denied, sharing violation, PowerShell
	 *         unavailable or constrained)
	 * @throws WinRMTimeoutException when the timeout elapses first
	 */
	public byte[] readBytes() {
		final long requested = length < 0 ? maxBytes + 1 : Math.min(length, maxBytes + 1);
		return blocking(() -> {
			try (InputStream in = open(requested)) {
				final byte[] content = in.readNBytes((int) requested);
				if (content.length > maxBytes) {
					throw new WinRMClientException(
						String.format(
							"Remote file %s on %s exceeds the %d-byte cap of readBytes(): raise maxBytes(...), read a range, or use openStream()",
							path,
							client.hostname(),
							maxBytes
						)
					);
				}
				// A read that filled exactly the requested length stops short of the end of the
				// stream: drain it (the host sends nothing past the length) so the script's exit code
				// is still checked and a late failure is not reported as a successful read.
				in.transferTo(OutputStream.nullOutputStream());
				return content;
			}
		});
	}

	/**
	 * Read the content (the whole file, or the configured range) into memory and decode it with the
	 * given charset — no guessing, no default. This method strips nothing: a byte order mark is
	 * handled exactly as the given charset's decoder handles it — {@code UTF-8} keeps it (as
	 * {@code U+FEFF}), while {@code UTF-16} consumes it to detect the byte order. A range boundary
	 * may split a multibyte character, which then
	 * decodes to {@code U+FFFD} at the edges, like any malformed input.
	 *
	 * @param charset the charset the file is encoded with
	 * @return the decoded content
	 * @throws WinRMClientException when the content exceeds {@link #maxBytes(long)}, or the file
	 *         cannot be read
	 * @throws WinRMTimeoutException when the timeout elapses first
	 */
	public String readText(final Charset charset) {
		Utils.checkNonNull(charset, "charset");
		return new String(readBytes(), charset);
	}

	/**
	 * Open the content (the whole file, or the configured range) as a stream, decoded as it
	 * arrives: memory is bounded by one transfer block, not by the file, and there is no size cap.
	 * The file is opened before this method returns, so a file that cannot be read fails here; a
	 * failure midway is reported by {@code read()} (never as a silently short read).
	 * <p>
	 * <b>The stream must be closed</b> — use try-with-resources. It holds the client's connection
	 * until it reaches its end or is closed; closing it early stops the remote read. Failures are
	 * reported through the unchecked {@link WinRMClientException} hierarchy, including from
	 * {@code read()}. The timeout is an inactivity timeout (see {@link #timeout(Duration)}).
	 *
	 * @return the content stream, to use with try-with-resources
	 * @throws WinRMClientException when the file cannot be read
	 * @throws WinRMTimeoutException when the host does not answer in time
	 */
	public InputStream openStream() {
		return open(length);
	}

	/**
	 * Open the content (the whole file, or the configured range) as a character stream decoded
	 * with the given charset: {@link #openStream()} behind an {@link InputStreamReader}, with the
	 * same lifecycle — <b>close it</b>. Like {@link #readText(Charset)}, it strips nothing: a byte
	 * order mark is handled as the given charset's decoder handles it.
	 *
	 * @param charset the charset the file is encoded with
	 * @return the reader, to use with try-with-resources
	 * @throws WinRMClientException when the file cannot be read
	 * @throws WinRMTimeoutException when the host does not answer in time
	 */
	public BufferedReader openReader(final Charset charset) {
		Utils.checkNonNull(charset, "charset");
		return new BufferedReader(new InputStreamReader(openStream(), charset));
	}

	/**
	 * Compute the digest of the whole file on the host — nothing but the digest is transferred.
	 * The {@link #offset(long)} and {@link #length(long)} settings do not apply.
	 *
	 * @param algorithm {@code MD5}, {@code SHA1}, {@code SHA256}, {@code SHA384} or {@code SHA512}
	 * @return the digest, as lowercase hexadecimal
	 * @throws IllegalArgumentException when the algorithm is not supported
	 * @throws WinRMClientException when the file cannot be read
	 * @throws WinRMTimeoutException when the timeout elapses first
	 */
	public String digest(final String algorithm) {
		Utils.checkNonNull(algorithm, "algorithm");
		final String script = RemoteFiles.digestScript(path, algorithm);
		return blocking(() -> {
			try (InputStream in = RemoteFiles.open(client, path, script, timeout)) {
				final StringBuilder hex = new StringBuilder();
				for (final byte b : in.readAllBytes()) {
					hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
				}
				return hex.toString();
			}
		});
	}

	/**
	 * Get the properties of the file or directory: size, timestamps, attributes. A path that does
	 * not exist is not an error: the result is empty. The {@link #offset(long)} and
	 * {@link #length(long)} settings do not apply.
	 *
	 * @return the properties, or empty when the path does not exist
	 * @throws WinRMClientException when the properties cannot be read (access denied, invalid
	 *         path, PowerShell unavailable or constrained)
	 * @throws WinRMTimeoutException when the timeout elapses first
	 */
	public Optional<RemoteFileInfo> info() {
		final String script = RemoteFiles.infoScript(path);
		return blocking(() -> {
			final RemoteProcess process = RemoteFiles.start(client, path, script, timeout);
			try {
				final RemoteFileInfo info = process
					.stdout()
					.lines()
					.filter(line -> !line.isBlank())
					.map(line -> RemoteFiles.parseEntry(line.strip()))
					.reduce((first, last) -> last)
					.orElse(null);
				return RemoteFiles.finish(process, path, client.hostname(), true) == 0
					? Optional.ofNullable(info)
					: Optional.<RemoteFileInfo>empty();
			} finally {
				process.close();
			}
		});
	}

	/**
	 * Tell whether the file or directory exists: {@code info().isPresent()}.
	 *
	 * @return {@code true} when the path exists
	 * @throws WinRMClientException when the path cannot be checked (e.g. access denied)
	 * @throws WinRMTimeoutException when the timeout elapses first
	 */
	public boolean exists() {
		return info().isPresent();
	}

	/**
	 * Prepare the listing of this path, a directory: set its filters, then call
	 * {@link RemoteDirectoryListing#execute()} or {@link RemoteDirectoryListing#stream()}. The
	 * timeout of this request, when set, carries over.
	 *
	 * <pre>{@code
	 * RemoteFileList logs = client.file("C:\\inetpub\\logs").list().glob("*.log").recursive().execute();
	 * }</pre>
	 *
	 * @return the listing request
	 */
	public RemoteDirectoryListing list() {
		return new RemoteDirectoryListing(client, path).timeout(timeout);
	}

	/** Start the remote read of the configured offset and the given length ({@code -1}: to the end). */
	private InputStream open(final long readLength) {
		return RemoteFiles.open(client, path, RemoteFiles.readScript(path, offset, readLength), timeout);
	}

	private <T> T blocking(final Callable<T> task) {
		return RemoteFiles.blocking(client, path, timeout, task);
	}
}
