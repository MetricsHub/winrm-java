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
import java.io.Reader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;

/**
 * A running remote command, created by {@link CommandRequest#start()} — the streaming counterpart
 * of {@link CommandRequest#execute()}, shaped like {@link java.lang.Process}. Output can be
 * consumed while the command is still running:
 *
 * <pre>{@code
 * try (RemoteProcess process = client.command("wevtutil qe System /f:text").start()) {
 * 	try (BufferedReader out = process.stdout()) {
 * 		out.lines().forEach(System.out::println);
 * 	}
 * 	int exitCode = process.waitFor();
 * }
 * }</pre>
 * <p>
 * <b>Lifecycle.</b> The process owns the client's serial connection until the command completes or
 * the process is closed: other operations on the same client block in the meantime (the same
 * contract as a JDBC {@code ResultSet} on its connection). Closing before completion sends the
 * WinRM terminate Signal, which stops the remote command. Always close the process — use
 * try-with-resources. Closing the readers returned by {@link #stdout()}/{@link #stderr()} does
 * <i>not</i> close the process.
 * <p>
 * <b>Reading.</b> Both channels are fed by the same WSMan Receive loop: reading either channel (or
 * calling {@link #waitFor()}) advances the loop, and output that arrives for the channel not being
 * read is buffered in memory until it is read — so memory is bounded by the unread channel, not by
 * the total output. Output is decoded incrementally with the request's charset; a multibyte
 * character split across protocol chunks is decoded correctly.
 * <p>
 * <b>Timeout.</b> The request timeout acts as an <i>inactivity</i> timeout: the longest silence
 * tolerated from the server between two responses, not an overall deadline — a command may run
 * (and stream) far longer than the timeout as long as it keeps producing output. Reads and waits
 * throw {@link WinRMTimeoutException} when the command stays silent for a whole timeout; use
 * {@link #waitFor(Duration)} for an overall deadline.
 * <p>
 * <b>Threading.</b> A process is not thread-safe: read, wait and close from one thread at a time.
 * <p>
 * Failures during consumption are reported through the unchecked
 * {@link org.metricshub.winrm.exceptions.WinRMClientException} hierarchy, including from the
 * readers' {@code read()} methods.
 */
public final class RemoteProcess implements AutoCloseable {

	private final CommandCursor cursor;
	private final String hostname;
	private final Duration timeout;

	private final ChunkDecoder stdoutDecoder;
	private final ChunkDecoder stderrDecoder;

	// Decoded output that has arrived but has not been read yet, per channel.
	private final StringBuilder stdoutPending = new StringBuilder();
	private final StringBuilder stderrPending = new StringBuilder();

	private final BufferedReader stdout;
	private final BufferedReader stderr;

	// finished = no more protocol fetches may happen: the command completed OR the process was
	// closed early. exitCode is non-null only when completion was actually observed.
	private boolean finished;
	private Integer exitCode;

	RemoteProcess(final CommandCursor cursor, final Charset charset, final String hostname, final Duration timeout) {
		this.cursor = cursor;
		this.hostname = hostname;
		this.timeout = timeout;
		this.stdoutDecoder = new ChunkDecoder(charset);
		this.stderrDecoder = new ChunkDecoder(charset);
		this.stdout = new BufferedReader(new ChannelReader(stdoutPending));
		this.stderr = new BufferedReader(new ChannelReader(stderrPending));
	}

	/**
	 * Get the standard output of the remote command, decoded incrementally: lines can be read
	 * while the command is still running. Always the same reader instance; closing it does not
	 * affect the process.
	 *
	 * @return the standard output reader
	 */
	public BufferedReader stdout() {
		return stdout;
	}

	/**
	 * Get the standard error of the remote command, decoded incrementally: lines can be read
	 * while the command is still running. Always the same reader instance; closing it does not
	 * affect the process.
	 *
	 * @return the standard error reader
	 */
	public BufferedReader stderr() {
		return stderr;
	}

