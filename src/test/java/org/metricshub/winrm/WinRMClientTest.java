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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.exceptions.WinRMAuthenticationException;
import org.metricshub.winrm.exceptions.WinRMFaultException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WqlSyntaxException;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * End-to-end tests of the fluent {@link WinRMClient} API against {@link FakeWsmanServer}: the
 * full NTLM handshake and message encryption, the typed results, the wire effect of the
 * builder options, and the unchecked exception mapping — all in-process, no Windows host.
 */
class WinRMClientTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";

	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private WinRMClient.Builder builder(final String password) {
		return WinRMClient
			.builder("127.0.0.1")
			.port(server.port())
			.credentials(DOMAIN + "\\" + USER, password.toCharArray());
	}

	private static String service(final String name, final String state) {
		return instance("Win32_Service", "Name", name, "State", state);
	}

	@Test
	void wqlReturnsTypedRowsAndColumnsOverEncryptedNtlm() throws Exception {
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
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsen:Items>" +
						service("WinRM", "Stopped") +
						"</wsen:Items>" +
						"<wsman:EndOfSequence/>" +
						"</wsen:PullResponse>"
				)
			);

		try (WinRMClient client = builder(PASSWORD).build()) {
			final WqlResult result = client.wql("SELECT Name, State FROM Win32_Service").execute();

			assertEquals(List.of("Name", "State"), result.columns());
			assertEquals(2, result.size());
			assertFalse(result.isEmpty());
			assertEquals("Spooler", result.rows().get(0).string("Name"));
			// Property lookup is case-insensitive, like WMI itself.
			assertEquals("Running", result.rows().get(0).string("state"));
			assertNotNull(result.elapsed());

			// The result is directly iterable.
			int count = 0;
			for (final WqlRow row : result) {
				assertNotNull(row.string("Name"));
				count++;
			}
			assertEquals(2, count);
		}

		// Defaults pinned on the wire: MaxElements 32000, no MaxTime, ROOT/CIMV2 namespace.
		final List<String> requests = server.decryptedRequests();
		assertEquals(2, requests.size(), () -> String.join("\n---\n", requests));
		final String enumerate = requests.get(0);
		assertTrue(enumerate.contains("<wsman:MaxElements>32000</wsman:MaxElements>"), enumerate);
		assertTrue(enumerate.contains("http://schemas.microsoft.com/wbem/wsman/1/wmi/ROOT/CIMV2/*"), enumerate);
		final String pull = requests.get(1);
		assertTrue(pull.contains("<wsen:MaxElements>32000</wsen:MaxElements>"), pull);
		assertFalse(pull.contains("MaxTime"), pull);
	}

	@Test
	void wqlOptionsReachTheWire() throws Exception {
		server
			.enqueue(
				200,
				envelope(
					"<wsen:EnumerateResponse xmlns:wsen=\"" +
						WSEN +
						"\">" +
						"<wsen:EnumerationContext>uuid:CTX-1</wsen:EnumerationContext>" +
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
						"<wsen:Items>" +
						service("Spooler", "Running") +
						"</wsen:Items>" +
						"<wsman:EndOfSequence/>" +
						"</wsen:PullResponse>"
				)
			);

		try (WinRMClient client = builder(PASSWORD).build()) {
			final WqlResult result = client
				.wql("SELECT Name, State FROM Win32_Service")
				.namespace("root\\custom")
				.pageSize(100)
				.pullTimeout(Duration.ofSeconds(5))
				.timeout(Duration.ofSeconds(10))
				.execute();

			assertEquals(1, result.size());
		}

		final List<String> requests = server.decryptedRequests();
		assertEquals(2, requests.size(), () -> String.join("\n---\n", requests));
		final String enumerate = requests.get(0);
		assertTrue(enumerate.contains("<wsman:MaxElements>100</wsman:MaxElements>"), enumerate);
		assertTrue(enumerate.contains("http://schemas.microsoft.com/wbem/wsman/1/wmi/root/custom/*"), enumerate);
		assertTrue(enumerate.contains("<wsman:OperationTimeout>PT10S</wsman:OperationTimeout>"), enumerate);
		final String pull = requests.get(1);
		assertTrue(pull.contains("<wsen:MaxTime>PT5S</wsen:MaxTime>"), pull);
		assertTrue(pull.contains("<wsen:MaxElements>100</wsen:MaxElements>"), pull);
	}

	@Test
	void commandReturnsTypedResult() throws Exception {
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", "CMD-1", "output".getBytes(StandardCharsets.UTF_8)) +
							stream("stderr", "CMD-1", "warn".getBytes(StandardCharsets.UTF_8)),
						done("CMD-1", 3)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			final CommandResult result = client
				.command("mycommand.exe")
				.workingDirectory("C:\\Temp")
				.charset(StandardCharsets.UTF_8)
				.execute();

			assertEquals("output", result.stdout());
			assertEquals("warn", result.stderr());
			assertEquals(3, result.exitCode());
			assertNotNull(result.elapsed());
		}

		final List<String> requests = server.decryptedRequests();
		final String create = requests.get(0);
		assertTrue(create.contains("<rsp:WorkingDirectory>C:\\Temp</rsp:WorkingDirectory>"), create);
		assertTrue(requests.get(1).contains("mycommand.exe"), requests.get(1));
	}

	@Test
	void commandDetectsTheOutputCharsetOnceAndCachesIt() throws Exception {
		server
			// First command: the client detects the remote code set with one WQL query...
			.enqueue(200, envelope(enumerationDone(instance("Win32_OperatingSystem", "CodeSet", "1252"))))
			// ...then runs the command in a fresh shell.
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "first".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0)))
			)
			.enqueue(200, envelope(signalResponse()))
			// Second command: no second WQL — the charset is cached, and the shell is reused.
			.enqueue(200, envelope(commandResponse("CMD-2")))
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-2", "second".getBytes(StandardCharsets.UTF_8)), done("CMD-2", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			assertEquals("first", client.command("first.exe").execute().stdout());
			assertEquals("second", client.command("second.exe").execute().stdout());
		}

		final List<String> requests = server.decryptedRequests();
		final long codeSetQueries = requests.stream().filter(r -> r.contains("SELECT CodeSet FROM Win32_OperatingSystem"))
			.count();
		assertEquals(1, codeSetQueries, () -> String.join("\n---\n", requests));
		final long shellCreations = requests.stream().filter(r -> r.contains("<rsp:InputStreams>")).count();
		assertEquals(1, shellCreations, "the second command must reuse the shell");
	}

	@Test
	void wsmanFaultSurfacesAsTypedException() throws Exception {
		server.enqueue(
			500,
			fault(
				"2150858778",
				"The WS-Management service cannot process the request.",
				"The WMI service or the WMI provider returned an unknown error: WBEM_E_INVALID_CLASS"
			)
		);

		try (WinRMClient client = builder(PASSWORD).build()) {
			final WinRMFaultException e = assertThrows(
				WinRMFaultException.class,
				() -> client.wql("SELECT Name FROM No_Such_Class").execute()
			);
			// The structured fields carry what callers previously had to extract with contains().
			assertEquals(500, e.getHttpStatus());
			assertEquals("2150858778", e.getFaultCode());
			assertEquals("The WS-Management service cannot process the request.", e.getFaultReason());
			assertTrue(e.getFaultDetail().contains("WBEM_E_INVALID_CLASS"), e.getFaultDetail());
			// And the message keeps the legacy format.
			assertTrue(e.getMessage().contains("Enumerate failed"), e.getMessage());
			assertTrue(e.getMessage().contains("WSManFault 2150858778"), e.getMessage());
		}
	}

	@Test
	void wrongPasswordSurfacesAsTypedAuthenticationException() {
		try (WinRMClient client = builder("wrong-password").build()) {
			final WinRMAuthenticationException e = assertThrows(
				WinRMAuthenticationException.class,
				() -> client.wql("SELECT Name FROM Win32_Service").execute()
			);
			assertEquals(
				"Authentication error on http://127.0.0.1:" + server.port() + "/wsman with user name \"FAKE\\user\"",
				e.getMessage()
			);
		}
	}

	@Test
	void invalidWqlIsRejectedBeforeAnythingIsSent() {
		try (WinRMClient client = builder(PASSWORD).build()) {
			assertThrows(WqlSyntaxException.class, () -> client.wql("HELLO WORLD").execute());
		}
		assertTrue(server.decryptedRequests().isEmpty());
	}

	@Test
	void slowServerSurfacesAsTypedTimeoutException() {
		// The response is scripted to arrive after the client's whole-operation deadline.
		server.enqueueDelayed(200, envelope(enumerationDone(service("Spooler", "Running"))), 5_000);

		try (WinRMClient client = builder(PASSWORD).timeout(Duration.ofMillis(500)).build()) {
			assertThrows(WinRMTimeoutException.class, () -> client.wql("SELECT Name FROM Win32_Service").execute());
		}
	}

	@Test
	void closedClientRejectsOperationsAndCloseIsIdempotent() {
		final WinRMClient client = builder(PASSWORD).build();
		client.close();
		client.close();
		assertThrows(IllegalStateException.class, () -> client.wql("SELECT Name FROM Win32_Service").execute());
	}
}
