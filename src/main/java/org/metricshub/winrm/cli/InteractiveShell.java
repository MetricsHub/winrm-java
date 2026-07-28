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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.metricshub.winrm.RemoteProcess;

/**
 * The protocol pump behind the CLI's {@code shell} subcommand: bridges a {@link RemoteProcess}
 * (typically a remote {@code cmd.exe}) to local streams until the remote shell exits, and returns
 * its exit code.
 * <p>
 * The pump is <b>single-threaded on the wire</b> — the WinRM connection stays strictly serial.
 * Each round of the loop forwards the queued local input (one WSMan Send), polls the remote
 * output for a short bounded wait, and writes whatever arrived to the local streams; the poll
 * cadence is the interaction latency. An idle session never trips the inactivity timeout: every
 * poll round trip completes with output or the protocol's "nothing yet" answer.
 * <p>
 * The only helper thread reads the local input into a queue, because a blocking read of
 * {@code System.in} must not stall the pump; it never touches the connection. Input is
 * <b>line-oriented</b> (like {@code winrs}): lines are forwarded once the local terminal hands
 * them out, i.e. after Enter.
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
	 * input can queue lines much faster than the wire consumes them: the bound keeps the local
	 * buffering flat and, above all, keeps the rounds alternating — a fire hose of input must not
	 * starve the output side of the pump.
	 */
	static final int MAX_INPUT_CHARS_PER_ROUND = 32 * 1024;

	/**
	 * How many lines the local reader thread may queue ahead of the pump before it blocks —
	 * backpressure toward the local stdin, so a redirected file streams through bounded memory
	 * instead of being swallowed whole.
	 */
	private static final int INPUT_QUEUE_CAPACITY = 1_024;

	private InteractiveShell() {}

	/**
	 * The local input lines available to the pump, without ever blocking it. Seam between the
	 * pump's deterministic protocol loop and the helper thread that feeds it in production.
	 */
	interface LineSource {
		/**
		 * @return the next queued local input line (without its line terminator), or {@code null}
		 *         when none is available right now
		 */
		String nextLine();

		/**
		 * @return whether the local input reached its end and every queued line was consumed
		 */
		boolean endOfInput();
	}

	/**
	 * Bridge the process to the given local streams until the remote command exits.
	 *
	 * @param process the running remote shell
	 * @param localInput the local input to forward, read line by line on a helper thread
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
		final QueuedLineSource lines = new QueuedLineSource();
		final Thread reader = new Thread(() -> lines.readAll(localInput), "winrm-shell-stdin");
		reader.setDaemon(true);
		reader.start();
		return bridge(process, lines, out, err, interruptRequested, pollMillis);
	}

	/**
	 * The pump itself, deterministic given its inputs: forward queued local input (one Send per
	 * round at most), poll the remote output for a short bounded wait, forward whatever arrived to
	 * the local streams — until the remote command completes.
	 *
	 * @param process the running remote shell
	 * @param lines the local input lines
	 * @param out where the remote standard output goes
	 * @param err where the remote standard error goes
	 * @param interruptRequested checked (and cleared) every round; forwards a {@code ctrl_c} Signal
	 * @param pollMillis the poll cadence in milliseconds
	 * @return the remote command's exit code
	 * @throws IOException when forwarding the local input fails
	 */
	static int bridge(
		final RemoteProcess process,
		final LineSource lines,
		final PrintStream out,
		final PrintStream err,
		final AtomicBoolean interruptRequested,
		final long pollMillis
	) throws IOException {
		// The tail of a record the current round could not fit entirely (an oversized line):
		// forwarded first on the next rounds, before any new line is pulled.
		final StringBuilder pendingInput = new StringBuilder();
		boolean eofForwarded = false;
		boolean completed = false;
		while (!completed) {
			if (interruptRequested.getAndSet(false)) {
				process.interrupt();
			}
			if (!eofForwarded) {
				try {
					eofForwarded = forwardLocalInput(lines, process.stdin(), pendingInput);
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
	 * round — as one flushed write (one WSMan Send). Lines are terminated with CRLF — what the
	 * remote {@code cmd.exe} console expects — and a record longer than one round's budget is
	 * split: the surplus stays in {@code pendingInput} and leads the next round, so even a giant
	 * newline-free record keeps the rounds (and the output side) alternating. Once the local
	 * input ends and every pending character is out, the remote stdin is closed (the final Send
	 * carries {@code End="true"}) and {@code true} is returned: no further input will be
	 * forwarded.
	 */
	private static boolean forwardLocalInput(
		final LineSource lines,
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
			final String line = lines.nextLine();
			if (line == null) {
				break;
			}
			pendingInput.append(line).append("\r\n");
		}
		if (pendingInput.length() == 0 && lines.endOfInput()) {
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
	 * The production {@link LineSource}: a queue fed by the helper thread reading the local
	 * standard input, drained by the pump without ever blocking.
	 */
	static final class QueuedLineSource implements LineSource {

		/** One queued item: a line, or the end of the local input when {@code line} is null. */
		private static final class Item {

			private final String line;

			private Item(final String line) {
				this.line = line;
			}
		}

		private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>(INPUT_QUEUE_CAPACITY);
		private boolean ended;

		/** Feed the queue from the local input, line by line, ending with the EOF marker. */
		void readAll(final InputStream localInput) {
			// The local console charset: what the terminal actually feeds System.in with. The
			// reader is deliberately never closed — the stream is the caller's (System.in).
			final BufferedReader reader = new BufferedReader(
				new InputStreamReader(localInput, Charset.defaultCharset())
			);
			try {
				String line;
				while ((line = reader.readLine()) != null) {
					queue.put(new Item(line));
				}
				queue.put(new Item(null));
			} catch (final IOException e) {
				// The local input died: treat it as its end.
				putSilently(new Item(null));
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		private void putSilently(final Item item) {
			try {
				queue.put(item);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public String nextLine() {
			if (ended) {
				return null;
			}
			final Item item = queue.poll();
			if (item == null) {
				return null;
			}
			if (item.line == null) {
				ended = true;
				return null;
			}
			return item.line;
		}

		@Override
		public boolean endOfInput() {
			return ended;
		}
	}
}