	/**
	 * Wait for the command to complete, buffering any unread output in the meantime (read it
	 * afterward from {@link #stdout()}/{@link #stderr()}).
	 *
	 * @return the command's exit code
	 * @throws WinRMTimeoutException when the command stays silent for a whole inactivity timeout
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public synchronized int waitFor() {
		while (!finished) {
			fetchOnce();
		}
		return exitCodeValue();
	}

	/**
	 * Wait at most the given duration for the command to complete — an overall deadline, on top of
	 * the per-response inactivity timeout. The remaining wait is a hard bound on the active
	 * protocol round trip: the server is asked to answer early enough for its reply to arrive
	 * within it, and a wait too short for any network round trip is waited out locally without
	 * touching the wire. Expiry does not affect the command: it keeps running, the process stays
	 * fully usable, and the caller decides whether to keep waiting or {@link #close()}.
	 *
	 * @param deadline how long to wait (at least one millisecond)
	 * @return {@code true} when the command completed within the given duration — the exit code is
	 *         then available from {@link #exitCode()} — {@code false} when the wait expired first
	 * @throws WinRMTimeoutException when the server does not answer the bounded requests within
	 *         the remaining wait — a peer that stopped answering cannot hold the wait past its
	 *         deadline
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public synchronized boolean waitFor(final Duration deadline) {
		WinRMClient.checkPositive(deadline, "deadline");
		final long deadlineMillis = WinRMClient.toMillis(deadline);
		final long start = Utils.getCurrentTimeMillis();
		long remaining = deadlineMillis;
		while (!finished && remaining > 0) {
			absorb(advance(remaining));
			remaining = deadlineMillis - (Utils.getCurrentTimeMillis() - start);
		}
		if (finished && exitCode == null) {
			throw new IllegalStateException("The process was closed before the command completed.");
		}
		return finished;
	}

	/**
	 * Get the exit code of the completed command.
	 *
	 * @return the exit code
	 * @throws IllegalStateException when the command has not completed yet — wait for completion
	 *         with {@link #waitFor()}, or read the output streams to their end first — or when the
	 *         process was closed before the command completed
	 */
	public synchronized int exitCode() {
		return exitCodeValue();
	}

	/**
	 * Terminate the command (when it is still running) and release the client's connection.
	 * Idempotent; a no-op when the command already completed. Output buffered before the close
	 * remains readable, then the readers report end of stream; {@link #waitFor()} and
	 * {@link #exitCode()} throw {@link IllegalStateException} when the close preceded completion —
	 * a terminated command has no exit code.
	 */
	@Override
	public synchronized void close() {
		if (!finished) {
			// No protocol fetch may happen after the close: the cursor signals the command and
			// releases the connection, so this handle must never touch it again. The decoders are
			// flushed so a trailing partial character surfaces (as a replacement) instead of vanishing.
			finished = true;
			try {
				// The command may in fact have completed (its final chunk was received) without this
				// handle having observed the end-of-stream fetch: the exit code is then already known.
				exitCode = cursor.exitCode();
			} catch (final IllegalStateException ignored) {
				// Genuinely closed before completion: there is no exit code.
			}
			stdoutPending.append(stdoutDecoder.finish());
			stderrPending.append(stderrDecoder.finish());
		}
		cursor.close();
	}

	/** The observed exit code, or the explanation of why there is none. */
	private int exitCodeValue() {
		if (exitCode == null) {
			throw new IllegalStateException(
				finished ? "The process was closed before the command completed." : "The command has not completed yet."
			);
		}
		return exitCode;
	}

	/** One Receive round trip: decode what arrived into the per-channel buffers. Holds the monitor. */
	private void fetchOnce() {
		absorb(advance(-1));
	}

	/**
	 * One protocol round trip: unbounded ({@code maxWaitMillis < 0}, the inactivity timeout
	 * governs) or bounded to the given wait (a deadline-driven poll whose expiry is an empty
	 * chunk, not a failure). Holds the monitor.
	 */
	private CommandCursor.Chunk advance(final long maxWaitMillis) {
		try {
			return maxWaitMillis < 0 ? cursor.next() : cursor.poll(maxWaitMillis);
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("Command produced no output within %s on %s", timeout, hostname),
				e
			);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
	}

	/** Absorb one round trip's outcome into the process state. Holds the monitor. */
	private void absorb(final CommandCursor.Chunk chunk) {
		if (chunk == null) {
			finished = true;
			exitCode = cursor.exitCode();
			stdoutPending.append(stdoutDecoder.finish());
			stderrPending.append(stderrDecoder.finish());
		} else {
			stdoutPending.append(stdoutDecoder.decode(chunk.stdout()));
			stderrPending.append(stderrDecoder.decode(chunk.stderr()));
		}
	}

	/** Serve a read from the channel's buffer, advancing the Receive loop while it is empty. */
	private synchronized int read(final StringBuilder pending, final char[] cbuf, final int off, final int len) {
		while (pending.length() == 0 && !finished) {
			fetchOnce();
		}
		if (pending.length() == 0) {
			return -1;
		}
		final int count = Math.min(len, pending.length());
		pending.getChars(0, count, cbuf, off);
		pending.delete(0, count);
		return count;
	}

	/**
	 * One output channel as a {@link Reader}. Reading drives the shared Receive loop; whatever
	 * arrives for the other channel in the meantime is buffered there.
	 */
	private final class ChannelReader extends Reader {

		private final StringBuilder pending;

		private ChannelReader(final StringBuilder pending) {
			this.pending = pending;
		}

		@Override
		public int read(final char[] cbuf, final int off, final int len) {
			if (len == 0) {
				return 0;
			}
			return RemoteProcess.this.read(pending, cbuf, off, len);
		}

		@Override
		public void close() {
			// Closing a reader does not close the process: the RemoteProcess owns the lifecycle, so
			// each reader can sit in its own try-with-resources while the process lives on.
		}
	}
}
