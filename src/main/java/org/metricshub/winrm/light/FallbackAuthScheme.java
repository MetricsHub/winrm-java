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

import java.util.List;

/**
 * Tries several {@link AuthScheme}s in the caller's order, using the first whose handshake succeeds
 * (e.g. {@code [KERBEROS, NTLM]}: attempt Kerberos, fall back to NTLM). Once a scheme authenticates
 * it becomes the active one for the rest of the connection; on reconnect the fallback runs again.
 *
 * <p>Fallback triggers on a failed handshake — the common case being Kerberos unavailable (no TGT,
 * no reachable KDC, unconfigured realm), which fails client-side before any SOAP is sent.
 */
final class FallbackAuthScheme implements AuthScheme {

	private final List<AuthScheme> candidates;
	private AuthScheme active;

	FallbackAuthScheme(final List<AuthScheme> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			throw new IllegalArgumentException("At least one authentication scheme is required");
		}
		this.candidates = candidates;
	}

	@Override
	public String authenticate(final HttpTransport transport) throws Exception {
		Exception lastFailure = null;
		for (int i = 0; i < candidates.size(); i++) {
			final AuthScheme candidate = candidates.get(i);
			try {
				final String authorization = candidate.authenticate(transport);
				active = candidate;
				return authorization;
			} catch (final Exception e) {
				lastFailure = e;
				candidate.reset();
				// Give the next scheme a clean connection — a partial handshake may have left the socket
				// mid-stream or the server may have closed it.
				if (i < candidates.size() - 1) {
					transport.close();
				}
			}
		}
		throw new IllegalStateException(
			"All requested authentication schemes failed" +
			(lastFailure == null ? "" : " (last: " + lastFailure.getMessage() + ")"),
			lastFailure
		);
	}

	@Override
	public boolean isAuthenticated() {
		return active != null && active.isAuthenticated();
	}

	@Override
	public void reset() {
		if (active != null) {
			active.reset();
			active = null;
		}
	}

	@Override
	public byte[] wrap(final byte[] soapUtf8) {
		return active.wrap(soapUtf8);
	}

	@Override
	public String wrapContentType() {
		return active.wrapContentType();
	}

	@Override
	public byte[] unwrap(final HttpTransport.Response response) throws Exception {
		return active.unwrap(response);
	}
}
