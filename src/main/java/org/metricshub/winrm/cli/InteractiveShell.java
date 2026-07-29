package org.metricshub.winrm.cli;

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
import java.io.BufferedWriter;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.metricshub.winrm.RemoteProcess;

/**
 * The protocol pump behind the CLI's {@code shell} subcommand: bridges a {@link RemoteProcess}
 * (typically a remote {@code cmd.exe}) to local streams until the remote shell exits, and returns
 * its exit code.
 * <p>
 * The pump is <b>single-threaded on the wire</b> — the WinRM connection stays strictly serial.
 * Each round of the loop forwards the queued local input (one WSMan Send, bounded per round),
 * polls the remote output for a short bounded wait, and writes whatever arrived to the local
 * streams; the poll cadence is the interaction latency. An idle session never trips the
 * inactivity timeout: every poll round trip completes with output or the protocol's "nothing
 * yet" answer.
 * <p>
 * The only helper thread reads the local input into a bounded queue, because a blocking read of
 * {@code System.in} must not stall the pump; it never touches the connection. Input is
 * <b>line-oriented</b> (like {@code winrs}): a terminal hands lines out after Enter, and they
 * are forwarded CRLF-terminated. Redirected input flows through the same path in bounded pieces,
 * so even a giant newline-free record streams through flat memory.
 * <p>
 * Local end of input (Ctrl+Z then Enter on Windows, Ctrl+D elsewhere) closes the remote stdin —
 * the final Send is flagged {@code End} — after which {@code cmd.exe} exits on its own. Setting
 * the interrupt flag (wired to Ctrl+C by the CLI) forwards a WSMan {@code ctrl_c} Signal, which
 * interrupts the remote child process without ending the session.
 */
final class InteractiveShell {

	/**
	 * Poll cadence of the CLI session. It paces the IDLE rounds only — a poll returns as soon as
	 * the server has output, and the poll following a forwarded line is issued immediately — so
	 * typed input echoes back with sub-second latency while idle rounds stay cheap. The cadence
	 * cannot be lower: the WSMan service holds a bounded Receive for at least 500 ms (its
	 * OperationTimeout floor), and the poll needs transit slack on top.
	 */
	static final long DEFAULT_POLL_MILLIS = 1_000L;

	/**
	 * How much input one round forwards at most (one Send's worth of characters). Redirected
	 * input can queue pieces much faster than the wire consumes them: the bound keeps the local
	 * buffering flat and, above all, keeps the rounds alternating — a fire hose of input must not
	 * starve the output side of the pump.
	 */
	static final int MAX_INPUT_CHARS_PER_ROUND = 32 * 1024;

	/**
	 * Largest single piece the local reader thread queues. A record longer than this (a giant
	 * newline-free payload in a redirected input) is queued in pieces of this size instead of
	 * being materialized whole, so the memory bound holds by CHARACTERS, not by lines.
	 */
	static final int MAX_QUEUED_PIECE_CHARS = 4 * 1024;

	/**
	 * How many pieces the local reader thread may queue ahead of the pump before it blocks —
	 * backpressure toward the local stdin, so a redirected file streams through bounded memory
	 * instead of being swallowed whole.
	 */
	private static final int INPUT_QUEUE_CAPACITY = 256;

	private InteractiveShell() {}

	/**
	 * The local input available to the pump, without ever blocking it. Seam between the pump's
	 * deterministic protocol loop and the helper thread that feeds it in production.
	 */
	interface InputSource {
		/**
		 * @return the next queued piece of local input — line terminators already normalized to
		 *         CRLF and included — or {@code null} when nothing is available right now
		 * @throws IOException when reading the local input failed: the session must fail rather
		 *         than pass the truncated input off as the complete one
		 */
		String nextPiece() throws IOException;

		/**
		 * @return whether the local input reached its end and every queued piece was consumed
		 */
		boolean endOfInput();
	}

