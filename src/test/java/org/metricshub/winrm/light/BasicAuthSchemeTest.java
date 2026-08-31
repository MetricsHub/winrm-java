package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	void resetErasesTheDerivedCredential() throws Exception {
		// close() always runs reset(), and the Base64 value is a reversible copy of the credential,
		// so the reset must leave no live copy of the password in the scheme.
		final char[] password = "password".toCharArray();
		final BasicAuthScheme scheme = new BasicAuthScheme("user", password);
		scheme.authenticate(new HttpTransport("localhost", 1, 1000));
		assertTrue(scheme.isAuthenticated());
		assertEquals(
			"Basic " + Base64.getEncoder().encodeToString(
				"user:password".getBytes(
					StandardCharsets.UTF_8
				)
			),
			scheme.requestAuthorization()
		);

		scheme.reset();
		assertFalse(scheme.isAuthenticated());
		// With the caller's password wiped, the erased credential cannot be re-derived — the next
		// request must fail loudly rather than silently send a header of zeros.
		java.util.Arrays.fill(password, '\0');
		assertThrows(Exception.class, scheme::requestAuthorization);
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
	void thePasswordArrayIsNotCopiedIntoAnImmutableString() {
		// The scheme keeps the caller's char[] only as a reference (like the NTLM scheme) and
		// derives the header on demand, so the caller remains the single owner of the secret and
		// can wipe it: a header derived before the wipe still works, and the scheme never builds a
		// String copy of the password.
		final char[] password = "s3cret".toCharArray();
		final BasicAuthScheme scheme = new BasicAuthScheme("user", password);
		final String header = scheme.requestAuthorization();
		assertEquals(
			"Basic " + Base64.getEncoder().encodeToString(
				"user:s3cret".getBytes(
					StandardCharsets.UTF_8
				)
			),
			header
		);

		java.util.Arrays.fill(password, '\0');
		// The already-derived (and not yet reset) header is still servable...
		assertEquals(header, scheme.requestAuthorization());
		// ...and after reset() the wiped array cannot be re-derived — loud failure, no zero header.
		scheme.reset();
		assertThrows(Exception.class, scheme::requestAuthorization);
	}
}
