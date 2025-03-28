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

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.WinrmEncryptionUtils
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class EncryptionUtils {

	private EncryptionUtils() {}

	private static final String HMAC_MD5 = "HmacMD5";
	private static final String RC4 = "RC4";

	public static MessageDigest getMD5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (final NoSuchAlgorithmException ex) {
			throw new IllegalStateException("MD5 message digest doesn't seem to exist - fatal error: " + ex.getMessage(), ex);
		}
	}

	public static byte[] md5digest(byte[] bytes) {
		final MessageDigest handle = getMD5();
		handle.update(bytes);
		return handle.digest();
	}

	public static Cipher arc4(byte[] key) {
		// engine needs to be stateful
		try {
			final Cipher rc4 = Cipher.getInstance(RC4);
			rc4.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, RC4));
			return rc4;
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Calculates RC4 */
	public static byte[] calculateRC4(final byte[] value, final byte[] key) {
		try {
			return arc4(key).doFinal(value);
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public static byte[] hmacMd5(byte[] key, byte[] body) {
		try {
			final SecretKeySpec keySpec = new SecretKeySpec(key, HMAC_MD5);
			final Mac mac = Mac.getInstance(HMAC_MD5);
			mac.init(keySpec);
			return mac.doFinal(body);
		} catch (final NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}
}
