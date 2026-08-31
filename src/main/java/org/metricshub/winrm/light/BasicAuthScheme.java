package org.metricshub.winrm.light;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2026 MetricsHub
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * HTTP Basic authentication scheme. The credential (base64 of {@code user:password}) rides the
 * {@code Authorization} header of EVERY request, so there is no stateful handshake and no message
 * protection — the payload travels as plaintext SOAP. Confidentiality therefore relies on the
 * transport: over HTTPS (TLS) the credential and the SOAP are protected; over plain HTTP they are
 * sent in the clear and must not be used.
 * <p>
 * The caller's {@code char[]} password is kept only as a reference (never copied into a
 * {@code String}), exactly like the NTLM scheme, so the caller remains the single owner of the
 * secret and can wipe it after {@code close()}. The derived {@code Authorization} header is held
 * as a wipeable {@code byte[]}, because Base64 is reversible: it is erased on
 * {@link #reset()} — which {@code close()} always runs — and re-derived from the caller's still
 * live array if the connection is (re)established before the caller wipes it.
 */
final class BasicAuthScheme extends PlaintextSoapAuthScheme {

	// The full "Basic <base64>" header, held wipeable: it is a reversible copy of the credential,
	// so it must not outlive the caller's own password array (erased in reset()).
	private byte[] authorization;
	private final String username;
	private final char[] password;

	/**
	 * @param username the account name exactly as the caller gave it (a domain-qualified name
	 *        keeps its domain prefix, which is how the server locates the account)
	 * @param password the account password, kept as {@code char[]} so the caller owns the single
	 *        wipeable copy of the secret
	 */
	BasicAuthScheme(final String username, final char[] password) {
		this.username = username;
		this.password = password;
		this.authorization = buildAuthorizationHeader(username, password);
	}

	/**
	 * Build the full {@code Authorization} header value, encoding the password to UTF-8
	 * straight from the caller's {@code char[]} (a {@link CharBuffer} view) — the secret is
	 * never copied into a {@code String}, per the credentials contract.
	 *
	 * @param username the account name (may be domain-qualified)
	 * @param password the account password
	 * @return the ASCII bytes of {@code Basic <base64(user:password)>}
	 */
	private static byte[] buildAuthorizationHeader(final String username, final char[] password) {
		final byte[] user = username.getBytes(StandardCharsets.UTF_8);
		final byte[] secret = new byte[password.length * 3]; // UTF-8 never exceeds 3 bytes/char
		final ByteBuffer secretBuffer = ByteBuffer.wrap(secret);
		final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
		final CoderResult result = encoder.encode(CharBuffer.wrap(password), secretBuffer, true);
		// A reported error can only be a malformed-input one: an unpaired surrogate in the password.
		if (result.isError()) {
			throw new IllegalArgumentException("The password contains unpaired surrogate characters");
		}
		encoder.flush(secretBuffer);
		final int secretLength = secretBuffer.position();
		final byte[] raw = new byte[user.length + 1 + secretLength];
		System.arraycopy(user, 0, raw, 0, user.length);
		raw[user.length] = (byte) ':';
		System.arraycopy(secret, 0, raw, user.length + 1, secretLength);
		Arrays.fill(secret, (byte) 0);
		final byte[] base64 = Base64.getEncoder().encode(raw);
		Arrays.fill(raw, (byte) 0);
		final byte[] header = new byte["Basic ".length() + base64.length];
		System.arraycopy("Basic ".getBytes(StandardCharsets.US_ASCII), 0, header, 0, "Basic ".length());
		System.arraycopy(base64, 0, header, "Basic ".length(), base64.length);
		Arrays.fill(base64, (byte) 0);
		return header;
	}

	@Override
	public String authenticate(final HttpTransport transport) throws Exception {
		// No handshake: Basic has no server challenge. Mark the connection authenticated so the
		// client proceeds straight to the first real request (which carries the Authorization header).
		// A reset() when the connection dropped may have erased the credential, so re-derive it
		// from the caller's still-live password array (the same behavior as the NTLM scheme, which
		// keeps the password by reference to re-handshake a dropped connection).
		if (authorization == null) {
			authorization = buildAuthorizationHeader(username, password);
		}
		authenticated = true;
		return null;
	}

	@Override
	public String requestAuthorization() {
		// Stateless: the same header repeats on every request, not just the first.
		if (authorization == null) {
			throw new IllegalStateException("The Basic credential was erased before the connection was re-authenticated");
		}
		return new String(authorization, StandardCharsets.US_ASCII);
	}

	@Override
	public void reset() {
		// close() always reaches here, and the Base64 value is a reversible copy of the credential:
		// erase it so wiping the caller's char[] afterward leaves no live copy of the password.
		authenticated = false;
		if (authorization != null) {
			Arrays.fill(authorization, (byte) 0);
			authorization = null;
		}
	}
}
