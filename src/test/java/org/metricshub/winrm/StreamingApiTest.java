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
				// A wait too short for any network round trip is waited out locally: no request goes
				// to the wire, and the process is untouched.
				assertFalse(process.waitFor(Duration.ofMillis(50)), "the command must still be running");
				assertEquals(2, server.decryptedRequests().size(), "a sub-round-trip wait must not touch the wire");

				// The process remains fully usable: reading advances the protocol as usual.
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
	void boundedPollTreatsAFaultWithinBudgetAsNothingYet() throws Exception {
		enqueueCommandStartup();
		server
			// The "nothing yet" op-timeout fault answering the bounded poll, arriving well within
			// the poll's budget — a compliant server answering at the shortened OperationTimeout.
			.enqueueDelayed(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."), 150)
			.enqueue(200, envelope(receiveResponse(stdoutChunk("done\n"), done(COMMAND_ID, 3))))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			try (CommandCursor cursor = client.executor().startCommand("run.exe", null, 10_000)) {
				// The fault reads as an expired poll (empty chunk), not as a failure.
				final CommandCursor.Chunk nothingYet = cursor.poll(2_000);
				assertEquals(0, nothingYet.stdout().length + nothingYet.stderr().length);

				// The bounded Receive asked the server to answer EARLY: its OperationTimeout is the
				// budget minus the fault-transit slack, so the answer arrives within the budget.
				final String boundedReceive = server.decryptedRequests().get(2);
				assertTrue(boundedReceive.contains("/Receive"), boundedReceive);
				assertTrue(
					boundedReceive.contains("<wsman:OperationTimeout>PT1S<"),
					"a 2 s poll must ask the server to answer within 1 s"
				);

				// The expired poll was non-destructive: the cursor completes normally afterward.
				final CommandCursor.Chunk chunk = cursor.next();
				assertEquals("done\n", new String(chunk.stdout(), StandardCharsets.UTF_8));
				assertNull(cursor.next());
				assertEquals(3, cursor.exitCode());
			}
		}
	}

	@Test
	void deadPeerCannotHoldABoundedWaitHostage() throws Exception {
		enqueueCommandStartup();
		server
			// The peer answers the bounded Receive long after the wait: a peer that stopped
			// answering. The wait must fail AT its deadline — the socket cuts at the poll budget
			// itself, with no headroom a dead peer could hide behind.
			.enqueueDelayed(500, fault(FAULT_OPERATION_TIMEOUT, "The operation timed out."), 4_000)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("dead.exe").charset(StandardCharsets.UTF_8).start()) {
				final long start = System.nanoTime();
				assertThrows(WinRMTimeoutException.class, () -> process.waitFor(Duration.ofMillis(200)));
				final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
				assertTrue(
					elapsedMillis < 3_000,
					"a dead peer must be detected at the bounded wait, not " + elapsedMillis + " ms later"
				);
				// The server was asked to answer within half the 200 ms budget.
				assertTrue(server.decryptedRequests().get(2).contains("<wsman:OperationTimeout>PT0.1S<"));
			}
			// close() terminated the command over a fresh connection (the abandoned one was dropped).
			final List<String> requests = server.decryptedRequests();
			assertTrue(requests.get(requests.size() - 1).contains("signal/terminate"));
		}
	}

	@Test
	void completionSignalIsBoundedByThePollBudget() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stdoutChunk("done\n"), done(COMMAND_ID, 5))))
			// The Signal acknowledging the ALREADY-COMPLETED command stalls far past the poll
			// budget: completion (and the known exit code) must win over the cleanup hiccup.
			.enqueueDelayed(200, envelope(signalResponse()), 3_000);

		try (WinRMClient client = builder().build()) {
			try (CommandCursor cursor = client.executor().startCommand("run.exe", null, 10_000)) {
				final CommandCursor.Chunk chunk = cursor.poll(5_000);
				assertEquals("done\n", new String(chunk.stdout(), StandardCharsets.UTF_8));

				final long start = System.nanoTime();
				assertNull(cursor.poll(1_000), "completion must be reported");
				final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
				assertTrue(
					elapsedMillis < 2_500,
					"the completion Signal must not outlive the poll budget; took " + elapsedMillis + " ms"
				);
				assertEquals(5, cursor.exitCode());
			}
		}
	}

	@Test
	void completionArrivingNearTheDeadlineIsStillReported() throws Exception {
		enqueueCommandStartup();
		// The final Done-carrying response lands close to the wait's deadline: too little budget is
		// left for a wire Signal, but the completion happened WITHIN the wait and must be reported
		// as such — never as a spurious expiry.
		server.enqueueDelayed(200, envelope(receiveResponse(stdoutChunk("late\n"), done(COMMAND_ID, 9))), 520);

		try (WinRMClient client = builder().build()) {
			try (RemoteProcess process = client.command("barely.exe").charset(StandardCharsets.UTF_8).start()) {
				assertTrue(process.waitFor(Duration.ofMillis(600)), "completion within the wait must be reported");
				assertEquals(9, process.exitCode());
				assertEquals("late", process.stdout().readLine());
				// The leftover budget could not fit a Signal round trip: none was sent.
				assertEquals(3, server.decryptedRequests().size());
			}
		}
	}

	@Test
	void faultAnsweringTheCompletionSignalDoesNotHideCompletion() throws Exception {
		enqueueCommandStartup();
		server
			.enqueue(200, envelope(receiveResponse(stdoutChunk("done\n"), done(COMMAND_ID, 5))))
			// The Signal acknowledging the ALREADY-COMPLETED command is answered with a fault: pure
			// cleanup noise — the completion and its exit code must win.
			.enqueue(500, fault("999", "Signal rejected"));

		try (WinRMClient client = builder().build()) {
			try (CommandCursor cursor = client.executor().startCommand("run.exe", null, 10_000)) {
				final CommandCursor.Chunk chunk = cursor.poll(5_000);
				assertEquals("done\n", new String(chunk.stdout(), StandardCharsets.UTF_8));
				assertNull(cursor.poll(5_000), "completion must be reported despite the Signal fault");
				assertEquals(5, cursor.exitCode());
			}

			// The fault was a complete, in-sync exchange: the connection remains usable.
			server.enqueue(200, envelope(enumerationDone(service("WinRM", "Running"))));
			assertEquals(1, client.wql("SELECT Name FROM Win32_Service").execute().size());
		}
	}

	@Test
	void completionInsideATinyPollSkipsTheSignal() throws Exception {
		enqueueCommandStartup();
		server.enqueue(200, envelope(receiveResponse(stdoutChunk("done\n"), done(COMMAND_ID, 5))));

		try (WinRMClient client = builder().build()) {
			try (CommandCursor cursor = client.executor().startCommand("run.exe", null, 10_000)) {
				final CommandCursor.Chunk chunk = cursor.poll(5_000);
				assertEquals("done\n", new String(chunk.stdout(), StandardCharsets.UTF_8));

				// No round trip fits in the remaining budget: completion is reported without a wire
				// Signal, and the healthy connection is left untouched.
				assertNull(cursor.poll(20));
				assertEquals(5, cursor.exitCode());
				assertEquals(3, server.decryptedRequests().size(), "a tiny-budget completion must not touch the wire");
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
