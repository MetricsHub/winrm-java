package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the ordered-fallback state machine without a network. */
class FallbackAuthSchemeTest {

	/** A stand-in AuthScheme whose handshake can be made to fail on/after a chosen call. */
	private static final class FakeScheme implements AuthScheme {

		private final String name;
		private final int failFromCall; // fail on this 1-based authenticate() call onward; MAX_VALUE = never
		private final String requestAuthorization; // non-null mimics a stateless scheme (e.g. Basic)
		private boolean authenticated;
		private int authenticateCalls;

		FakeScheme(final String name, final int failFromCall) {
			this(name, failFromCall, null);
		}

		FakeScheme(final String name, final int failFromCall, final String requestAuthorization) {
			this.name = name;
			this.failFromCall = failFromCall;
			this.requestAuthorization = requestAuthorization;
		}

		@Override
		public String requestAuthorization() {
			return requestAuthorization;
		}

		@Override
		public String authenticate(final HttpTransport transport) throws Exception {
			authenticateCalls++;
			if (authenticateCalls >= failFromCall) {
				authenticated = false;
				throw new IllegalStateException(name + " handshake failed");
			}
			authenticated = true;
			return "Negotiate " + name;
		}

		@Override
		public boolean isAuthenticated() {
			return authenticated;
		}

		@Override
		public void reset() {
			authenticated = false;
		}

		@Override
		public byte[] wrap(final byte[] soapUtf8) {
			return soapUtf8;
		}

		@Override
		public String wrapContentType() {
			return "application/soap+xml;charset=UTF-8";
		}

		@Override
		public byte[] unwrap(final HttpTransport.Response response) {
			return response.body;
		}
	}

	private static final int NEVER = Integer.MAX_VALUE;
	private static final int ALWAYS = 1;

	// Never connects — the fake schemes ignore it, and FallbackAuthScheme only calls close() on it.
	private static HttpTransport dummyTransport() {
		return new HttpTransport("localhost", 1, 1000);
	}

	@Test
	void clientSideFailureFallsBackToNextScheme() throws Exception {
		final FakeScheme kerberos = new FakeScheme("kerberos", ALWAYS); // fails during the handshake
		final FakeScheme ntlm = new FakeScheme("ntlm", NEVER);
		final FallbackAuthScheme fallback = new FallbackAuthScheme(List.of(kerberos, ntlm));

		assertEquals("Negotiate ntlm", fallback.authenticate(dummyTransport()));
		assertTrue(fallback.isAuthenticated());
		assertEquals(1, kerberos.authenticateCalls);
		assertEquals(1, ntlm.authenticateCalls);
	}

	@Test
	void advanceMovesPastAServerRejectedScheme() throws Exception {
		// Both handshakes succeed client-side; the first is "rejected server-side" via advance().
		final FakeScheme kerberos = new FakeScheme("kerberos", NEVER);
		final FakeScheme ntlm = new FakeScheme("ntlm", NEVER);
		final FallbackAuthScheme fallback = new FallbackAuthScheme(List.of(kerberos, ntlm));
		final HttpTransport transport = dummyTransport();

		assertEquals("Negotiate kerberos", fallback.authenticate(transport));
		assertTrue(fallback.advance()); // server rejected kerberos -> move to ntlm
		assertFalse(fallback.isAuthenticated()); // active cleared until re-authenticated
		assertEquals("Negotiate ntlm", fallback.authenticate(transport));
		assertFalse(fallback.advance()); // ntlm is the last candidate
	}

	@Test
	void reAuthFailureOfActiveSchemeFallsThrough() throws Exception {
		// Kerberos succeeds initially, then fails on the second handshake (e.g. TGT expired on reconnect).
		final FakeScheme kerberos = new FakeScheme("kerberos", 2);
		final FakeScheme ntlm = new FakeScheme("ntlm", NEVER);
		final FallbackAuthScheme fallback = new FallbackAuthScheme(List.of(kerberos, ntlm));
		final HttpTransport transport = dummyTransport();

		assertEquals("Negotiate kerberos", fallback.authenticate(transport)); // picks kerberos
		fallback.reset(); // simulate a dropped connection
		// Re-auth of kerberos now fails, so it must fall through to ntlm rather than throwing.
		assertEquals("Negotiate ntlm", fallback.authenticate(transport));
		assertEquals(2, kerberos.authenticateCalls);
	}

	@Test
	void allSchemesFailingThrows() {
		final FallbackAuthScheme fallback = new FallbackAuthScheme(
			List.of(new FakeScheme("kerberos", ALWAYS), new FakeScheme("ntlm", ALWAYS))
		);
		assertThrows(IllegalStateException.class, () -> fallback.authenticate(dummyTransport()));
	}

	@Test
	void requestAuthorizationForwardsToTheActiveStatelessScheme() throws Exception {
		// A stateless candidate (e.g. Basic) repeats its Authorization header on EVERY request, so
		// the wrapper must forward it — the interface default of null would drop the header and
		// every request would 401. Here Basic is the first (active) candidate of a fallback list.
		final FakeScheme basic = new FakeScheme("basic", NEVER, "Basic dXNlcjpwYXNz");
		final FakeScheme ntlm = new FakeScheme("ntlm", NEVER);
		final FallbackAuthScheme fallback = new FallbackAuthScheme(List.of(basic, ntlm));

		fallback.authenticate(dummyTransport()); // Basic wins the fallback and becomes active
		assertEquals("Basic dXNlcjpwYXNz", fallback.requestAuthorization());
	}

	@Test
	void requestAuthorizationIsNullWhileUnauthenticated() throws Exception {
		// Before any handshake there is no active scheme, and the header must be null (the
		// client then runs the handshake, which for Basic marks the connection authenticated).
		final FallbackAuthScheme fallback = new FallbackAuthScheme(
			List.of(new FakeScheme("basic", NEVER, "Basic dXNlcjpwYXNz"))
		);
		assertNull(fallback.requestAuthorization());

		fallback.authenticate(dummyTransport());
		assertEquals("Basic dXNlcjpwYXNz", fallback.requestAuthorization());
	}
}
