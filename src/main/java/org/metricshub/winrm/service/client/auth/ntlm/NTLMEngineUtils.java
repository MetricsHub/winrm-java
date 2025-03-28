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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.http.impl.auth.NTLMEngineException;

/**
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public class NTLMEngineUtils {

	private NTLMEngineUtils() {}

	/** Unicode encoding */
	public static final Charset UNICODE_LITTLE_UNMARKED = StandardCharsets.UTF_16LE;
	/** Character encoding */
	public static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;

	// Flags we use; descriptions according to:
	// http://davenport.sourceforge.net/ntlm.html
	// and
	// http://msdn.microsoft.com/en-us/library/cc236650%28v=prot.20%29.aspx
	// [MS-NLMP] section 2.2.2.5
	static final int FLAG_REQUEST_UNICODE_ENCODING = 0x00000001; // Unicode string encoding requested
	static final int FLAG_REQUEST_SIGN = 0x00000010; // Requests all messages have a signature attached, in NEGOTIATE message.
	static final int FLAG_REQUEST_LAN_MANAGER_KEY = 0x00000080; // Request Lan Manager key instead of user session key
	static final int FLAG_REQUEST_NTLM_V1 = 0x00000200; // Request NTLMv1 security.  MUST be set in NEGOTIATE and CHALLENGE both
	static final int FLAG_REQUEST_ALWAYS_SIGN = 0x00008000; // Requests a signature block on all messages.  Overridden by REQUEST_SIGN and REQUEST_SEAL.
	static final int FLAG_REQUEST_NTLM2_SESSION = 0x00080000; // From server in challenge, requesting NTLM2 session security
	static final int FLAG_REQUEST_VERSION = 0x02000000; // Request protocol version
	static final int FLAG_TARGETINFO_PRESENT = 0x00800000; // From server in challenge message, indicating targetinfo is present
	static final int FLAG_REQUEST_128BIT_KEY_EXCH = 0x20000000; // Request explicit 128-bit key exchange
	static final int FLAG_REQUEST_EXPLICIT_KEY_EXCH = 0x40000000; // Request explicit key exchange
	static final int FLAG_REQUEST_56BIT_ENCRYPTION = 0x80000000; // Must be used in conjunction with SEAL

	// Code from io.cloudsoft.winrm4j.client.ntlm.NtlmKeys.NegotiateFlags
	// release 0.12.3 @link https://github.com/cloudsoft/winrm4j
	// expanded set of what is in NTLMEngineImpl
	// 0b 10100010_10001010_10000010_00000101
	// 0b 10100010_00001000_10000010_00110001
	public static final long NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000L;
	public static final long NTLMSSP_NEGOTIATE_KEY_EXCH = 0x40000000L;
	static final long NTLMSSP_NEGOTIATE_SEAL = 0x00000020L;
	static final long NTLMSSP_NEGOTIATE_SIGN = 0x00000010L;
	static final long NTLMSSP_NEGOTIATE_56 = 0x80000000L;
	static final long NTLMSSP_NEGOTIATE_128 = 0x20000000L;
	static final long NTLMSSP_NEGOTIATE_LM_KEY = 0x00000080L;

	/**
	 * Find the character set based on the flags.
	 * @param flags is the flags.
	 * @return the character set.
	 */
	static Charset getCharset(final int flags) throws NTLMEngineException {
		if ((flags & FLAG_REQUEST_UNICODE_ENCODING) == 0) {
			return DEFAULT_CHARSET;
		}
		if (UNICODE_LITTLE_UNMARKED == null) {
			throw new NTLMEngineException("Unicode not supported");
		}
		return UNICODE_LITTLE_UNMARKED;
	}
}
