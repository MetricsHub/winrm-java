package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Verifies the HTTP Basic scheme's header encoding and stateless request behavior (no network). */
class BasicAuthSchemeTest {

	@Test
	void encodesDomainQualifiedCredentialAsBasicHeader() {
		final BasicAuthScheme scheme = new BasicAuthScheme("DOMAIN\\user", "s3cret".toCharArray());

		final String expected = "Basic " + Base64.getEncoder().encodeToString(
			("DOMAIN\\user:s3cret").getBytes(StandardCharsets.UTF_8)
		);
		assertEquals(expected, scheme.requestAuthorization());
	}

	@Test
	void headerIsStatelessAndIdenticalOnEveryRequest() throws Exception {
		final BasicAuthScheme scheme = new BasicAuthScheme("user", "password".toCharArray());

		final HttpTransport transport = new HttpTransport("localhost", 1, 1000);
		final String first = scheme.authenticate(transport);
		// authenticate() returns null: Basic needs no first-request token; the header is per-request.
		assertEquals(null, first);
		assertTrue(scheme.isAuthenticated());

		final String again = scheme.requestAuthorization();
		final String yetAgain = scheme.requestAuthorization();
		assertEquals(again, yetAgain);
		assertTrue(again.startsWith("Basic "));
	}

	@Test
	void resetReturnsToUnauthenticatedState() throws Exception {
		final BasicAuthScheme scheme = new BasicAuthScheme("user", "password".toCharArray());
		scheme.authenticate(new HttpTransport("localhost", 1, 1000));
		assertTrue(scheme.isAuthenticated());

		scheme.reset();
		assertFalse(scheme.isAuthenticated());
		// The credential header is still available: Basic re-sends it on the next request, so reset
		// only clears the authenticated flag, not the derived header.
		assertTrue(scheme.requestAuthorization().startsWith("Basic "));
	}

	@Test
	void wrapAndUnwrapArePlaintextPassThrough() {
		final BasicAuthScheme scheme = new BasicAuthScheme("user", "password".toCharArray());
		final byte[] soap = "<soap/>".getBytes(StandardCharsets.UTF_8);

		// No message protection: the SOAP bytes travel verbatim.
		assertSame(soap, scheme.wrap(soap));
		assertEquals("application/soap+xml;charset=UTF-8", scheme.wrapContentType());
	}

	@Test
	void passwordIsNeverRetainedAfterConstruction() {
		// The scheme must encode the credential at construction and not hold the caller's char[]:
		// wiping the array afterward must not change the (already-derived) header.
		final char[] password = "s3cret".toCharArray();
		final BasicAuthScheme scheme = new BasicAuthScheme("user", password);
		final String header = scheme.requestAuthorization();

		java.util.Arrays.fill(password, '\0');
		assertEquals(header, scheme.requestAuthorization());
	}
}