	/**
	 * The charset the local standard input is actually encoded with. {@code Charset.defaultCharset()}
	 * is wrong for a Windows console on modern JDKs: the JVM default is UTF-8 there while the
	 * console feeds {@code System.in} with its OEM code page. The JDK exposes the real answer
	 * through {@code stdin.encoding} (recent JDKs), {@code sun.stdin.encoding} (Windows consoles
	 * on older JDKs), {@code Console.charset()} (Java 17+, reached reflectively — this code
	 * targets Java 11), and {@code native.encoding} (Java 18+), tried in that order before
	 * falling back to the process default.
	 */
	static Charset localInputCharset(final UnaryOperator<String> systemProperty, final Console console) {
		for (final String property : new String[] { "stdin.encoding", "sun.stdin.encoding" }) {
			final Charset charset = charsetOf(systemProperty.apply(property));
			if (charset != null) {
				return charset;
			}
		}
		if (console != null) {
			try {
				final Object charset = Console.class.getMethod("charset").invoke(console);
				if (charset instanceof Charset) {
					return (Charset) charset;
				}
			} catch (final ReflectiveOperationException | RuntimeException ignored) {
				// Java 11-16: no Console.charset() — keep probing.
			}
		}
		final Charset nativeCharset = charsetOf(systemProperty.apply("native.encoding"));
		return nativeCharset != null ? nativeCharset : Charset.defaultCharset();
	}

	/** The charset of the given name, or {@code null} when absent, unknown, or unsupported. */
	private static Charset charsetOf(final String name) {
		if (name == null) {
			return null;
		}
		try {
			return Charset.forName(name);
		} catch (final RuntimeException e) {
			return null;
		}
	}

	/**
	 * Bridge the process to the given local streams until the remote command exits.
	 *
	 * @param process the running remote shell
	 * @param localInput the local input to forward, read on a helper thread
	 * @param out where the remote standard output goes
	 * @param err where the remote standard error goes
	 * @param interruptRequested set externally (e.g. by a Ctrl+C handler) to forward a
	 *        {@code ctrl_c} Signal on the next round; cleared once forwarded
	 * @param pollMillis the poll cadence in milliseconds
	 * @return the remote command's exit code
	 * @throws IOException when forwarding the local input fails
	 */
	static int run(
		final RemoteProcess process,
		final InputStream localInput,
		final PrintStream out,
		final PrintStream err,
		final AtomicBoolean interruptRequested,
		final long pollMillis
	) throws IOException {
		final QueuedInputSource pieces = new QueuedInputSource();
		final Thread reader = new Thread(() -> pieces.readAll(localInput), "winrm-shell-stdin");
		reader.setDaemon(true);
		reader.start();
		return bridge(process, pieces, out, err, interruptRequested, pollMillis);
	}

	/**
	 * The pump itself, deterministic given its inputs: forward queued local input (one Send per
	 * round at most), poll the remote output for a short bounded wait, forward whatever arrived to
	 * the local streams — until the remote command completes.
	 *
	 * @param process the running remote shell
	 * @param pieces the local input pieces
	 * @param out where the remote standard output goes
	 * @param err where the remote standard error goes
	 * @param interruptRequested checked (and cleared) every round; forwards a {@code ctrl_c} Signal
	 * @param pollMillis the poll cadence in milliseconds
	 * @return the remote command's exit code
	 * @throws IOException when forwarding the local input fails
	 */
	static int bridge(
		final RemoteProcess process,
		final InputSource pieces,
		final PrintStream out,
		final PrintStream err,
		final AtomicBoolean interruptRequested,
		final long pollMillis
	) throws IOException {
		// The tail of a piece the current round could not fit entirely: forwarded first on the
		// next rounds, before any new piece is pulled.
		final StringBuilder pendingInput = new StringBuilder();
		boolean eofForwarded = false;
		boolean completed = false;
		while (!completed) {
			if (interruptRequested.getAndSet(false)) {
				process.interrupt();
			}
			if (!eofForwarded) {
				try {
					eofForwarded = forwardLocalInput(pieces, process.stdin(), pendingInput);
				} catch (final IllegalStateException e) {
					// The command completed while this input was being queued (the final Receive
					// beat it): nothing can consume it anymore. Stop forwarding — the next poll
					// observes the completion and the exit code is reported normally.
					eofForwarded = true;
				}
			}
			// One bounded round trip: it returns as soon as the server answers, so available
			// output is forwarded immediately — the cadence only paces the idle rounds.
			completed = process.poll(Duration.ofMillis(pollMillis));
			forward(process.stdout(), out);
			forward(process.stderr(), err);
		}
		// Completion was observed with output possibly still buffered: drain it to the end.
		forward(process.stdout(), out);
		forward(process.stderr(), err);
		return process.exitCode();
	}

