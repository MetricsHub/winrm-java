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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.enumerationDone;
import static org.metricshub.winrm.light.FakeWsmanResponses.fault;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WqlSyntaxException;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * End-to-end tests of the streaming API (issue #111) against {@link FakeWsmanServer}:
 * {@code WqlRequest.stream()}, {@code CommandRequest.start()} / {@code RemoteProcess}, and the
 * {@code onStdout}/{@code onStderr} callbacks — laziness, resource cleanup (WS-Enumeration
 * Release, terminate Signal), incremental decoding, and the inactivity-timeout semantics.
 */
class StreamingApiTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";

	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	/** The WSMan fault code the server answers with when no result is ready before OperationTimeout. */
	private static final String FAULT_OPERATION_TIMEOUT = "2150858793";

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

	private static String service(final String name, final String state) {
		return instance("Win32_Service", "Name", name, "State", state);
	}

	/** An EnumerateResponse carrying rows and an open enumeration context (more pages follow). */
	private static String enumeratePage(final String context, final String... instances) {
		final StringBuilder xml = new StringBuilder();
		xml
			.append("<wsen:EnumerateResponse xmlns:wsen=\"")
			.append(WSEN)
			.append("\" xmlns:wsman=\"")
			.append(WSMAN)
			.append("\">")
			.append("<wsen:EnumerationContext>")
			.append(context)
			.append("</wsen:EnumerationContext>")
			.append("<wsman:Items>");
		for (final String item : instances) {
			xml.append(item);
		}
		return xml.append("</wsman:Items></wsen:EnumerateResponse>").toString();
	}

	/** A final PullResponse: the last rows and the end-of-sequence marker. */
	private static String pullDone(final String... instances) {
		final StringBuilder xml = new StringBuilder();
		xml
			.append("<wsen:PullResponse xmlns:wsen=\"")
			.append(WSEN)
			.append("\" xmlns:wsman=\"")
			.append(WSMAN)
			.append("\"><wsen:Items>");
		for (final String item : instances) {
			xml.append(item);
		}
		return xml.append("</wsen:Items><wsman:EndOfSequence/></wsen:PullResponse>").toString();
	}

	private static String releaseResponse() {
		return "<wsen:ReleaseResponse xmlns:wsen=\"" + WSEN + "\"/>";
	}

	// --- WQL streaming -------------------------------------------------------

	@Test
	void wqlStreamYieldsRowsBeforeLaterPagesAreFetched() throws Exception {
		server
			.enqueue(200, envelope(enumeratePage("uuid:CTX-1", service("Spooler", "Running"), service("W32Time", "Stopped"))))
			.enqueue(200, envelope(pullDone(service("WinRM", "Running"))));

		try (WinRMClient client = builder().build()) {
			try (Stream<WqlRow> rows = client.wql("SELECT Name, State FROM Win32_Service").stream()) {
				final Iterator<WqlRow> iterator = rows.iterator();

				assertEquals("Spooler", iterator.next().string("Name"));
				assertEquals("W32Time", iterator.next().string("Name"));
				// Both first-page rows were served from the Enumerate response alone: no Pull yet.
				assertEquals(1, server.decryptedRequests().size());

				assertEquals("WinRM", iterator.next().string("Name"));
				assertEquals(2, server.decryptedRequests().size());
				assertTrue(server.decryptedRequests().get(1).contains("enumeration/Pull"));

				assertFalse(iterator.hasNext());
			}
			// The enumeration completed with EndOfSequence: nothing to release.
			assertEquals(2, server.decryptedRequests().size());
		}
	}

	@Test
	void closingWqlStreamEarlySendsRelease() throws Exception {
		server
			.enqueue(
				200,
				envelope(enumeratePage("uuid:CTX-42", service("Spooler", "Running"), service("W32Time", "Stopped")))
			)
			.enqueue(200, envelope(releaseResponse()));

		try (WinRMClient client = builder().build()) {
			try (Stream<WqlRow> rows = client.wql("SELECT Name FROM Win32_Service").stream()) {
				assertEquals("Spooler", rows.findFirst().orElseThrow().string("Name"));
			}

			final List<String> requests = server.decryptedRequests();
			assertEquals(2, requests.size());
			assertTrue(requests.get(1).contains("enumeration/Release"), "early close must send a Release");
			assertTrue(requests.get(1).contains("uuid:CTX-42"), "the Release must carry the enumeration context");

			// The connection is free again: a follow-up query runs on the same client.
			server.enqueue(200, envelope(enumerationDone(service("WinRM", "Running"))));
			assertEquals(1, client.wql("SELECT Name FROM Win32_Service").execute().size());
		}
	}

	@Test
	void exhaustedWqlStreamReleasesTheConnectionWithoutRelease() throws Exception {
		server.enqueue(200, envelope(enumerationDone(service("Spooler", "Running"))));

		try (WinRMClient client = builder().build()) {
			final List<String> names = new ArrayList<>();
			try (Stream<WqlRow> rows = client.wql("SELECT Name FROM Win32_Service").stream()) {
				rows.map(row -> row.string("Name")).forEach(names::add);
			}
			assertEquals(List.of("Spooler"), names);
			assertEquals(1, server.decryptedRequests().size());

			// The permit was released on exhaustion: the client is immediately reusable.
			server.enqueue(200, envelope(enumerationDone(service("WinRM", "Running"))));
			assertEquals(1, client.wql("SELECT Name FROM Win32_Service").execute().size());
		}
	}

	@Test
	void wqlStreamMapsOperationTimeoutFaultToInactivityTimeout() throws Exception {
		server
			.enqueue(200, envelope(enumeratePage("uuid:CTX-1", service("Spooler", "Running"))))
			.enqueue(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."));

		try (WinRMClient client = builder().timeout(Duration.ofMillis(500)).build()) {
			try (Stream<WqlRow> rows = client.wql("SELECT Name FROM Win32_Service").stream()) {
				final Iterator<WqlRow> iterator = rows.iterator();
				assertEquals("Spooler", iterator.next().string("Name"));

				final WinRMTimeoutException e = assertThrows(WinRMTimeoutException.class, iterator::next);
				assertTrue(e.getMessage().contains("timed out"), e.getMessage());
			}
			// The enumeration state is unknown after the failure: no Release is pushed into it.
			assertEquals(2, server.decryptedRequests().size());
		}
	}

	@Test
	void totalServerSilenceIsBoundedByTheInactivityTimeout() throws Exception {
		server
			.enqueue(200, envelope(enumeratePage("uuid:CTX-1", service("Spooler", "Running"))))
			// The Pull response arrives way past the inactivity timeout — a server that stopped
			// answering entirely (no op-timeout fault). The socket read itself must give up at the
			// inactivity bound, not 10 seconds later (the headroom the blocking paths keep).
			.enqueueDelayed(200, envelope(pullDone(service("WinRM", "Running"))), 5_000);

		try (WinRMClient client = builder().timeout(Duration.ofMillis(300)).build()) {
			try (Stream<WqlRow> rows = client.wql("SELECT Name FROM Win32_Service").stream()) {
				final Iterator<WqlRow> iterator = rows.iterator();
				assertEquals("Spooler", iterator.next().string("Name"));

				final long start = System.nanoTime();
				assertThrows(WinRMTimeoutException.class, iterator::next);
				final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
				assertTrue(
					elapsedMillis < 4_000,
					"silence must be detected at the inactivity timeout, not " + elapsedMillis + " ms later"
				);
			}
		}
	}

	@Test
	void quietTimeoutOnTheInitialEnumerateSurfacesAsInactivityTimeout() throws Exception {
		// The op-timeout fault can answer the very first request too: stream() must report the
		// documented timeout, not a generic WSMan fault.
		server.enqueue(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."));

		try (WinRMClient client = builder().timeout(Duration.ofMillis(500)).build()) {
			assertThrows(WinRMTimeoutException.class, () -> client.wql("SELECT Name FROM Win32_Service").stream());
		}
	}

	@Test
	void closingTheClientWhileAWqlStreamIsOpenLeavesTheStreamInert() throws Exception {
		server.enqueue(200, envelope(enumeratePage("uuid:CTX-1", service("Spooler", "Running"))));

		try (WinRMClient client = builder().build()) {
			final Stream<WqlRow> rows = client.wql("SELECT Name FROM Win32_Service").stream();
			final Iterator<WqlRow> iterator = rows.iterator();
			assertEquals("Spooler", iterator.next().string("Name"));

			// The client goes away while the stream still owns the connection: closing the stream
			// afterward must not resurrect the transport (reconnect + re-authenticate) for a Release.
			client.close();
			rows.close();

			assertEquals(1, server.decryptedRequests().size());
		}
	}

	@Test
	void wqlStreamRejectsInvalidQueryBeforeSendingAnything() throws Exception {
		try (WinRMClient client = builder().build()) {
			assertThrows(WqlSyntaxException.class, () -> client.wql("Not a WQL query").stream());
			assertEquals(0, server.decryptedRequests().size());
		}
	}

	// --- Command streaming ---------------------------------------------------

	/** Script the shell creation and command startup that precede every command exchange. */
	private void enqueueCommandStartup() {
		server.enqueue(200, envelope(resourceCreated("SHELL-1"))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
	}

	private static String stdoutChunk(final String text) {
		return stream("stdout", COMMAND_ID, text.getBytes(StandardCharsets.UTF_8));
	}

	private static String stderrChunk(final String text) {
		return stream("stderr", COMMAND_ID, text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void startStreamsOutputWhileTheCommandIsStillRunning() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stdoutChunk("line1\n"), null)))
			.enqueue(200, envelope(receiveResponse(stdoutChunk("line2\n"), done(COMMAND_ID, 7))))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("dir /s").charset(StandardCharsets.UTF_8).start()) {
				final BufferedReader stdout = process.stdout();

				assertEquals("line1", stdout.readLine());
				// The first line was consumed while only one Receive had been answered: the command
				// is still running from the client's point of view.
				assertEquals(3, server.decryptedRequests().size());

				assertEquals("line2", stdout.readLine());
				assertNull(stdout.readLine());
				assertEquals(7, process.waitFor());
			}

			final List<String> requests = server.decryptedRequests();
			// Create, Command, Receive, Receive, Signal — and the close() after completion adds nothing.
			assertEquals(5, requests.size());
			assertTrue(requests.get(4).contains("signal/terminate"));
		}
	}

	@Test
	void interleavedChannelsAreSplitAndOrderIsPreservedPerChannel() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stdoutChunk("out1\n") + stderrChunk("err1\n"), null)))
			.enqueue(200, envelope(receiveResponse(stderrChunk("err2\n") + stdoutChunk("out2\n"), done(COMMAND_ID, 0))))
			.enqueue(200, envelope(signalResponse()));

		try (
			WinRMClient client = builder().build();
			RemoteProcess process = client.command("run").charset(StandardCharsets.UTF_8).start()) {
			// Draining stdout first buffers whatever arrives on stderr in the meantime.
			assertEquals(List.of("out1", "out2"), process.stdout().lines().collect(java.util.stream.Collectors.toList()));
			assertEquals(List.of("err1", "err2"), process.stderr().lines().collect(java.util.stream.Collectors.toList()));
			assertEquals(0, process.waitFor());
		}
	}

	@Test
	void multibyteCharacterSplitAcrossReceiveResponsesIsDecodedCorrectly() throws Exception {
		final byte[] eAcute = "é".getBytes(StandardCharsets.UTF_8); // 0xC3 0xA9
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stream("stdout", COMMAND_ID, new byte[]
			{ 'a', eAcute[0] }), null)))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", COMMAND_ID, new byte[]
				{ eAcute[1], 'b' }), done(COMMAND_ID, 0)))
			)
			.enqueue(200, envelope(signalResponse()));

		try (
			WinRMClient client = builder().build();
			RemoteProcess process = client.command("type utf8.txt").charset(StandardCharsets.UTF_8).start()) {
			assertEquals("aéb", process.stdout().readLine());
			assertEquals(0, process.waitFor());
		}
	}

	@Test
	void closingTheProcessEarlyTerminatesTheRemoteCommand() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stdoutChunk("tick\n"), null)))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			final RemoteProcess process = client.command("ping -t localhost").charset(StandardCharsets.UTF_8).start();
			assertEquals("tick", process.stdout().readLine());
			assertThrows(IllegalStateException.class, process::exitCode);

			process.close();

			// The handle is inert after closing: buffered output only, then end of stream — reads
			// and waits must not issue any further protocol request on a connection they no longer own.
			assertNull(process.stdout().readLine());
			assertThrows(IllegalStateException.class, process::waitFor);
			assertThrows(IllegalStateException.class, process::exitCode);

			final List<String> requests = server.decryptedRequests();
			assertEquals(4, requests.size());
			assertTrue(requests.get(3).contains("signal/terminate"), "early close must Signal the command");

			// The connection is free again after the early termination.
			server.enqueue(200, envelope(enumerationDone(service("WinRM", "Running"))));
			assertEquals(1, client.wql("SELECT Name FROM Win32_Service").execute().size());
		}
	}

	@Test
	void closingAfterTheFinalChunkStillExposesTheExitCode() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stdoutChunk("all\n"), done(COMMAND_ID, 5))))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			final RemoteProcess process = client.command("quick.exe").charset(StandardCharsets.UTF_8).start();
			// The final chunk (carrying the exit state) was received, but the end-of-stream fetch
			// never ran: closing must still expose the exit code the command actually reported.
			assertEquals("all", process.stdout().readLine());
			process.close();

			assertEquals(5, process.exitCode());
			assertEquals(5, process.waitFor());
			assertNull(process.stdout().readLine());
			assertEquals(4, server.decryptedRequests().size());
		}
	}

	@Test
	void waitForDeadlineExpiresWhileTheCommandKeepsRunning() throws Exception {
		enqueueCommandStartup();
		server
			.enqueueDelayed(200, envelope(receiveResponse(stdoutChunk("slow\n"), null)), 300)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("slow.exe").charset(StandardCharsets.UTF_8).start()) {
				assertFalse(process.waitFor(Duration.ofMillis(100)), "the command must still be running");
				// The output that arrived while waiting stays readable.
				assertEquals("slow", process.stdout().readLine());
			}
			assertTrue(server.decryptedRequests().get(3).contains("signal/terminate"));
		}
	}

	@Test
	void quietTimeoutDuringCommandStartupSurfacesAsInactivityTimeout() throws Exception {
		// The op-timeout fault can answer the shell Create too: start() must report the documented
		// timeout, not a generic WSMan fault.
		server.enqueue(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."));

		try (WinRMClient client = builder().timeout(Duration.ofMillis(500)).build()) {
			assertThrows(
				WinRMTimeoutException.class,
				() -> client.command("slow-start.exe").charset(StandardCharsets.UTF_8).start()
			);
		}
	}

	@Test
	void closingTheClientWhileAProcessIsOpenLeavesTheProcessInert() throws Exception {
		enqueueCommandStartup();
		server.enqueue(200, envelope(receiveResponse(stdoutChunk("tick\n"), null)));

		try (WinRMClient client = builder().build()) {
			final RemoteProcess process = client.command("ping -t localhost").charset(StandardCharsets.UTF_8).start();
			assertEquals("tick", process.stdout().readLine());

			// The client goes away while the process still owns the connection: closing the process
			// afterward must not resurrect the transport (reconnect + re-authenticate) for a Signal.
			client.close();
			process.close();

			assertEquals(3, server.decryptedRequests().size());
		}
	}

	@Test
	void waitForDeadlineBoundsTheActiveReceive() throws Exception {
		enqueueCommandStartup();
		server
			// Answers the bounded Receive after the wait would have expired: with a compliant server
			// this is the "nothing yet" op-timeout fault at the requested OperationTimeout. It must
			// read as an expired poll, NOT as an inactivity failure — the handle stays usable.
			.enqueueDelayed(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."), 300)
			.enqueue(200, envelope(receiveResponse(stdoutChunk("done\n"), done(COMMAND_ID, 3))))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("slow.exe").charset(StandardCharsets.UTF_8).start()) {
				assertFalse(process.waitFor(Duration.ofMillis(200)), "the command must still be running");

				// The active Receive was bounded by the remaining wait, not by the 10 s inactivity
				// timeout: its WSMan OperationTimeout is sub-second.
				final String boundedReceive = server.decryptedRequests().get(2);
				assertTrue(boundedReceive.contains("/Receive"), boundedReceive);
				assertTrue(
					boundedReceive.contains("<wsman:OperationTimeout>PT0."),
					"the bounded Receive must carry the remaining wait as its OperationTimeout"
				);

				// The expired wait was non-destructive: the process completes normally afterward.
				assertEquals(3, process.waitFor());
				assertEquals("done", process.stdout().readLine());
			}
		}
	}

	@Test
	void commandSilenceBeyondTheTimeoutSurfacesAsInactivityTimeout() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().timeout(Duration.ofMillis(500)).build()) {
			try (RemoteProcess process = client.command("silent.exe").charset(StandardCharsets.UTF_8).start()) {
				final WinRMTimeoutException e = assertThrows(WinRMTimeoutException.class, process::waitFor);
				assertTrue(e.getMessage().contains("no output"), e.getMessage());
			}
			// Closing after the failure still terminates the remote command.
			assertTrue(server.decryptedRequests().get(3).contains("signal/terminate"));
		}
	}

	// --- onStdout / onStderr callbacks ----------------------------------------

	@Test
	void callbacksReceiveChunksAsTheyArriveAndTheResultIsComplete() throws Exception {
		final byte[] eAcute = "é".getBytes(StandardCharsets.UTF_8);
		enqueueCommandStartup();
		server
			// The first chunk ends with half of a UTF-8 character: the callback must not see it
			// until the second chunk completes it.
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", COMMAND_ID, concat("first".getBytes(StandardCharsets.UTF_8), new byte[]
					{ eAcute[0] })), null)
				)
			)
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", COMMAND_ID, concat(new byte[]
						{ eAcute[1] }, "second".getBytes(StandardCharsets.UTF_8))) +
							stderrChunk("warning"),
						done(COMMAND_ID, 3)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));

		final List<String> stdoutChunks = new ArrayList<>();
		final List<String> stderrChunks = new ArrayList<>();
		try (WinRMClient client = builder().build()) {
			final CommandResult result = client
				.command("chatty.exe")
				.charset(StandardCharsets.UTF_8)
				.onStdout(stdoutChunks::add)
				.onStderr(stderrChunks::add)
				.execute();

			assertEquals(List.of("first", "ésecond"), stdoutChunks);
			assertEquals(List.of("warning"), stderrChunks);
			assertEquals("firstésecond", result.stdout());
			assertEquals("warning", result.stderr());
			assertEquals(3, result.exitCode());
		}
	}

	// --- SPI defaults ----------------------------------------------------------

	@Test
	void executorsWithoutStreamingSupportRejectTheStreamingEntryPoints() {
		final WindowsRemoteExecutor executor = new ScriptedWindowsRemoteExecutor();
		assertThrows(UnsupportedOperationException.class, () -> executor.streamWql("ROOT\\CIMV2", "SELECT 1", 1000, 10, 0));
		assertThrows(UnsupportedOperationException.class, () -> executor.startCommand("dir", null, 1000));
	}

	private static byte[] concat(final byte[] a, final byte[] b) {
		final byte[] result = new byte[a.length + b.length];
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}
}
