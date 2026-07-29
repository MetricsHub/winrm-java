package org.metricshub.winrm.cli;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2026 MetricsHub
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellDeletion;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.sendResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.RemoteProcess;
import org.metricshub.winrm.WinRMClient;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * Headless tests of the interactive {@code shell} pump (issue #136, phase 2) against
 * {@link FakeWsmanServer}, with a scripted {@link InteractiveShell.InputSource} making every round
 * deterministic: input line → Send on the wire, scripted output → local stdout, local EOF →
 * {@code End="true"}, Ctrl+C → {@code ctrl_c} Signal, and exit-code propagation.
 */
class InteractiveShellTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";

	private static final String SHELL_ID = "SHELL-1";
	private static final String COMMAND_ID = "CMD-1";

	private static final long POLL_MILLIS = 2_000L;

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private WinRMClient client() {
		return WinRMClient
			.builder("127.0.0.1")
			.port(server.port())
			.credentials(DOMAIN + "\\" + USER, PASSWORD.toCharArray())
			.timeout(Duration.ofSeconds(10))
			.build();
	}

	private void enqueueStartup() {
		server.enqueue(200, envelope(resourceCreated(SHELL_ID))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
	}

	/** An input source whose {@code nextPiece()} answers are fully scripted, including "not yet". */
	private static final class ScriptedPieces implements InteractiveShell.InputSource {

		private final Deque<String> answers = new ArrayDeque<>();
		private boolean ended;

		/** The given pieces, one per {@code nextPiece()} call, then the end of the local input. */
		private static ScriptedPieces endingAfter(final String... pieces) {
			final ScriptedPieces scripted = new ScriptedPieces();
			for (final String piece : pieces) {
				scripted.answers.addLast(piece);
			}
			scripted.ended = true;
			return scripted;
		}

		/** A local input that never produces anything and never ends (a silent terminal). */
		private static ScriptedPieces silent() {
			return new ScriptedPieces();
		}

		@Override
		public String nextPiece() {
			return answers.pollFirst();
		}

		@Override
		public boolean endOfInput() {
			return ended && answers.isEmpty();
		}
	}

	@Test
	void bridgesInputLinesAndOutputAndPropagatesTheExitCode() throws Exception {
		enqueueStartup();
		server
			// the typed line, CRLF-terminated, traveling WITH the End mark (the input ends after it)
			.enqueue(200, envelope(sendResponse()))
			// the shell answers and exits
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", COMMAND_ID, "MODE PREPARE\r\n".getBytes(StandardCharsets.UTF_8)),
						done(COMMAND_ID, 7)
					)
				)
			)
			// completion cleanup: the bounded terminate Signal
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		final int exitCode;
		try (WinRMClient client = client()) {
			try (RemoteProcess process = client.command("cmd.exe").start()) {
				exitCode = InteractiveShell.bridge(
					process,
					ScriptedPieces.endingAfter("MODE PREPARE\r\n"),
					new PrintStream(stdout, true, "UTF-8"),
					new PrintStream(stderr, true, "UTF-8"),
					new AtomicBoolean(),
					POLL_MILLIS
				);
			}
		}

		assertEquals(7, exitCode);
		assertEquals("MODE PREPARE\r\n", stdout.toString("UTF-8"));
		assertEquals("", stderr.toString("UTF-8"));

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(1, chunks.size());
		assertArrayEquals("MODE PREPARE\r\n".getBytes(StandardCharsets.UTF_8), chunks.get(0).data());
		assertTrue(chunks.get(0).end());
	}

	@Test
	void forwardsCtrlCAsTheCtrlCSignalAndTheSessionSurvivesIt() throws Exception {
		enqueueStartup();
		server
			// the forwarded Ctrl+C
			.enqueue(200, envelope(signalResponse()))
			// the interrupted child returns to the prompt...
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", COMMAND_ID, "^C\r\nC:\\>".getBytes(StandardCharsets.UTF_8)), null))
			)
			// ...and the shell later exits on its own
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		final AtomicBoolean interruptRequested = new AtomicBoolean(true);
		final int exitCode;
		try (WinRMClient client = client()) {
			try (RemoteProcess process = client.command("cmd.exe").start()) {
				exitCode = InteractiveShell.bridge(
					process,
					ScriptedPieces.silent(),
					new PrintStream(stdout, true, "UTF-8"),
					new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
					interruptRequested,
					POLL_MILLIS
				);
			}
		}

		assertEquals(0, exitCode);
		assertFalse(interruptRequested.get(), "the flag must be cleared once forwarded");
		assertTrue(stdout.toString("UTF-8").contains("^C"));

		final List<String> requests = server.decryptedRequests();
		assertTrue(requests.get(2).contains("signal/ctrl_c"), requests.get(2));
		// The session went on after the interrupt: output flowed, and only completion terminated it.
		assertTrue(requests.get(5).contains("signal/terminate"), requests.get(5));
	}

	@Test
	void echoesOutputArrivingBeforeAnyInput() throws Exception {
		enqueueStartup();
		server
			// round 1: no local input yet — the poll picks up the shell banner
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", COMMAND_ID, "Microsoft Windows\r\nC:\\>".getBytes(StandardCharsets.UTF_8)),
						null
					)
				)
			)
			// round 2: the user typed "exit"
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		// Nothing on the first nextLine() poll, then "exit", then the local input keeps going
		// (never ends): the End mark must NOT be sent, completion alone ends the bridge.
		final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		final int exitCode;
		try (WinRMClient client = client()) {
			try (RemoteProcess process = client.command("cmd.exe").start()) {
				final PrintStream out = new PrintStream(stdout, true, "UTF-8");
				exitCode = InteractiveShell.bridge(
					process,
					new InteractiveShell.InputSource() {
						private int round;

						@Override
						public String nextPiece() {
							round++;
							// Round 1 (and the drain call right after "exit"): nothing queued.
							return round == 2 ? "exit\r\n" : null;
						}

						@Override
						public boolean endOfInput() {
							return false;
						}
					},
					out,
					new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
					new AtomicBoolean(),
					POLL_MILLIS
				);
			}
		}

		assertEquals(0, exitCode);
		assertTrue(stdout.toString("UTF-8").startsWith("Microsoft Windows"));

		// The banner traveled BEFORE the input: Receive first, the Send only on the next round.
		final List<String> requests = server.decryptedRequests();
		assertTrue(requests.get(2).contains(":Receive>"), requests.get(2));
		assertTrue(requests.get(3).contains(":Send>"), requests.get(3));

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(1, chunks.size());
		assertArrayEquals("exit\r\n".getBytes(StandardCharsets.UTF_8), chunks.get(0).data());
		assertFalse(chunks.get(0).end());
	}

	@Test
	void inputQueuedWhileTheFinalReceiveWasInFlightDoesNotHideTheExitCode() throws Exception {
		enqueueStartup();
		server
			// round 1: the final Receive carries the Done state...
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 3))))
			// ...and the completion cleanup Signal follows on the next round
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		// A line shows up right AFTER the final Receive: it can no longer be consumed, and it must
		// not turn the session into a failure — the exit code wins.
		final InteractiveShell.InputSource latePiece = new InteractiveShell.InputSource() {
			private int round;

			@Override
			public String nextPiece() {
				round++;
				return round == 2 ? "too late\r\n" : null;
			}

			@Override
			public boolean endOfInput() {
				return false;
			}
		};
		final int exitCode;
		try (WinRMClient client = client()) {
			try (RemoteProcess process = client.command("cmd.exe").start()) {
				exitCode = InteractiveShell.bridge(
					process,
					latePiece,
					new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
					new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
					new AtomicBoolean(),
					POLL_MILLIS
				);
			}
		}

		assertEquals(3, exitCode);
		// The late line never reached the wire: no Send left the client.
		assertTrue(server.stdinChunks().isEmpty());
		assertTrue(server.decryptedRequests().stream().noneMatch(request -> request.contains(":Send>")));
	}

	@Test
	void aFireHoseOfInputIsForwardedInBoundedBatches() throws Exception {
		final char[] big = new char[InteractiveShell.MAX_INPUT_CHARS_PER_ROUND + 1_000];
		java.util.Arrays.fill(big, 'x');
		final String hugeLine = new String(big);

		enqueueStartup();
		server
			// round 1: the first batch (capped) travels alone...
			.enqueue(200, envelope(sendResponse()))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", COMMAND_ID, "ok\r\n".getBytes(StandardCharsets.UTF_8)), null))
			)
			// ...the leftover line follows on the next round, with the End mark
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		final int exitCode;
		try (WinRMClient client = client()) {
			try (RemoteProcess process = client.command("cmd.exe").start()) {
				exitCode = InteractiveShell.bridge(
					process,
					ScriptedPieces.endingAfter(hugeLine + "\r\n", "exit\r\n"),
					new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
					new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"),
					new AtomicBoolean(),
					POLL_MILLIS
				);
			}
		}

		assertEquals(0, exitCode);
		// Two bounded batches instead of one unbounded drain: the oversized record was SPLIT at
		// the round budget — the output side polled in between — and nothing was lost or
		// reordered.
		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(2, chunks.size());
		assertEquals(InteractiveShell.MAX_INPUT_CHARS_PER_ROUND, chunks.get(0).data().length);
		assertFalse(chunks.get(0).end());
		assertTrue(chunks.get(1).end());
		final ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
		for (final FakeWsmanServer.StdinChunk chunk : chunks) {
			reassembled.writeBytes(chunk.data());
		}
		assertArrayEquals((hugeLine + "\r\nexit\r\n").getBytes(StandardCharsets.UTF_8), reassembled.toByteArray());
	}

	@Test
	void queuedInputSourceNormalizesLineEndingsAndReportsTheEnd() throws Exception {
		// The production InputSource behind InteractiveShell.run: fed by the helper thread reading
		// the local standard input. Here the whole stream is read synchronously (readAll returns
		// once the input ends), making the outcome deterministic. Every line-ending flavor (LF,
		// CRLF, lone CR) becomes the CRLF the remote cmd.exe expects, and a final unterminated
		// record is delivered as-is.
		final InteractiveShell.QueuedInputSource pieces = new InteractiveShell.QueuedInputSource();
		pieces.readAll(new ByteArrayInputStream("first\nsecond\r\nthird\rtail".getBytes(StandardCharsets.UTF_8)));

		assertFalse(pieces.endOfInput(), "queued pieces must be consumed before the end of input is reported");
		assertEquals("first\r\n", pieces.nextPiece());
		assertEquals("second\r\n", pieces.nextPiece());
		assertEquals("third\r\n", pieces.nextPiece());
		assertFalse(pieces.endOfInput());
		assertEquals("tail", pieces.nextPiece());
		assertNull(pieces.nextPiece());
		assertTrue(pieces.endOfInput());
		assertNull(pieces.nextPiece());
	}

	@Test
	void aLocalReadFailureFailsTheSourceInsteadOfEndingIt() throws Exception {
		// A failing local stdin must NOT read as a normal end of input: the remote shell would
		// execute the truncated input and the CLI would report success despite the local error.
		final java.io.InputStream failing = new java.io.InputStream() {
			private int calls;

			@Override
			public int read() throws java.io.IOException {
				calls++;
				if (calls <= 3) {
					return "ok\n".charAt(calls - 1);
				}
				throw new java.io.IOException("disk error");
			}
		};
		final InteractiveShell.QueuedInputSource pieces = new InteractiveShell.QueuedInputSource();
		pieces.readAll(failing);

		assertEquals("ok\r\n", pieces.nextPiece());
		final java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(
			java.io.IOException.class,
			pieces::nextPiece
		);
		assertEquals("disk error", failure.getCause().getMessage());
		assertTrue(pieces.endOfInput());
	}

	@Test
	void localInputCharsetProbesTheJdkSignalsBeforeTheProcessDefault() {
		// stdin.encoding (recent JDKs) wins over everything.
		assertEquals(
			StandardCharsets.ISO_8859_1,
			InteractiveShell.localInputCharset(name -> "stdin.encoding".equals(name) ? "ISO-8859-1" : null, null)
		);
		// sun.stdin.encoding: what a Windows console reports on older JDKs.
		assertEquals(
			StandardCharsets.US_ASCII,
			InteractiveShell.localInputCharset(name -> "sun.stdin.encoding".equals(name) ? "US-ASCII" : null, null)
		);
		// An unknown name is skipped and the probing continues down to native.encoding.
		assertEquals(
			StandardCharsets.UTF_16BE,
			InteractiveShell.localInputCharset(
				name -> "stdin.encoding".equals(name) ? "no-such-charset" : "native.encoding".equals(name) ? "UTF-16BE" : null,
				null
			)
		);
		// No signal at all: the process default.
		assertEquals(
			java.nio.charset.Charset.defaultCharset(),
			InteractiveShell.localInputCharset(name -> null, null)
		);
	}

	@Test
	void queuedInputSourceSlicesANewlineFreeRecordInsteadOfMaterializingIt() throws Exception {
		// A giant record without any newline (minified JSON, base64) must be queued in bounded
		// slices: the memory bound holds by characters, never by lines.
		final char[] record = new char[InteractiveShell.MAX_QUEUED_PIECE_CHARS + 500];
		java.util.Arrays.fill(record, 'x');
		final InteractiveShell.QueuedInputSource pieces = new InteractiveShell.QueuedInputSource();
		pieces.readAll(new ByteArrayInputStream((new String(record) + "\n").getBytes(StandardCharsets.UTF_8)));

		final String first = pieces.nextPiece();
		final String second = pieces.nextPiece();
		assertEquals(InteractiveShell.MAX_QUEUED_PIECE_CHARS, first.length());
		assertEquals(500 + 2, second.length());
		assertTrue(second.endsWith("\r\n"));
		assertEquals(new String(record) + "\r\n", first + second);
		assertNull(pieces.nextPiece());
		assertTrue(pieces.endOfInput());
	}
}
