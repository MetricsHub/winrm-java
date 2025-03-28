package org.metricshub.winrm.service.client.auth.ntlm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2024 Metricshub
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

import java.util.function.LongUnaryOperator;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.Credentials;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.message.BasicHeader;

/**
 * Code from io.cloudsoft.winrm4j.client.ntlm.NtlmMasqAsSpnegoScheme
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class NtlmMasqAsSpnegoScheme extends NTLMScheme {

	private static final LongUnaryOperator FLAG_MODIFIER = flags ->
		flags |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_SIGN |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_SEAL |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_KEY_EXCH;

	public NtlmMasqAsSpnegoScheme() {
		super(newDefaultNtlmEngine());
	}

	private static NTLMEngine newDefaultNtlmEngine() {
		return new NTLMEngineImpl() {
			@Override
			public Integer getDefaultFlags() {
				final Long flags = (long) Type1Message.getDefaultFlags();
				return (int) FLAG_MODIFIER.applyAsLong(flags);
			}
		};
	}

	@Override
	public String getSchemeName() {
		return AuthSchemes.SPNEGO;
	}

	@Override
	public Header authenticate(final Credentials credentials, final HttpRequest httpRequest)
		throws AuthenticationException {
		final Header header = super.authenticate(credentials, httpRequest);

		// code from winrm4j implementation: https://github.com/cloudsoft/winrm4j
		return new BasicHeader(header.getName(), header.getValue().replace("NTLM", getSchemeName()));
	}
}
