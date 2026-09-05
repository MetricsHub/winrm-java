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

/**
 * Base for authentication schemes that exchange **plaintext SOAP inside TLS**: there is no NTLM
 * message sealing, so {@link #wrap(byte[])} and {@link #unwrap(HttpTransport.Response)} are
 * pass-throughs and the only difference between subclasses is the handshake and the
 * connection-bound state it holds. Today that is {@link KerberosAuthScheme} (SPNEGO, HTTPS-only)
 * and {@link BasicAuthScheme} (stateless {@code Authorization} header, HTTPS in practice).
 * <p>
 * Subclasses own the authenticated flag and implement {@link #authenticate(HttpTransport)} and
 * {@link #reset()}; everything else is shared here.
 */
abstract class PlaintextSoapAuthScheme implements AuthScheme {

	/** The content type of the plaintext SOAP body these schemes exchange. */
	protected static final String SOAP_CONTENT_TYPE = "application/soap+xml;charset=UTF-8";

	// Set by authenticate() and cleared by reset(): whether this connection is currently
	// authenticated. volatile — see WinRMSession's notes on why visibility, not atomics, is needed.
	protected volatile boolean authenticated;

	@Override
	public final boolean isAuthenticated() {
		return authenticated;
	}

	@Override
	public final byte[] wrap(final byte[] soapUtf8) {
		// No message protection: the SOAP travels plaintext. Confidentiality comes from TLS (HTTPS),
		// which is why only HTTPS-backed schemes may extend this base.
		return soapUtf8;
	}

	@Override
	public final String wrapContentType() {
		return SOAP_CONTENT_TYPE;
	}

	@Override
	public final byte[] unwrap(final HttpTransport.Response response) {
		return response.body;
	}
}
