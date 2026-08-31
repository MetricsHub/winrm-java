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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HTTP Basic authentication scheme. The credential (base64 of {@code user:password}) rides the
 * {@code Authorization} header of EVERY request, so there is no stateful handshake and no message
 * protection — the payload travels as plaintext SOAP. Confidentiality therefore relies on the
 * transport: over HTTPS (TLS) the credential and the SOAP are protected; over plain HTTP they are
 * sent in the clear and must not be used.
 * <p>
 * The credential is computed from the caller's {@code char[]} password and held as an immutable
 * header value; the password array itself is never retained, so it can be wiped by the caller after
 * closing the client.
 */
final class BasicAuthScheme extends PlaintextSoapAuthScheme {

	private final String authorizationHeader;

	/**
	 * @param username the account name (without any {@code DOMAIN\} prefix)
	 * @param password the account password, kept as {@code char[]} so the caller owns the single
	 *        wipeable copy of the secret
	 */
	BasicAuthScheme(final String username, final char[] password) {
		this.authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(
			buildCredential(username, password)
		);
	}

	/**
	 * Encode {@code user:password} to UTF-8 straight from the caller's {@code char[]} password,
	 * never forming a {@code String} copy of the secret (the credentials contract: the caller owns
	 * the single wipeable array, and no live copy of the password may outlive {@code close()}).
	 */
	private static byte[] buildCredential(final String username, final char[] password) {
		final StringBuilder user = new StringBuilder(username.length() + 1 + password.length);
		user.append(username);
		user.append(':');
		for (final char c : password) {
			user.append(c);
		}
		return user.toString().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public String authenticate(final HttpTransport transport) throws Exception {
		// No handshake: Basic has no server challenge. Mark the connection authenticated so the
		// client proceeds straight to the first real request (which carries the Authorization header).
		authenticated = true;
		return null;
	}

	@Override
	public String requestAuthorization() {
		// Stateless: the same header repeats on every request, not just the first.
		return authorizationHeader;
	}

	@Override
	public void reset() {
		authenticated = false;
	}
}
