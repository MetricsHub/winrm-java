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
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueEnumeration;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellCreation;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WinRMClient;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * The opt-in retry policy for transient connection failures (issue #158): a round trip is retried
 * only while establishing and authenticating the connection — where its request provably never
 * reached the server — and never once the request may have executed. Exercised end to end against
 * {@link FakeWsmanServer}, in-process, no Windows host required.
 */
class WsmanRetryTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";
	private static final long TIMEOUT = 30_000L;

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private LightWinRMService service(final int port, final int retries, final long retryDelayMillis)
		throws Exception {
		final WinRMEndpoint endpoint = new WinRMEndpoint(
			WinRMHttpProtocolEnum.HTTP,
			"127.0.0.1",
			port,
			DOMAIN + "\\" + USER,
			PASSWORD.toCharArray(),
			null
		);
		return LightWinRMService.createInstance(
			endpoint,
			TIMEOUT,
			null,
			List.of(AuthenticationEnum.NTLM),
			null,
			false,
			0,
			retries,
			retryDelayMillis
		);
	}

	/** A local port with no listener: connecting to it is refused immediately. */
	private static int refusedPort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	// --- retryable: the request never reached the server -----------------------

	@Test
	void retriesAConnectionDroppedDuringTheHandshake() throws Exception {
		// The first TCP connection is dropped before anything is read: the authentication handshake
		// fails, the operation's request was never sent — the retry succeeds on a fresh connection.
		server.dropNextConnections(1);
		enqueueEnumeration(server, instance("Win32_Service", "Name", "Spooler"));

		try (LightWinRMService service = service(server.port(), 1, 50L)) {
			final List<Map<String, Object>> rows = service.executeWql("SELECT Name FROM Win32_Service", TIMEOUT);
			assertEquals(1, rows.size());
			assertEquals("Spooler", rows.get(0).get("Name"));
			// The operation itself went out exactly once: at-most-once execution held.
			assertEquals(1, server.decryptedRequests().size());
		}
	}

	@Test
	void failsImmediatelyWithoutARetryPolicy() throws Exception {
		server.dropNextConnections(1);
		enqueueEnumeration(server, instance("Win32_Service", "Name", "Spooler"));

		try (LightWinRMService service = service(server.port(), 0, 0L)) {
			assertThrows(WinRMException.class, () -> service.executeWql("SELECT Name FROM Win32_Service", TIMEOUT));
		}
	}

	@Test
	void exhaustsRetriesAgainstAnUnreachableEndpoint() throws Exception {
		// Nothing listens on the port: every attempt is refused. The configured pauses prove the
		// retries actually ran before the failure was reported.
		final long start = System.currentTimeMillis();
		try (LightWinRMService service = service(refusedPort(), 2, 200L)) {
			assertThrows(WinRMException.class, () -> service.executeWql("SELECT Name FROM Win32_Service", TIMEOUT));
		}
		assertTrue(System.currentTimeMillis() - start >= 400L, "expected two 200 ms retry pauses");
	}

	@Test
	void retryPolicyPlumbsThroughTheFluentBuilder() throws Exception {
		server.dropNextConnections(1);
		enqueueEnumeration(server, instance("Win32_Service", "Name", "Spooler"));

		try (
			WinRMClient client = WinRMClient
				.builder("127.0.0.1")
				.port(server.port())
				.credentials(DOMAIN + "\\" + USER, PASSWORD.toCharArray())
				.retries(1, Duration.ofMillis(50))
				.build()) {
			assertEquals("Spooler", client.wql("SELECT Name FROM Win32_Service").execute().rows().get(0).string("Name"));
		}
	}

	// --- never retried: the request may have executed --------------------------

	@Test
	void doesNotRetryARequestThatReachedTheServer() throws Exception {
		// The shell is created, then the connection is dropped AFTER the Command request was read:
		// the command may have started, so the failure must surface without any second Command.
		enqueueShellCreation(server);
		server.enqueueDrop();

		try (LightWinRMService service = service(server.port(), 3, 10L)) {
			assertThrows(
				WinRMException.class,
				() -> service.executeCommand("echo risky", null, StandardCharsets.UTF_8, TIMEOUT)
			);
			final long commandRequests = server
				.decryptedRequests()
				.stream()
				.filter(request -> request.contains(":CommandLine>"))
				.count();
			assertEquals(1, commandRequests, "a command that may have started must never be re-sent");
		}
	}

	@Test
	void doesNotRetryACredentialRejection() throws Exception {
		final WinRMEndpoint endpoint = new WinRMEndpoint(
			WinRMHttpProtocolEnum.HTTP,
			"127.0.0.1",
			server.port(),
			DOMAIN + "\\" + USER,
			"wrong-password".toCharArray(),
			null
		);
		final long start = System.currentTimeMillis();
		try (
			LightWinRMService service = LightWinRMService.createInstance(
				endpoint,
				TIMEOUT,
				null,
				List.of(AuthenticationEnum.NTLM),
				null,
				false,
				0,
				3,
				60_000L
			)) {
			final WinRMException e = assertThrows(
				WinRMException.class,
				() -> service.executeWql("SELECT Name FROM Win32_Service", TIMEOUT)
			);
			assertTrue(e.getMessage().contains("Authentication error"), e.getMessage());
		}
		// With a 60 s pause configured, a fast failure proves the rejection was not retried.
		assertTrue(System.currentTimeMillis() - start < 30_000L, "a credential rejection must not be retried");
	}

	// --- the wall-clock deadline still governs ---------------------------------

	@Test
	void retryPausesStayInsideTheWallClockDeadline() throws Exception {
		// Retries against an unreachable endpoint would run for ~25 s; the 1.5 s wall-clock deadline
		// must cut them short and report the documented timeout instead.
		try (LightWinRMService service = service(refusedPort(), 50, 500L)) {
			final long start = System.currentTimeMillis();
			assertThrows(TimeoutException.class, () -> service.executeWql("SELECT Name FROM Win32_Service", 1_500L));
			assertTrue(System.currentTimeMillis() - start < 20_000L, "the deadline must cut the retry loop short");
		}
	}

	// --- factory validation -----------------------------------------------------

	@Test
	void rejectsNegativeRetrySettings() {
		final WinRMEndpoint endpoint = new WinRMEndpoint(
			WinRMHttpProtocolEnum.HTTP,
			"127.0.0.1",
			5985,
			DOMAIN + "\\" + USER,
			PASSWORD.toCharArray(),
			null
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> LightWinRMService.createInstance(
				endpoint,
				TIMEOUT,
				null,
				List.of(AuthenticationEnum.NTLM),
				null,
				false,
				0,
				-1,
				0L
			)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> LightWinRMService.createInstance(
				endpoint,
				TIMEOUT,
				null,
				List.of(AuthenticationEnum.NTLM),
				null,
				false,
				0,
				1,
				-1L
			)
		);
	}
}
