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
import java.util.Locale;
import java.util.Random;
import org.apache.http.impl.auth.NTLMEngineException;
import org.metricshub.winrm.service.client.encryption.CipherGen;
import org.metricshub.winrm.service.client.encryption.EncryptionUtils;

/**
 * Type 3 message assembly class
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public class Type3Message extends NTLMMessage {

	/** Secure random generator */
	static final java.security.SecureRandom RND_GEN;

	static {
		java.security.SecureRandom rnd = null;
		try {
			rnd = java.security.SecureRandom.getInstance("SHA1PRNG");
		} catch (final Exception ignore) {}
		RND_GEN = rnd;
	}

	// Response flags from the type2 message
	private final int type2Flags;

	private final byte[] domainBytes;
	private final byte[] hostBytes;
	private final byte[] userBytes;

	private byte[] lmResp;
	private byte[] ntResp;
	private final byte[] sessionKey;
	private final byte[] exportedSessionKey;

	/** More primitive constructor: don't include cert or previous messages.
	 */
	Type3Message(
		final String domain,
		final String host,
		final String user,
		final String password,
		final byte[] nonce,
		final int type2Flags,
		final String target,
		final byte[] targetInformation
	) throws NTLMEngineException {
		final Random random = RND_GEN;
		if (random == null) {
			throw new NTLMEngineException("Random generator not available");
		}

		final long currentTime = System.currentTimeMillis();

		// Save the flags
		this.type2Flags = type2Flags;

		// Strip off domain name from the host!
		final String unqualifiedHost = NTLMEngineImpl.convertHost(host);
		// Use only the base domain name!
		final String unqualifiedDomain = NTLMEngineImpl.convertDomain(domain);

		byte[] responseTargetInformation = targetInformation;

		// Create a cipher generator class.  Use domain BEFORE it gets modified!
		final CipherGen gen = new CipherGen(
			random,
			currentTime,
			unqualifiedDomain,
			user,
			password,
			nonce,
			target,
			responseTargetInformation
		);

		// Use the new code to calculate the responses, including v2 if that
		// seems warranted.
		byte[] userSessionKey;
		try {
			// This conditional may not work on Windows Server 2008 R2 and above, where it has not yet
			// been tested
			if (
				((type2Flags & NTLMEngineUtils.FLAG_TARGETINFO_PRESENT) != 0) && targetInformation != null && target != null
			) {
				// NTLMv2
				ntResp = gen.getNTLMv2Response();
				lmResp = gen.getLMv2Response();
				if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_LAN_MANAGER_KEY) != 0) {
					userSessionKey = gen.getLanManagerSessionKey();
				} else {
					userSessionKey = gen.getNTLMv2UserSessionKey();
				}
			} else {
				// NTLMv1
				if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_NTLM2_SESSION) != 0) {
					// NTLM2 session stuff is requested
					ntResp = gen.getNTLM2SessionResponse();
					lmResp = gen.getLM2SessionResponse();
					if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_LAN_MANAGER_KEY) != 0) {
						userSessionKey = gen.getLanManagerSessionKey();
					} else {
						userSessionKey = gen.getNTLM2SessionResponseUserSessionKey();
					}
				} else {
					ntResp = gen.getNTLMResponse();
					lmResp = gen.getLMResponse();
					if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_LAN_MANAGER_KEY) != 0) {
						userSessionKey = gen.getLanManagerSessionKey();
					} else {
						userSessionKey = gen.getNTLMUserSessionKey();
					}
				}
			}
		} catch (final NTLMEngineException e) {
			// This likely means we couldn't find the MD4 hash algorithm -
			// fail back to just using LM
			ntResp = new byte[0];
			lmResp = gen.getLMResponse();
			if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_LAN_MANAGER_KEY) != 0) {
				userSessionKey = gen.getLanManagerSessionKey();
			} else {
				userSessionKey = gen.getLMUserSessionKey();
			}
		}

		if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_SIGN) != 0) {
			if ((type2Flags & NTLMEngineUtils.FLAG_REQUEST_EXPLICIT_KEY_EXCH) != 0) {
				exportedSessionKey = gen.getSecondaryKey();
				try {
					sessionKey = EncryptionUtils.calculateRC4(exportedSessionKey, userSessionKey);
				} catch (final Exception e) {
					throw new NTLMEngineException(e.getMessage(), e);
				}
			} else {
				sessionKey = userSessionKey;
				exportedSessionKey = sessionKey;
			}
		} else {
			sessionKey = null;
			exportedSessionKey = null;
		}
		final Charset charset = NTLMEngineUtils.getCharset(type2Flags);
		hostBytes = unqualifiedHost != null ? unqualifiedHost.getBytes(charset) : null;
		domainBytes = unqualifiedDomain != null ? unqualifiedDomain.toUpperCase(Locale.ROOT).getBytes(charset) : null;
		userBytes = user.getBytes(charset);
	}

	int getType2Flags() {
		return type2Flags;
	}

	byte[] getExportedSessionKey() {
		return exportedSessionKey;
	}

	/** Assemble the response */
	@Override
	protected void buildMessage() {
		final int ntRespLen = ntResp.length;
		final int lmRespLen = lmResp.length;

		final int domainLen = domainBytes != null ? domainBytes.length : 0;
		final int hostLen = hostBytes != null ? hostBytes.length : 0;
		final int userLen = userBytes.length;
		final int sessionKeyLen;
		if (sessionKey != null) {
			sessionKeyLen = sessionKey.length;
		} else {
			sessionKeyLen = 0;
		}

		// Calculate the layout within the packet
		final int lmRespOffset = 72; // allocate space for the version
		final int ntRespOffset = lmRespOffset + lmRespLen;
		final int domainOffset = ntRespOffset + ntRespLen;
		final int userOffset = domainOffset + domainLen;
		final int hostOffset = userOffset + userLen;
		final int sessionKeyOffset = hostOffset + hostLen;
		final int finalLength = sessionKeyOffset + sessionKeyLen;

		// Start the response. Length includes signature and type
		prepareResponse(finalLength, 3);

		// LM Resp Length (twice)
		addUShort(lmRespLen);
		addUShort(lmRespLen);

		// LM Resp Offset
		addULong(lmRespOffset);

		// NT Resp Length (twice)
		addUShort(ntRespLen);
		addUShort(ntRespLen);

		// NT Resp Offset
		addULong(ntRespOffset);

		// Domain length (twice)
		addUShort(domainLen);
		addUShort(domainLen);

		// Domain offset.
		addULong(domainOffset);

		// User Length (twice)
		addUShort(userLen);
		addUShort(userLen);

		// User offset
		addULong(userOffset);

		// Host length (twice)
		addUShort(hostLen);
		addUShort(hostLen);

		// Host offset
		addULong(hostOffset);

		// Session key length (twice)
		addUShort(sessionKeyLen);
		addUShort(sessionKeyLen);

		// Session key offset
		addULong(sessionKeyOffset);

		// Flags.
		addULong(
			/*
				//FLAG_WORKSTATION_PRESENT |
				//FLAG_DOMAIN_PRESENT |

				// Required flags
				(type2Flags & FLAG_REQUEST_LAN_MANAGER_KEY) |
				(type2Flags & FLAG_REQUEST_NTLMv1) |
				(type2Flags & FLAG_REQUEST_NTLM2_SESSION) |

				// Protocol version request
				FLAG_REQUEST_VERSION |

				// Recommended privacy settings
				(type2Flags & FLAG_REQUEST_ALWAYS_SIGN) |
				(type2Flags & FLAG_REQUEST_SEAL) |
				(type2Flags & FLAG_REQUEST_SIGN) |

				// These must be set according to documentation, based on use of SEAL above
				(type2Flags & FLAG_REQUEST_128BIT_KEY_EXCH) |
				(type2Flags & FLAG_REQUEST_56BIT_ENCRYPTION) |
				(type2Flags & FLAG_REQUEST_EXPLICIT_KEY_EXCH) |

				(type2Flags & FLAG_TARGETINFO_PRESENT) |
				(type2Flags & FLAG_REQUEST_UNICODE_ENCODING) |
				(type2Flags & FLAG_REQUEST_TARGET)
					*/
			type2Flags
		);

		// Version
		addUShort(0x0105);
		// Build
		addULong(2600);
		// NTLM revision
		addUShort(0x0f00);

		// Add the actual data
		addBytes(lmResp);
		addBytes(ntResp);
		addBytes(domainBytes);
		addBytes(userBytes);
		addBytes(hostBytes);
		if (sessionKey != null) {
			addBytes(sessionKey);
		}
	}
}
