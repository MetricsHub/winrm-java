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

import java.util.Locale;

/**
 * Type 1 message assembly class
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
class Type1Message extends NTLMMessage {

	private final byte[] hostBytes;
	private final byte[] domainBytes;
	private final int flags;

	Type1Message(final String domain, final String host, final Integer flags) {
		super();
		this.flags = flags == null ? getDefaultFlags() : flags;

		// Strip off domain name from the host!
		final String unqualifiedHost = NTLMEngineImpl.convertHost(host);
		// Use only the base domain name!
		final String unqualifiedDomain = NTLMEngineImpl.convertDomain(domain);

		hostBytes = unqualifiedHost != null ? unqualifiedHost.getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED) : null;
		domainBytes =
			unqualifiedDomain != null
				? unqualifiedDomain.toUpperCase(Locale.ROOT).getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED)
				: null;
	}

	static int getDefaultFlags() {
		return ( // Required flags
			NTLMEngineUtils.FLAG_REQUEST_NTLM_V1 |
			NTLMEngineUtils.FLAG_REQUEST_NTLM2_SESSION |
			NTLMEngineUtils.FLAG_REQUEST_VERSION |
			NTLMEngineUtils.FLAG_REQUEST_ALWAYS_SIGN |
			NTLMEngineUtils.FLAG_REQUEST_128BIT_KEY_EXCH |
			NTLMEngineUtils.FLAG_REQUEST_56BIT_ENCRYPTION |
			NTLMEngineUtils.FLAG_REQUEST_UNICODE_ENCODING
		);
	}

	/**
	 * Getting the response involves building the message before returning it
	 */
	@Override
	protected void buildMessage() {
		int domainBytesLength = 0;
		if (domainBytes != null) {
			domainBytesLength = domainBytes.length;
		}
		int hostBytesLength = 0;
		if (hostBytes != null) {
			hostBytesLength = hostBytes.length;
		}

		// Now, build the message. Calculate its length first, including signature or type.
		final int finalLength = 32 + 8 + hostBytesLength + domainBytesLength;

		// Set up the response. This will initialize the signature, message, type, and flags.
		prepareResponse(finalLength, 1);

		// Flags. These are the complete set of flags we support.
		addULong(flags);

		// Domain length (two times).
		addUShort(domainBytesLength);
		addUShort(domainBytesLength);

		// Domain offset.
		addULong(hostBytesLength + 32 + 8);

		// Host length (two times).
		addUShort(hostBytesLength);
		addUShort(hostBytesLength);

		// Host offset (always 32 + 8).
		addULong(32 + 8);

		// Version
		addUShort(0x0105);
		// Build
		addULong(2600);
		// NTLM revision
		addUShort(0x0f00);

		// Host (workstation) String.
		if (hostBytes != null) {
			addBytes(hostBytes);
		}
		// Domain String.
		if (domainBytes != null) {
			addBytes(domainBytes);
		}
	}
}
