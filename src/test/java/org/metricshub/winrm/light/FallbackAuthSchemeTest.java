package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the ordered-fallback state machine without a network. */
class FallbackAuthSchemeTest {

	/** A stand-in AuthScheme whose handshake can be made to fail client-side. */
	private static final class FakeScheme implements AuthScheme {

		private final String name;
		private final boolean failHandshake;
		private boolean authenticated;
		private int authenticateCalls;

		FakeScheme(final String name, final boolean failHandshake) {
			this.name = name;
			this.failHandshake = failHandshake;
		}

		@Override
		public String authenticate(final HttpTransport transport) throws Exception {
			authenticateCalls++;
			if (failHandshake) {
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

	// Never connects — the fake schemes ignore it, and FallbackAuthScheme only calls close() on it.
	private static HttpTransport dummyTransport() {
		return new HttpTransport("localhost", 1, 1000);
	}

	@Test
	void clientSideFailureFallsBackToNextScheme() throws Exception {
		final FakeScheme kerberos = new FakeScheme("kerberos", true); // fails during the handshake
		final FakeScheme ntlm = new FakeScheme("ntlm", false);
		final FallbackAuthScheme fallback = new FallbackAuthScheme(List.of(kerberos, ntlm));

		assertEquals("Negotiate ntlm", fallback.authenticate(dummyTransport()));
		assertTrue(fallback.isAuthenticated());
		assertEquals(1, kerberos.authenticateCalls);
		assertEquals(1, ntlm.authenticateCalls);
	}

	@Test
	void advanceMovesPastAServerRejectedScheme() throws Exception {
		// Both handshakes succeed client-side; the first is "rejected server-side" via advance().
		final FakeScheme kerberos = new FakeScheme("kerberos", false);
		final FakeScheme ntlm = new FakeScheme("ntlm", false);
		final FallbackAuthScheme fallback = new FallbackAuthScheme(List.of(kerberos, ntlm));
		final HttpTransport transport = dummyTransport();

		assertEquals("Negotiate kerberos", fallback.authenticate(transport));
		assertTrue(fallback.advance()); // server rejected kerberos -> move to ntlm
		assertFalse(fallback.isAuthenticated()); // active cleared until re-authenticated
		assertEquals("Negotiate ntlm", fallback.authenticate(transport));
		assertFalse(fallback.advance()); // ntlm is the last candidate
	}

	@Test
	void allSchemesFailingThrows() {
		final FallbackAuthScheme fallback = new FallbackAuthScheme(
			List.of(new FakeScheme("kerberos", true), new FakeScheme("ntlm", true))
		);
		assertThrows(IllegalStateException.class, () -> fallback.authenticate(dummyTransport()));
	}
}
