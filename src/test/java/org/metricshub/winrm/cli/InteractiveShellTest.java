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
 * {@link FakeWsmanServer}, with a scripted {@link InteractiveShell.LineSource} making every round
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

	/** A line source whose {@code nextLine()} answers are fully scripted, including "not yet". */
	private static final class ScriptedLines implements InteractiveShell.LineSource {

		private final Deque<String> answers = new ArrayDeque<>();
		private boolean ended;

		/** The given lines, one per {@code nextLine()} call, then the end of the local input. */
		private static ScriptedLines endingAfter(final String... lines) {
			final ScriptedLines scripted = new ScriptedLines();
			for (final String line : lines) {
				scripted.answers.addLast(line);
			}
			scripted.ended = true;
			return scripted;
		}

		/** A local input that never produces anything and never ends (a silent terminal). */
		private static ScriptedLines silent() {
			return new ScriptedLines();
		}

		@Override
		public String nextLine() {
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
					ScriptedLines.endingAfter("MODE PREPARE"),
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
					ScriptedLines.silent(),
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
					new InteractiveShell.LineSource() {
						private int round;

						@Override
						public String nextLine() {
							round++;
							// Round 1 (and the drain call right after "exit"): nothing queued.
							return round == 2 ? "exit" : null;
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
	void queuedLineSourceDeliversLinesInOrderThenTheEndOfInput() {
		// The production LineSource behind InteractiveShell.run: fed by the helper thread reading
		// the local standard input. Here the whole stream is read synchronously (readAll returns
		// once the input ends), making the outcome deterministic.
		final InteractiveShell.QueuedLineSource lines = new InteractiveShell.QueuedLineSource();
		lines.readAll(new ByteArrayInputStream("first\nsecond\r\nexit\n".getBytes(StandardCharsets.UTF_8)));

		assertFalse(lines.endOfInput(), "queued lines must be consumed before the end of input is reported");
		assertEquals("first", lines.nextLine());
		assertEquals("second", lines.nextLine());
		assertFalse(lines.endOfInput());
		assertEquals("exit", lines.nextLine());
		assertNull(lines.nextLine());
		assertTrue(lines.endOfInput());
		assertNull(lines.nextLine());
	}
}
