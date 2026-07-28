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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
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
 * <b>Writing.</b> {@link #stdin()} feeds the command's standard input: {@code flush()} carries the
 * written text to the host as a WSMan Send, {@code close()} marks the end of input (the remote
 * stdin then reaches EOF). Writes and reads alternate on the caller's thread, exactly like
 * {@link java.lang.Process} pipes — including the classic deadlock, which is the caller's to
 * avoid: blocking on a read while the remote command itself is blocked waiting for input (or the
 * reverse) hangs both sides until the inactivity timeout fires.
 * <p>
 * <b>Threading.</b> A process is not thread-safe: read, write, wait and close from one thread at a
 * time.
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
	private final ChunkEncoder stdinEncoder;

	// Decoded output that has arrived but has not been read yet, per channel.
	private final StringBuilder stdoutPending = new StringBuilder();
	private final StringBuilder stderrPending = new StringBuilder();

	// Written input that has not been flushed to the host yet.
	private final StringBuilder stdinPending = new StringBuilder();

	private final BufferedReader stdout;
	private final BufferedReader stderr;
	private final BufferedWriter stdin;

	// finished = no more protocol fetches may happen: the command completed OR the process was
	// closed early. exitCode is non-null only when completion was actually observed.
	private boolean finished;
	private Integer exitCode;

	// The end-of-input Send was already emitted (or the input was pre-supplied by the request):
	// no further input may be sent.
	private boolean stdinClosed;

	RemoteProcess(
		final CommandCursor cursor,
		final Charset charset,
		final String hostname,
		final Duration timeout,
		final boolean stdinAlreadySupplied
	) {
		this.cursor = cursor;
		this.hostname = hostname;
		this.timeout = timeout;
		this.stdoutDecoder = new ChunkDecoder(charset);
		this.stderrDecoder = new ChunkDecoder(charset);
		this.stdinEncoder = new ChunkEncoder(charset);
		this.stdout = new BufferedReader(new ChannelReader(stdoutPending));
		this.stderr = new BufferedReader(new ChannelReader(stderrPending));
		this.stdin = new BufferedWriter(new StdinWriter());
		this.stdinClosed = stdinAlreadySupplied;
		if (stdinAlreadySupplied) {
			// The writer itself must reject writes immediately, not buffer them into the void:
			// close it for real. No request leaves here — ending an already-ended input is a no-op.
			try {
				stdin.close();
			} catch (final IOException e) {
				// Unreachable: closing the empty writer performs no I/O (see sendStdin).
				throw new UncheckedIOException(e);
			}
		}
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
	 * Get the standard input of the remote command. Written text is buffered locally until
	 * {@code flush()}, which carries it to the host as one WSMan Send (encoded with the request's
	 * charset); {@code close()} sends the final chunk flagged as the end of input, after which the
	 * remote stdin reaches EOF. Always the same writer instance; closing it does not close the
	 * process — but unlike the readers it does talk to the host, so close it before (or via) the
	 * process itself, not after.
	 * <p>
	 * When the request pre-supplied the input ({@code stdin(...)} on the builder), that input was
	 * already delivered in full: this writer is then closed from the start.
	 * <p>
	 * A command that must <i>observe</i> the EOF — a filter like {@code sort} that only acts once
	 * its input ends — needs pipe semantics: declare the interactive input with the no-argument
	 * {@link CommandRequest#stdin()} on the builder. Without it the remote stdin keeps the
	 * historical console semantics, where writes are delivered but the end of input is not.
	 * <p>
	 * Failures while sending are reported through the unchecked
	 * {@link org.metricshub.winrm.exceptions.WinRMClientException} hierarchy; writing after the end
	 * of input or after the command completed throws {@link IllegalStateException}.
	 *
	 * @return the standard input writer
	 */
	@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "The writer IS the API: it feeds the process's standard input, exactly like "
		+
		"Process.getOutputStream()")
	public BufferedWriter stdin() {
		return stdin;
	}

	/**
	 * Interrupt the command the way a console Ctrl+C would — the WSMan {@code ctrl_c} Signal. It
	 * interrupts the command's child process without terminating the command or this process
	 * handle: the process stays fully usable, which is what an interactive session needs. A no-op
	 * once the command has completed or the process is closed.
	 *
	 * @throws WinRMTimeoutException when the server does not answer the Signal in time
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public synchronized void interrupt() {
		if (finished) {
			return;
		}
		try {
			cursor.interrupt();
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("The interrupt Signal was not answered within %s on %s", timeout, hostname),
				e
			);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
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
	 * Advance the stream by <b>at most one</b> bounded protocol round trip: block at most the
	 * given wait for the next chunk of output, buffer whatever arrives for the readers, and report
	 * whether the command has completed. The wait expiring is not a failure — the server answers
	 * with the protocol's "nothing yet" and the process stays fully usable — so a polling consumer
	 * (e.g. an interactive session pump) never trips the inactivity timeout while idle. Unlike
	 * {@link #waitFor(Duration)}, which keeps polling until its deadline, this returns as soon as
	 * the server answers: output that is ready arrives (and can be read) immediately.
	 * <p>
	 * A wait too short for a network round trip — the WSMan service holds a bounded Receive for at
	 * least 500 ms before answering "nothing yet", and the answer needs transit slack on top — is
	 * waited out locally without touching the wire: the stream then does not advance. Use waits of
	 * about a second or more to actually poll the server.
	 *
	 * @param maxWait how long to block at most (at least one millisecond)
	 * @return {@code true} when the command has completed — the exit code is then available from
	 *         {@link #exitCode()} — {@code false} when it is still running
	 * @throws WinRMTimeoutException when the server does not answer the bounded request in time
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public synchronized boolean poll(final Duration maxWait) {
		WinRMClient.checkPositive(maxWait, "maxWait");
		if (!finished) {
			absorb(advance(WinRMClient.toMillis(maxWait)));
		}
		return finished;
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
		if (!finished) {
			try {
				// The last absorbed chunk may have carried completion right as the deadline ran out:
				// the command DID complete within the wait, so report that rather than a spurious
				// expiry. exitCode() answers from local state; the follow-up advance then completes
				// without waiting (its bounded cleanup cannot block on a 1 ms budget).
				cursor.exitCode();
				absorb(advance(1));
			} catch (final IllegalStateException ignored) {
				// Genuinely still running: the wait expired.
			}
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

	/**
	 * Flush the buffered input to the host — one WSMan Send, flagged as the end of input when
	 * {@code end} is set. Holds the monitor. Ending the input is idempotent; a plain flush with
	 * nothing buffered is a no-op. Once the command completed (or the process was closed), ending
	 * the input touches nothing — the cursor already released the connection, and closing a
	 * writer must never turn into a stray request or a failure hiding a known exit code (input it
	 * still held is discarded, like a {@link java.lang.Process} pipe's) — while flushing actual
	 * input is refused: it can no longer be delivered.
	 */
	private synchronized void sendStdin(final boolean end) {
		if (stdinClosed) {
			if (end) {
				return;
			}
			throw new IllegalStateException("The command's standard input has already been closed.");
		}
		// Stateful encoding: a charset mark is emitted once (not per flush) and a surrogate pair
		// split across two flushes is withheld until complete, exactly as one whole-string encode.
		final byte[] bytes = stdinEncoder.encode(stdinPending.toString(), end);
		stdinPending.setLength(0);
		if (finished) {
			if (end) {
				stdinClosed = true;
				return;
			}
			if (bytes.length == 0) {
				return;
			}
			throw new IllegalStateException("The command has completed: its standard input is closed.");
		}
		if (bytes.length == 0 && !end) {
			return;
		}
		if (end) {
			stdinClosed = true;
		}
		try {
			cursor.send(bytes, end);
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("The command input was not accepted within %s on %s", timeout, hostname),
				e
			);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
	}

	/** Buffer written input until the next flush. Holds the monitor. */
	private synchronized void bufferStdin(final char[] cbuf, final int off, final int len) {
		if (stdinClosed) {
			throw new IllegalStateException("The command's standard input has already been closed.");
		}
		stdinPending.append(cbuf, off, len);
	}

	/** Whether the channel's buffer holds decoded output ready to be read without a round trip. */
	private synchronized boolean hasPending(final StringBuilder pending) {
		return pending.length() > 0;
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
		public boolean ready() {
			// Buffered output can be read without a protocol round trip. This is what lets a polling
			// consumer (e.g. the CLI's interactive shell pump) drain everything a bounded wait
			// absorbed without ever blocking on the wire.
			return RemoteProcess.this.hasPending(pending);
		}

		@Override
		public void close() {
			// Closing a reader does not close the process: the RemoteProcess owns the lifecycle, so
			// each reader can sit in its own try-with-resources while the process lives on.
		}
	}

	/**
	 * The command's standard input as a {@link Writer}: writes buffer locally, {@code flush()}
	 * emits one WSMan Send, {@code close()} emits the final Send flagged as the end of input.
	 */
	private final class StdinWriter extends Writer {

		@Override
		public void write(final char[] cbuf, final int off, final int len) {
			bufferStdin(cbuf, off, len);
		}

		@Override
		public void flush() {
			sendStdin(false);
		}

		@Override
		public void close() {
			sendStdin(true);
		}
	}
}
