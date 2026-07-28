package org.metricshub.winrm.light;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.fault;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * End-to-end protocol tests against {@link FakeWsmanServer} (issue #107): the full NTLM
 * handshake, message encryption, multipart framing, WQL Enumerate/Pull paging, the command
 * shell lifecycle, and fault mapping — all in-process, no Windows host required.
 */
class WsmanProtocolTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";
	private static final long TIMEOUT = 30_000L;

	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";
	private static final String RSP = "http://schemas.microsoft.com/wbem/wsman/1/windows/shell";

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private LightWinRMService client(final String password) throws Exception {
		final WinRMEndpoint endpoint = new WinRMEndpoint(
			WinRMHttpProtocolEnum.HTTP,
			"127.0.0.1",
			server.port(),
			DOMAIN + "\\" + USER,
			password.toCharArray(),
			null
		);
		return LightWinRMService.createInstance(endpoint, TIMEOUT, null, List.of(AuthenticationEnum.NTLM));
	}

	// --- WQL paging -----------------------------------------------------------

	@Test
	void wqlPagesAcrossEnumerateAndPullsOverEncryptedNtlm() throws Exception {
		// Optimized EnumerateResponse (wsman:Items) -> Pull (wsen:Items) -> final Pull with the
		// wsman:EndOfSequence variant: covers both Items and both EndOfSequence namespaces end to end.
		server
			.enqueue(
				200,
				envelope(
					"<wsen:EnumerateResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsen:EnumerationContext>uuid:CTX-1</wsen:EnumerationContext>" +
						"<wsman:Items>" +
						service("Spooler", "Running") +
						"</wsman:Items>" +
						"</wsen:EnumerateResponse>"
				)
			)
			.enqueue(
				200,
				envelope(
					"<wsen:PullResponse xmlns:wsen=\"" +
						WSEN +
						"\">" +
						"<wsen:EnumerationContext>uuid:CTX-2</wsen:EnumerationContext>" +
						"<wsen:Items>" +
						service("WinRM", "Running") +
						service("Wecsvc", "Stopped") +
						"</wsen:Items>" +
						"</wsen:PullResponse>"
				)
			)
			.enqueue(
				200,
				envelope(
					"<wsen:PullResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsman:EndOfSequence/>" +
						"</wsen:PullResponse>"
				)
			);

		try (LightWinRMService service = client(PASSWORD)) {
			final List<Map<String, Object>> rows = service.executeWql("SELECT Name,State FROM Win32_Service", TIMEOUT);

			assertEquals(3, rows.size());
			assertEquals("Spooler", rows.get(0).get("Name"));
			assertEquals("Running", rows.get(0).get("State"));
			assertEquals("WinRM", rows.get(1).get("Name"));
			assertEquals("Wecsvc", rows.get(2).get("Name"));
			assertEquals("Stopped", rows.get(2).get("State"));
		}

		// The decrypted request bodies pin what the client actually sends on the wire.
		final List<String> requests = server.decryptedRequests();
		assertEquals(3, requests.size(), () -> String.join("\n---\n", requests));
		final String enumerate = requests.get(0);
		assertTrue(enumerate.contains("<wsman:OperationTimeout>PT30S</wsman:OperationTimeout>"), enumerate);
		assertTrue(enumerate.contains("<wsman:OptimizeEnumeration/>"), enumerate);
		assertTrue(enumerate.contains("<wsman:MaxElements>32000</wsman:MaxElements>"), enumerate);
		assertTrue(enumerate.contains("http://schemas.microsoft.com/wbem/wsman/1/wmi/ROOT/CIMV2/*"), enumerate);
		assertTrue(enumerate.contains("SELECT Name,State FROM Win32_Service"), enumerate);
		assertTrue(requests.get(1).contains("uuid:CTX-1"), requests.get(1));
		assertTrue(requests.get(2).contains("uuid:CTX-2"), requests.get(2));
	}

	@Test
	void wqlPagesOverChunkedResponsesWithTrailers() throws Exception {
		// Real WinRM hosts answer with Transfer-Encoding: chunked. The client must reassemble the
		// chunks AND consume the trailer fields that follow the terminating chunk — leftover trailer
		// bytes desync the kept-alive NTLM connection, so the SECOND request on it is what fails.
		server
			.withChunkedResponses()
			.enqueue(
				200,
				envelope(
					"<wsen:EnumerateResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsen:EnumerationContext>uuid:CTX-1</wsen:EnumerationContext>" +
						"<wsman:Items>" +
						service("Spooler", "Running") +
						"</wsman:Items>" +
						"</wsen:EnumerateResponse>"
				)
			)
			.enqueue(
				200,
				envelope(
					"<wsen:PullResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsman:Items>" +
						service("WinRM", "Running") +
						"</wsman:Items>" +
						"<wsman:EndOfSequence/>" +
						"</wsen:PullResponse>"
				)
			);

		try (LightWinRMService service = client(PASSWORD)) {
			final List<Map<String, Object>> rows = service.executeWql("SELECT Name,State FROM Win32_Service", TIMEOUT);

			assertEquals(2, rows.size());
			assertEquals("Spooler", rows.get(0).get("Name"));
			assertEquals("WinRM", rows.get(1).get("Name"));
		}

		// The Pull was answered on the same connection: proof the trailers were fully drained.
		final List<String> requests = server.decryptedRequests();
		assertEquals(2, requests.size(), () -> String.join("\n---\n", requests));
		assertTrue(requests.get(1).contains("uuid:CTX-1"), requests.get(1));
	}

	// --- Command shell lifecycle ------------------------------------------------

	@Test
	void commandLifecycleReassemblesMultibyteOutputSplitAcrossReceives() throws Exception {
		// "héllo!" in UTF-8, split in the middle of the 2-byte 'é' across two Receive responses: the
		// client must accumulate raw bytes and decode once, or the boundary bytes become U+FFFD.
		final byte[] utf8 = "héllo!".getBytes(StandardCharsets.UTF_8);
		final byte[] chunk1 = java.util.Arrays.copyOfRange(utf8, 0, 2); // 'h' + first byte of 'é'
		final byte[] chunk2 = java.util.Arrays.copyOfRange(utf8, 2, utf8.length);

		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(200, envelope(receiveResponse(stream("stdout", "CMD-1", chunk1), null)))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", "CMD-1", chunk2) + stream("stderr", "CMD-1", "warn!".getBytes(StandardCharsets.UTF_8)),
						done("CMD-1", 7)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (LightWinRMService service = client(PASSWORD)) {
			final WindowsRemoteCommandResult result = service.executeCommand(
				"echo héllo!",
				null,
				StandardCharsets.UTF_8,
				TIMEOUT
			);

			assertEquals("héllo!", result.getStdout());
			assertEquals("warn!", result.getStderr());
			assertEquals(7, result.getStatusCode());
		}

		final List<String> requests = server.decryptedRequests();
		// Create + Command + 2x Receive + Signal (+ the close()-time shell Delete).
		assertTrue(requests.size() >= 5, () -> String.join("\n---\n", requests));
		final String create = requests.get(0);
		assertTrue(create.contains("<wsman:Option Name=\"WINRS_NOPROFILE\">TRUE</wsman:Option>"), create);
		// UTF-8: the only console code page that can carry every remote locale's output, and the one
		// the decoding side assumes without asking the remote host (see #142).
		assertTrue(create.contains("<wsman:Option Name=\"WINRS_CODEPAGE\">65001</wsman:Option>"), create);
		assertTrue(create.contains("<rsp:OutputStreams>stdout stderr</rsp:OutputStreams>"), create);
		final String command = requests.get(1);
		assertTrue(command.contains("echo héllo!"), command);
		assertTrue(command.contains("Selector Name=\"ShellId\">SHELL-1<"), command);
		final String receive = requests.get(2);
		assertTrue(receive.contains("CommandId=\"CMD-1\">stdout stderr</rsp:DesiredStream>"), receive);
		final String signal = requests.get(4);
		assertTrue(signal.contains(RSP + "/signal/terminate"), signal);
	}

	@Test
	void receiveRetriesOnOperationTimeoutFault() throws Exception {
		// No output before OperationTimeout: the server faults with 2150858793 and the client must
		// immediately re-issue the Receive rather than fail the command.
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				500,
				fault(
					"2150858793",
					"The WS-Management service cannot complete the operation within the time specified in OperationTimeout."
				)
			)
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-1", "late".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (LightWinRMService service = client(PASSWORD)) {
			final WindowsRemoteCommandResult result = service.executeCommand("slow", null, StandardCharsets.UTF_8, TIMEOUT);
			assertEquals("late", result.getStdout());
			assertEquals(0, result.getStatusCode());
		}

		// Two Receive requests must have been sent: the faulted one and the retry.
		final long receives = server.decryptedRequests().stream().filter(r -> r.contains("</rsp:DesiredStream>")).count();
		assertEquals(2, receives);
	}

	@Test
	void commandExitCodeAboveIntegerMaxIsNarrowedNotRejected() throws Exception {
		// Windows reports HRESULT exit codes (e.g. certutil's 0x80070002 for a missing file) as
		// unsigned 32-bit values like 2147942402, which overflow Integer.parseInt: the client
		// must narrow them to the equivalent signed int instead of failing the whole command.
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream(
							"stdout",
							"CMD-1",
							"CertUtil: -hashfile command FAILED: 0x80070002".getBytes(StandardCharsets.UTF_8)
						),
						"<rsp:CommandState CommandId=\"CMD-1\" State=\"" +
							RSP +
							"/CommandState/Done\"><rsp:ExitCode>2147942402</rsp:ExitCode></rsp:CommandState>"
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (LightWinRMService service = client(PASSWORD)) {
			final WindowsRemoteCommandResult result = service.executeCommand(
				"certutil -hashfile \"C:\\missing\" SHA256",
				null,
				StandardCharsets.UTF_8,
				TIMEOUT
			);

			assertEquals((int) 2147942402L, result.getStatusCode());
			assertTrue(result.getStdout().contains("0x80070002"));
		}
	}

	@Test
	void terminateSignalToleratesShellNotFoundFault() throws Exception {
		// The command finished and the shell may already be gone: fault 2150858843 on the terminate
		// Signal must not fail the (successful) command.
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "ok".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0)))
			)
			.enqueue(
				500,
				fault("2150858843", "The WS-Management service cannot process the request because the resource offline.")
			);

		try (LightWinRMService service = client(PASSWORD)) {
			final WindowsRemoteCommandResult result = service.executeCommand("whoami", null, StandardCharsets.UTF_8, TIMEOUT);
			assertEquals("ok", result.getStdout());
			assertEquals(0, result.getStatusCode());
		}
	}

	// --- Fault mapping ------------------------------------------------------------

	@Test
	void wqlFaultSurfacesCodeReasonAndWbemDetail() throws Exception {
		server.enqueue(
			500,
			fault(
				"2150858778",
				"The WS-Management service cannot process the request.",
				"The WMI service or the WMI provider returned an unknown error: WBEM_E_INVALID_CLASS"
			)
		);

		try (LightWinRMService service = client(PASSWORD)) {
			final WinRMException e = assertThrows(
				WinRMException.class,
				() -> service.executeWql("SELECT Name FROM No_Such_Class", TIMEOUT)
			);
			final String message = e.getMessage();
			assertTrue(message.contains("Enumerate failed"), message);
			assertTrue(message.contains("WSManFault 2150858778"), message);
			assertTrue(message.contains("The WS-Management service cannot process the request."), message);
			// The provider-level detail carries the WBEM_E_* mnemonics MetricsHub matches on.
			assertTrue(message.contains("WBEM_E_INVALID_CLASS"), message);
		}
	}

	@Test
	void wrongPasswordSurfacesTheCxfAuthenticationErrorMessage() throws Exception {
		try (LightWinRMService service = client("wrong-password")) {
			final WinRMException e = assertThrows(
				WinRMException.class,
				() -> service.executeWql("SELECT Name FROM Win32_Service", TIMEOUT)
			);
			// Exact CXF-parity message (issue #106): operators and callers match on this format.
			assertEquals(
				"Authentication error on http://127.0.0.1:" + server.port() + "/wsman with user name \"FAKE\\user\"",
				e.getMessage()
			);
		}
	}

	// --- response body builders -----------------------------------------------------

	private static String service(final String name, final String state) {
		return instance("Win32_Service", "Name", name, "State", state);
	}
}
