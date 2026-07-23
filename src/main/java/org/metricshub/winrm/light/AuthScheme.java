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

/**
 * Authentication and message protection for one WSMan connection. A scheme owns its handshake, its
 * connection-bound session state, and how it wraps/unwraps the SOAP payload — the two things that
 * differ between NTLM and Kerberos. {@link WsmanClient} is otherwise mechanism-agnostic and just
 * delegates to the scheme, so a new mechanism is added by implementing this interface rather than
 * branching the client.
 *
 * <p>All methods are called while {@code WsmanClient} holds its operation lock, so implementations
 * need no internal synchronization.
 */
interface AuthScheme {
	/**
	 * Run the full authentication handshake over the given transport (which may involve several
	 * request/response legs), leaving the connection authenticated.
	 *
	 * @param transport the connection to authenticate
	 * @return the {@code Authorization} header value to attach to the first real request, or
	 *         {@code null} if none is needed
	 * @throws Exception if the handshake fails
	 */
	String authenticate(HttpTransport transport) throws Exception;

	/** @return whether the connection is currently authenticated. */
	boolean isAuthenticated();

	/**
	 * Drop the authenticated state so the next request re-runs the handshake. Called when the
	 * underlying connection was lost, since the session state is bound to the TCP connection.
	 */
	void reset();

	/**
	 * Encode an outgoing SOAP body for the wire (sealing it over plain HTTP, or passing it through
	 * over HTTPS where TLS provides confidentiality).
	 *
	 * @param soapUtf8 the SOAP envelope, UTF-8 encoded
	 * @return the bytes to send as the request body
	 */
	byte[] wrap(byte[] soapUtf8);

	/** @return the {@code Content-Type} for the body produced by {@link #wrap(byte[])}. */
	String wrapContentType();

	/**
	 * Decode a response body back to plaintext SOAP bytes, verifying integrity where the mechanism
	 * provides it.
	 *
	 * @param response the HTTP response
	 * @return the plaintext SOAP bytes to parse
	 * @throws Exception if the body cannot be trusted or decoded
	 */
	byte[] unwrap(HttpTransport.Response response) throws Exception;

	/**
	 * After the server rejects this scheme on a real request (HTTP 401) — which for Kerberos/NTLM only
	 * surfaces after {@link #authenticate} has returned, because the token/Type-3 rides the first real
	 * request — move to the next candidate of an ordered fallback list, if any. A single scheme cannot
	 * advance.
	 *
	 * @return {@code true} if a further scheme is now available so the caller should re-authenticate and
	 *         retry; {@code false} if there is nothing left to try
	 */
	default boolean advance() {
		return false;
	}
}
