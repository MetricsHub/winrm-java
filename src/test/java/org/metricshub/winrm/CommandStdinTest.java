package org.metricshub.winrm;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellDeletion;
import static org.metricshub.winrm.light.FakeWsmanResponses.fault;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.sendResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * End-to-end tests of command standard input (issue #136, phase 1) against
 * {@link FakeWsmanServer}: pre-supplied input on the builders ({@code stdin(...)}), the
 * process-style {@code RemoteProcess.stdin()} writer, the {@code ctrl_c} interrupt, the
 * console-mode option on the wire, chunking, the {@code End} flag, and the cleanup discipline
 * (early close, stdin after completion).
 */
class CommandStdinTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";

	private static final String SHELL_ID = "SHELL-1";
	private static final String COMMAND_ID = "CMD-1";

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private WinRMClient.Builder builder() {
		return WinRMClient
			.builder("127.0.0.1")
			.port(server.port())
			.credentials(DOMAIN + "\\" + USER, PASSWORD.toCharArray())
			.timeout(Duration.ofSeconds(10));
	}

	private void enqueueStartup() {
		server.enqueue(200, envelope(resourceCreated(SHELL_ID))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
	}

	@Test
	void presuppliedStringStdinIsDeliveredWithPipeSemantics() throws Exception {
		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", COMMAND_ID, "alpha\r\nbeta\r\n".getBytes(StandardCharsets.UTF_8)),
						done(COMMAND_ID, 0)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			final CommandResult result = client.command("sort").stdin("beta\nalpha\n").execute();

			assertEquals(0, result.exitCode());
			assertEquals("alpha\r\nbeta\r\n", result.stdout());
		}

		// The whole input fits one Send: a single chunk carrying the End flag.
		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(1, chunks.size());
		assertArrayEquals("beta\nalpha\n".getBytes(StandardCharsets.UTF_8), chunks.get(0).data());
		assertTrue(chunks.get(0).end());

		// Programmatic stdin switches the remote stdin to pipe semantics on the wire.
		final List<String> requests = server.decryptedRequests();
		assertTrue(
			requests.get(1).contains("<wsman:Option Name=\"WINRS_CONSOLEMODE_STDIN\">FALSE</wsman:Option>"),
			requests.get(1)
		);
	}

	@Test
	void presuppliedFileStdinIsDeliveredVerbatim(@TempDir final Path tempDir) throws Exception {
		final byte[] content = "line one\r\nline two\r\n".getBytes(StandardCharsets.UTF_8);
		final Path file = tempDir.resolve("input.txt");
		Files.write(file, content);

		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 3))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			assertEquals(3, client.command("findstr two").stdin(file).execute().exitCode());
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(1, chunks.size());
		assertArrayEquals(content, chunks.get(0).data());
		assertTrue(chunks.get(0).end());
	}

	@Test
	void largePresuppliedStdinIsChunkedAndOnlyTheLastChunkCarriesEnd() throws Exception {
		// Three 64 KiB read buffers: 64 KiB + 64 KiB + 18 928 bytes.
		final int size = 150_000;
		final StringBuilder text = new StringBuilder(size);
		for (int i = 0; i < size; i++) {
			text.append((char) ('a' + i % 26));
		}

		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			assertEquals(0, client.command("sort").stdin(text.toString()).execute().exitCode());
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(3, chunks.size());
		assertFalse(chunks.get(0).end());
		assertFalse(chunks.get(1).end());
		assertTrue(chunks.get(2).end());

		// Reassembling the chunks yields exactly the input.
		final ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
		for (final FakeWsmanServer.StdinChunk chunk : chunks) {
			reassembled.writeBytes(chunk.data());
		}
		assertArrayEquals(text.toString().getBytes(StandardCharsets.UTF_8), reassembled.toByteArray());
	}

	@Test
	void emptyStdinAnnouncesOnlyTheEndOfInput() throws Exception {
		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			assertEquals(0, client.command("sort").stdin("").execute().exitCode());
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(1, chunks.size());
		assertEquals(0, chunks.get(0).data().length);
		assertTrue(chunks.get(0).end());
	}

	@Test
	void presuppliedStdinWithStartClosesTheWriterFromTheStart() throws Exception {
		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("sort").stdin("beta\nalpha\n").start()) {
				// The input was delivered in full at startup: the writer rejects further input
				// immediately, instead of silently buffering it into the void.
				assertThrows(IOException.class, () -> process.stdin().write("more"));
				assertEquals(0, process.waitFor());
			}
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(1, chunks.size());
		assertArrayEquals("beta\nalpha\n".getBytes(StandardCharsets.UTF_8), chunks.get(0).data());
		assertTrue(chunks.get(0).end());
	}

	@Test
	void aSendLeftUnansweredSurfacesAsTheDocumentedTimeout() throws Exception {
		enqueueStartup();
		// The Send stays unanswered past the inactivity timeout: the documented timeout must
		// surface (the CLI maps it to exit 124), not a generic connection or protocol failure.
		server.enqueueDelayed(200, envelope(sendResponse()), 4_000);

		try (WinRMClient client = builder().timeout(Duration.ofMillis(1_500)).build()) {
			final RemoteProcess process = client.command("repl.exe").stdin().start();
			final BufferedWriter stdin = process.stdin();
			stdin.write("ping\n");
			assertThrows(WinRMTimeoutException.class, stdin::flush);
		}
	}

	@Test
	void aFailedStdinDeliveryIsNotMaskedByACleanupFailure(@TempDir final Path tempDir) {
		enqueueStartup();
		// The terminate Signal cleaning up the failed start is itself answered with a fault: the
		// original delivery failure must win, with the cleanup failure attached as suppressed.
		server.enqueue(500, fault("999", "Signal rejected"));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			final Path missing = tempDir.resolve("missing.txt");
			final WinRMClientException failure = assertThrows(
				WinRMClientException.class,
				() -> client.command("sort").stdin(missing).start()
			);
			assertTrue(failure.getCause() instanceof java.nio.file.NoSuchFileException, String.valueOf(failure.getCause()));
			assertEquals(1, failure.getCause().getSuppressed().length);
		}
	}

	@Test
	void remoteProcessStdinSupportsAWriteFlushReadRoundTrip() throws Exception {
		enqueueStartup();
		server
			// write + flush → one Send
			.enqueue(200, envelope(sendResponse()))
			// the REPL answers
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", COMMAND_ID, "pong\r\n".getBytes(StandardCharsets.UTF_8)), null))
			)
			// closing stdin → the End-of-input Send
			.enqueue(200, envelope(sendResponse()))
			// the REPL exits
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("repl.exe").stdin().start()) {
				final BufferedWriter stdin = process.stdin();
				final BufferedReader stdout = process.stdout();

				stdin.write("ping\n");
				stdin.flush();
				assertEquals("pong", stdout.readLine());

				stdin.close();
				assertEquals(0, process.waitFor());
			}
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(2, chunks.size());
		assertArrayEquals("ping\n".getBytes(StandardCharsets.UTF_8), chunks.get(0).data());
		assertFalse(chunks.get(0).end());
		assertEquals(0, chunks.get(1).data().length);
		assertTrue(chunks.get(1).end());

		// The no-argument stdin() declared interactive input: pipe semantics on the wire, so the
		// End mark above actually delivered EOF to the command.
		assertTrue(
			server.decryptedRequests().get(1).contains("<wsman:Option Name=\"WINRS_CONSOLEMODE_STDIN\">FALSE</wsman:Option>")
		);
	}

	@Test
	void interactiveWriteLargerThanOneEnvelopeIsSplit() throws Exception {
		// 200 KiB of ASCII: 98 304 + 98 304 + 8 192 bytes across three Sends of one flush.
		final char[] text = new char[200 * 1024];
		java.util.Arrays.fill(text, 'x');

		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("consume.exe").start()) {
				process.stdin().write(text);
				process.stdin().flush();
			}
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(3, chunks.size());
		assertEquals(96 * 1024, chunks.get(0).data().length);
		assertEquals(96 * 1024, chunks.get(1).data().length);
		assertEquals(200 * 1024 - 2 * 96 * 1024, chunks.get(2).data().length);
		assertFalse(chunks.get(0).end());
		assertFalse(chunks.get(1).end());
		assertFalse(chunks.get(2).end());
	}

	@Test
	void earlyCloseWithStdinOpenLeavesNoStrayRequests() throws Exception {
		enqueueStartup();
		server.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			final RemoteProcess process = client.command("repl.exe").start();
			final BufferedWriter stdin = process.stdin();
			stdin.write("never flushed");
			process.close();

			final int requestsAfterClose = server.decryptedRequests().size();
			assertEquals(3, requestsAfterClose, () -> String.join("\n---\n", server.decryptedRequests()));
			assertTrue(server.decryptedRequests().get(2).contains("signal/terminate"));

			// Ending the input after the close is silent cleanup: no request may leave the client,
			// and the input it still held is discarded, like a java.lang.Process pipe's.
			stdin.close();
			assertEquals(requestsAfterClose, server.decryptedRequests().size());

			// Further writes are refused locally.
			assertThrows(IOException.class, () -> writeAndFlush(stdin, "more"));
			assertEquals(requestsAfterClose, server.decryptedRequests().size());
		}
	}

	@Test
	void stdinAfterCompletionIsSilentOnCloseAndRefusedOnWrite() throws Exception {
		enqueueStartup();
		server
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 5))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("run.exe").start()) {
				assertEquals(5, process.waitFor());
				final int requestsAfterCompletion = server.decryptedRequests().size();

				// Closing stdin after completion is a clean no-op...
				process.stdin().close();
				assertEquals(requestsAfterCompletion, server.decryptedRequests().size());

				// ...but writing to it is refused, locally.
				assertThrows(IOException.class, () -> writeAndFlush(process.stdin(), "late"));
				assertEquals(requestsAfterCompletion, server.decryptedRequests().size());
			}
		}
	}

	@Test
	void interruptSendsCtrlCAndTheSessionContinues() throws Exception {
		enqueueStartup();
		server
			// the ctrl_c Signal
			.enqueue(200, envelope(signalResponse()))
			// the interrupted child's shell keeps running, then exits
			.enqueue(200, envelope(receiveResponse("", done(COMMAND_ID, 130))))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("cmd.exe").start()) {
				process.interrupt();
				assertEquals(130, process.waitFor());

				// Once completed, further interrupts are local no-ops.
				final int requests = server.decryptedRequests().size();
				process.interrupt();
				assertEquals(requests, server.decryptedRequests().size());
			}
		}

		final List<String> requests = server.decryptedRequests();
		assertTrue(requests.get(2).contains("signal/ctrl_c"), requests.get(2));
		assertTrue(requests.get(4).contains("signal/terminate"), requests.get(4));

		// Without any stdin declaration the historical console semantics are kept.
		assertTrue(
			requests.get(1).contains("<wsman:Option Name=\"WINRS_CONSOLEMODE_STDIN\">TRUE</wsman:Option>"),
			requests.get(1)
		);
	}

	@Test
	void stdinEncodingIsStatefulAcrossFlushes() throws Exception {
		// U+1F600 as its surrogate halves: the pair is split across two flushes below.
		final char high = (char) 0xD83D;
		final char low = (char) 0xDE00;
		final String emoji = new String(new char[] { high, low });

		enqueueStartup();
		server
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(sendResponse()))
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (
				RemoteProcess process = client.command("consume.exe")
					.charset(StandardCharsets.UTF_16)
					.stdin()
					.start()) {
				final BufferedWriter stdin = process.stdin();
				// Two flushes of a charset with a byte-order mark, the second one ending on a lone
				// high surrogate: incremental encoding must yield exactly one whole-string encode.
				stdin.write("ab");
				stdin.flush();
				stdin.write("cd");
				stdin.write(high);
				stdin.flush();
				stdin.write(low);
				stdin.close();
			}
		}

		final List<FakeWsmanServer.StdinChunk> chunks = server.stdinChunks();
		assertEquals(3, chunks.size());
		final ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
		for (final FakeWsmanServer.StdinChunk chunk : chunks) {
			reassembled.writeBytes(chunk.data());
		}
		assertArrayEquals(("abcd" + emoji).getBytes(StandardCharsets.UTF_16), reassembled.toByteArray());
		// The byte-order mark travels once, with the first chunk only.
		assertArrayEquals("ab".getBytes(StandardCharsets.UTF_16), chunks.get(0).data());
		// The half pair was withheld at its flush and completed by the final write: the second
		// chunk carries "cd" alone, the last one the whole character.
		assertArrayEquals("cd".getBytes(StandardCharsets.UTF_16BE), chunks.get(1).data());
		assertArrayEquals(emoji.getBytes(StandardCharsets.UTF_16BE), chunks.get(2).data());
		assertTrue(chunks.get(2).end());
	}

	@Test
	void cursorRejectsInputAfterTheEndMark() throws Exception {
		enqueueStartup();
		server.enqueue(200, envelope(sendResponse())).enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);

		try (WinRMClient client = builder().build()) {
			try (CommandCursor cursor = client.executor().startCommand("sort", null, 10_000, false)) {
				cursor.send("all of it".getBytes(StandardCharsets.UTF_8), true);
				final int requestsAfterEnd = server.decryptedRequests().size();

				// The remote stdin reached EOF: later input is a caller bug, rejected locally.
				assertThrows(
					IllegalStateException.class,
					() -> cursor.send("too late".getBytes(StandardCharsets.UTF_8), false)
				);
				assertEquals(requestsAfterEnd, server.decryptedRequests().size());
			}
		}
	}

	@Test
	void interactiveStdinDeclarationIsRejectedByExecute() {
		try (WinRMClient client = builder().build()) {
			// execute() cannot take interactive input: the misconfiguration is rejected before any
			// request leaves the client — a command waiting on a never-fed pipe would hang instead.
			assertThrows(IllegalStateException.class, () -> client.command("sort").stdin().execute());
		}
		assertEquals(0, server.decryptedRequests().size());
	}

	/** Write then flush, unwrapping nothing: the assertion targets the raised exception type. */
	private static void writeAndFlush(final BufferedWriter writer, final String text) throws IOException {
		writer.write(text);
		writer.flush();
	}
}
