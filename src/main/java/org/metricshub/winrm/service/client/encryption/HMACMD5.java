package org.metricshub.winrm.service.client.encryption;

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

import java.security.MessageDigest;

/**
 * Cryptography support - HMACMD5 - algorithmically based on various web
 * resources by Karl Wright
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public class HMACMD5 {

	private final byte[] ipad;
	private final byte[] opad;
	private final MessageDigest md5;

	HMACMD5(final byte[] input) {
		byte[] key = input;
		md5 = EncryptionUtils.getMD5();

		// Initialize the pad buffers with the key
		ipad = new byte[64];
		opad = new byte[64];

		int keyLength = key.length;
		if (keyLength > 64) {
			// Use MD5 of the key instead, as described in RFC 2104
			md5.update(key);
			key = md5.digest();
			keyLength = key.length;
		}
		int i = 0;
		while (i < keyLength) {
			ipad[i] = (byte) (key[i] ^ (byte) 0x36);
			opad[i] = (byte) (key[i] ^ (byte) 0x5c);
			i++;
		}
		while (i < 64) {
			ipad[i] = (byte) 0x36;
			opad[i] = (byte) 0x5c;
			i++;
		}

		// Very important: processChallenge the digest with the ipad buffer
		md5.reset();
		md5.update(ipad);
	}

	/** Grab the current digest. This is the "answer". */
	byte[] getOutput() {
		final byte[] digest = md5.digest();
		md5.update(opad);
		return md5.digest(digest);
	}

	/** Update by adding a complete array */
	void update(final byte[] input) {
		md5.update(input);
	}
}