	/**
	 * Forward the queued local input — at most {@link #MAX_INPUT_CHARS_PER_ROUND} characters per
	 * round — as one flushed write (one WSMan Send). A piece exceeding one round's budget is
	 * split: the surplus stays in {@code pendingInput} and leads the next round, so even a giant
	 * record keeps the rounds (and the output side) alternating. Once the local input ends and
	 * every pending character is out, the remote stdin is closed (the final Send carries
	 * {@code End="true"}) and {@code true} is returned: no further input will be forwarded.
	 */
	private static boolean forwardLocalInput(
		final InputSource pieces,
		final BufferedWriter stdin,
		final StringBuilder pendingInput
	) throws IOException {
		int budget = MAX_INPUT_CHARS_PER_ROUND;
		boolean wrote = false;
		while (budget > 0) {
			if (pendingInput.length() > 0) {
				final int take = Math.min(budget, pendingInput.length());
				stdin.write(pendingInput.substring(0, take));
				pendingInput.delete(0, take);
				budget -= take;
				wrote = true;
				continue;
			}
			final String piece = pieces.nextPiece();
			if (piece == null) {
				break;
			}
			pendingInput.append(piece);
		}
		if (pendingInput.length() == 0 && pieces.endOfInput()) {
			// Closing flushes the written characters too: they travel with the End mark, one
			// single Send.
			stdin.close();
			return true;
		}
		if (wrote) {
			stdin.flush();
		}
		return false;
	}

	/** Write everything already buffered for the channel — never a protocol round trip. */
	private static void forward(final BufferedReader channel, final PrintStream target) throws IOException {
		final char[] buffer = new char[4096];
		boolean wrote = false;
		while (channel.ready()) {
			final int read = channel.read(buffer);
			if (read < 0) {
				break;
			}
			target.print(new String(buffer, 0, read));
			wrote = true;
		}
		if (wrote) {
			target.flush();
		}
	}

	/**
	 * The production {@link InputSource}: a bounded queue fed by the helper thread reading the
	 * local standard input, drained by the pump without ever blocking. The reader normalizes
	 * every line ending (LF, CRLF, lone CR) to the CRLF the remote {@code cmd.exe} expects, and
	 * never materializes more than one bounded piece at a time — a record longer than
	 * {@link #MAX_QUEUED_PIECE_CHARS} is queued in slices, so memory stays bounded by characters,
	 * not by lines.
	 */
	static final class QueuedInputSource implements InputSource {

		/**
		 * One queued item: a piece of input, the normal end of the local input ({@code piece} and
		 * {@code failure} both null), or a local read failure to surface to the pump.
		 */
		private static final class Item {

			private final String piece;
			private final IOException failure;

			private Item(final String piece, final IOException failure) {
				this.piece = piece;
				this.failure = failure;
			}
		}

		private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>(INPUT_QUEUE_CAPACITY);
		private boolean ended;

		/**
		 * Feed the queue from the local input, piece by piece, ending with the EOF marker — or
		 * with the read failure itself, so the pump fails the session instead of passing the
		 * truncated input off as the complete one.
		 */
		void readAll(final InputStream localInput) {
			// The local input charset: what the terminal (or the redirection) actually feeds
			// System.in with. The reader is deliberately never closed — the stream is the
			// caller's (System.in).
			final Reader reader = new BufferedReader(
				new InputStreamReader(localInput, localInputCharset(System::getProperty, System.console()))
			);
			final StringBuilder piece = new StringBuilder();
			try {
				boolean skipLoneLineFeed = false;
				int read;
				while ((read = reader.read()) != -1) {
					final char character = (char) read;
					if (skipLoneLineFeed) {
						skipLoneLineFeed = false;
						if (character == '\n') {
							// The LF of a CRLF pair: its CR already emitted the line ending.
							continue;
						}
					}
					if (character == '\n' || character == '\r') {
						skipLoneLineFeed = character == '\r';
						piece.append("\r\n");
						put(piece);
					} else {
						piece.append(character);
						if (piece.length() >= MAX_QUEUED_PIECE_CHARS) {
							put(piece);
						}
					}
				}
				if (piece.length() > 0) {
					put(piece);
				}
				queue.put(new Item(null, null));
			} catch (final IOException e) {
				putSilently(new Item(null, e));
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		private void put(final StringBuilder piece) throws InterruptedException {
			queue.put(new Item(piece.toString(), null));
			piece.setLength(0);
		}

		private void putSilently(final Item item) {
			try {
				queue.put(item);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public String nextPiece() throws IOException {
			if (ended) {
				return null;
			}
			final Item item = queue.poll();
			if (item == null) {
				return null;
			}
			if (item.piece == null) {
				ended = true;
				if (item.failure != null) {
					throw new IOException("Reading the local standard input failed", item.failure);
				}
				return null;
			}
			return item.piece;
		}

		@Override
		public boolean endOfInput() {
			return ended;
		}
	}
}
