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
import java.util.Locale;

/**
 * NTLM (masqueraded as Negotiate) authentication scheme. Over plain HTTP it seals the SOAP with the
 * NTLM session keys ({@code multipart/encrypted}); over HTTPS it authenticates only and sends
 * plaintext SOAP inside TLS.
 */
final class NtlmAuthScheme implements AuthScheme {

	// Type 1 flags over plain HTTP: engine defaults + SIGN | SEAL | KEY_EXCH (matches
	// NtlmMasqAsSpnegoScheme). Message sealing is what protects the SOAP over an unencrypted transport.
	private static final int TYPE1_FLAGS_ENCRYPTED = (int) (Type1Message.getDefaultFlags() |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_SIGN |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_SEAL |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_KEY_EXCH);

	// Type 1 flags over HTTPS: engine defaults only. TLS already provides confidentiality/integrity, so
	// we authenticate WITHOUT negotiating sealing and exchange plaintext SOAP — claiming SEAL but then
	// sending plaintext would make the server reject the message.
	private static final int TYPE1_FLAGS_PLAIN = (int) Type1Message.getDefaultFlags();

	private static final String SOAP_CONTENT_TYPE = "application/soap+xml;charset=UTF-8";
	private static final byte[] PRE_AUTH_BOGUS = "AWAITING_ENCRYPTION_KEYS".getBytes(StandardCharsets.US_ASCII);

	private final boolean https;
	private final WinRMSession session;

	NtlmAuthScheme(final String domain, final String username, final String password, final boolean https) {
		this.https = https;
		// Uppercase the domain: NTOWFv2 (and thus the NTLM session key) is computed over it, the
		// Type 3 DomainName field goes on the wire uppercased, and the server derives its session
		// key from the uppercased value. A lowercase domain here passes authentication but fails
		// message integrity (server-side seal mismatch → HTTP 400).
		// Workstation is left empty in the Type 3 message, matching the reference client.
		final String upperDomain = domain == null ? null : domain.toUpperCase(Locale.ROOT);
		this.session = new WinRMSession(upperDomain, null, username, password);
	}

	@Override
	public String authenticate(final HttpTransport transport) throws Exception {
		// Request 0: unauthenticated probe (bogus body), mirroring the reference client.
		transport.post("/wsman", PRE_AUTH_BOGUS, SOAP_CONTENT_TYPE, null);

		// Request A: Type 1 under the Negotiate header. No keys yet, so send the bogus placeholder.
		final String type1 = new Type1Message(null, null, https ? TYPE1_FLAGS_PLAIN : TYPE1_FLAGS_ENCRYPTED).getResponse();
		final HttpTransport.Response challenge = transport.post(
			"/wsman",
			PRE_AUTH_BOGUS,
			SOAP_CONTENT_TYPE,
			"Negotiate " + type1
		);
		if (challenge.status != 401) {
			throw new IllegalStateException("Expected HTTP 401 with an NTLM challenge, got HTTP " + challenge.status);
		}
		final String type2 = challenge.negotiateToken();
		if (type2 == null) {
			throw new IllegalStateException(
				"No Negotiate challenge token in response: " + challenge.allHeaders("www-authenticate")
			);
		}

		final Type2Message challengeMessage = new Type2Message(type2);
		final Type3Message type3Message = new Type3Message(
			session.getDomain(),
			session.getWorkstation(),
			session.getUsername(),
			session.getPassword(),
			challengeMessage.getChallenge(),
			challengeMessage.getFlags(),
			challengeMessage.getTarget(),
			challengeMessage.getTargetInfo()
		);
		final String type3 = type3Message.getResponse();
		if (https) {
			// No sealing over TLS: authenticate the connection but derive no RC4 keys.
			session.markAuthenticated();
		} else {
			session.applyKeys(type3Message);
		}
		return "Negotiate " + type3;
	}

	@Override
	public boolean isAuthenticated() {
		return session.isAuthenticated();
	}

	@Override
	public void reset() {
		session.reset();
	}

	@Override
	public byte[] wrap(final byte[] soapUtf8) {
		return https ? soapUtf8 : NtlmCrypto.encryptAndSign(session, soapUtf8);
	}

	@Override
	public String wrapContentType() {
		return https ? SOAP_CONTENT_TYPE : NtlmCrypto.ENCRYPTED_CONTENT_TYPE;
	}

	@Override
	public byte[] unwrap(final HttpTransport.Response response) {
		if (https) {
			// Over TLS the response body is plaintext application/soap+xml; TLS already guarantees
			// confidentiality and integrity, so there is no multipart/encrypted envelope to unseal.
			return response.body;
		}
		final String contentType = response.firstHeader("content-type");
		// Once the NTLM session is authenticated, the seal is the ONLY thing protecting response
		// integrity over plaintext HTTP. A non-encrypted body (from a proxy, a misconfigured server,
		// or an on-path attacker returning a forged HTTP 200/500) has not passed the HMAC check, so it
		// must never be parsed as a trusted WSMan response. This is only reached after the handshake,
		// so an encrypted content type is always required.
		if (contentType == null || !contentType.startsWith("multipart/encrypted")) {
			throw new IllegalStateException(
				"Refusing to parse an unencrypted WSMan response after authentication (Content-Type: " + contentType + ")"
			);
		}
		return NtlmCrypto.decrypt(session, response.body);
	}
}
