package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The close-vs-in-flight-operation race for the Basic scheme (review round 3): when {@code close()}
 * cannot acquire the connection permit because an operation still holds it (a timed-out worker
 * blocked on a socket read), the LAST operation to release the connection must still erase the
 * Base64 credential, so a wiped password can never be re-derived from a closed client.
 */
class BasicAuthCloseRaceTest {

	private static final String USERNAME = "domain\\user";
	private static final char[] PASSWORD = "s3cret-Passw0rd".toCharArray();
	private static final long TIMEOUT = 30_000L;

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer("domain", "user", new String(PASSWORD));
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void closeWhileOperationInFlightStillErasesTheBasicCredential() throws Exception {
		// The Basic credential is a reversible Base64 copy of the password. The client sends it on
		// every request, so the fake server expects it. The single scripted response is DELAYED,
		// so the worker thread is still blocked on the socket read — and still holding the
		// connection permit — when close() runs.
		final BasicAuthScheme scheme = new BasicAuthScheme(USERNAME, PASSWORD);
		final WsmanClient client = new WsmanClient(
			"127.0.0.1",
			server.port(),
			TIMEOUT,
			null,
			false,
			scheme,
			USERNAME,
			65001,
			0,
			0L
		);
		server.withBasicAuth(headerFor(USERNAME));
		// One delayed response: the worker blocks on the Enumerate's socket read (holding the
		// permit) and, once it is served, returns an OPEN enumeration that keeps the permit until
		// close() runs — long after the worker is parked.
		server.enqueueDelayed(200, plaintextEnvelope("enumerate"), 1500L);

		final Thread worker = new Thread(
			() -> {
				try {
					client.openWql("ROOT/CIMV2", "SELECT Name FROM Win32_Service", TIMEOUT, 1000, 0, false);
				} catch (final Exception ignored) {
					// close() hard-cuts the read (or the server 401s/500s): the outcome is irrelevant —
					// what matters is that the operation ran to the end and released the connection.
				}
			},
			"basic-close-race-worker"
		);
		worker.start();

		// Wait until the worker has authenticated and sent its first request, so it is now blocked
		// on the delayed read and still holds the connection permit. Seeing the Authorization header
		// on the server is the proof — no fixed sleep, so the test is not sensitive to scheduling.
		for (int i = 0; i < 1000 && server.requestAuthorizations().isEmpty(); i++) {
			Thread.sleep(5L);
		}
		client.close();
		worker.join(30_000L);
		assertFalse(worker.isAlive(), "the worker should have completed once the delayed read ended");

		// Whichever path erased it (close() acquiring the permit, or the worker's release), the
		// credential is gone: no live header, no authenticated flag.
		assertFalse(scheme.isAuthenticated());
		assertThrows(IllegalStateException.class, scheme::requestAuthorization);
	}

	private static String headerFor(final String username) {
		return "Basic "
			+ java.util.Base64.getEncoder().encodeToString(
				(username + ":" + new String(PASSWORD)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
			);
	}

	private static String plaintextEnvelope(final String marker) {
		return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body>" + marker
			+ "</s:Body></s:Envelope>";
	}
}
