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
	// The next candidate to try when (re)running the fallback; advanced past a server-rejected scheme.
	private int startIndex;
	private AuthScheme active;

	FallbackAuthScheme(final List<AuthScheme> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			throw new IllegalArgumentException("At least one authentication scheme is required");
		}
		this.candidates = candidates;
	}

	@Override
	public String authenticate(final HttpTransport transport) throws Exception {
		// Run the candidates from startIndex. After a dropped connection startIndex still points at the
		// scheme that last succeeded, so it is retried first; but if that re-authentication now fails
		// (e.g. an expired TGT or a briefly unavailable KDC) we fall through to the remaining candidates
		// rather than abandoning the whole fallback list.
		Exception lastFailure = null;
		for (int i = startIndex; i < candidates.size(); i++) {
			final AuthScheme candidate = candidates.get(i);
			try {
				final String authorization = candidate.authenticate(transport);
				active = candidate;
				startIndex = i;
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
		// A dropped connection: clear the active scheme's session but keep it selected so the next
		// authenticate() re-handshakes with the same (already-accepted) scheme.
		if (active != null) {
			active.reset();
		}
	}

	@Override
	public boolean advance() {
		// The server rejected the active scheme (401 on a real request). Drop it and move to the next
		// candidate so the next authenticate() runs the remaining schemes.
		if (active != null && startIndex + 1 < candidates.size()) {
			active.reset();
			active = null;
			startIndex++;
			return true;
		}
		return false;
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
