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

import java.security.Key;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.apache.http.impl.auth.NTLMEngineException;
import org.metricshub.winrm.service.client.auth.ntlm.NTLMEngineUtils;

/**
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public class CipherGen {

	private final Random random;
	private final long currentTime;

	private final String domain;
	private final String user;
	private final String password;
	private final byte[] challenge;
	private final byte[] targetInformation;

	// Information we can generate but may be passed in (for testing)
	private byte[] clientChallenge;
	private byte[] clientChallenge2;
	private byte[] secondaryKey;
	private byte[] timestamp;

	// Stuff we always generate
	private byte[] lmHash = null;
	private byte[] lmResponse = null;
	private byte[] ntlmHash = null;
	private byte[] ntlmResponse = null;
	private byte[] ntlmv2Hash = null;
	private byte[] lmv2Hash = null;
	private byte[] lmv2Response = null;
	private byte[] ntlmv2Blob = null;
	private byte[] ntlmv2Response = null;
	private byte[] ntlm2SessionResponse = null;
	private byte[] lm2SessionResponse = null;
	private byte[] lmUserSessionKey = null;
	private byte[] ntlmUserSessionKey = null;
	private byte[] ntlmv2UserSessionKey = null;
	private byte[] ntlm2SessionResponseUserSessionKey = null;
	private byte[] lanManagerSessionKey = null;

	public CipherGen(
		final Random random,
		final long currentTime,
		final String domain,
		final String user,
		final String password,
		final byte[] challenge,
		final String target,
		final byte[] targetInformation
	) {
		this.random = random;
		this.currentTime = currentTime;

		this.domain = domain;
		this.user = user;
		this.password = password;
		this.challenge = challenge;
		this.targetInformation = targetInformation;
	}

	/** Calculate and return client challenge */
	private byte[] getClientChallenge() {
		if (clientChallenge == null) {
			clientChallenge = makeRandomChallenge(random);
		}
		return clientChallenge;
	}

	/** Calculate and return second client challenge */
	private byte[] getClientChallenge2() {
		if (clientChallenge2 == null) {
			clientChallenge2 = makeRandomChallenge(random);
		}
		return clientChallenge2;
	}

	/** Calculate and return random secondary key */
	public byte[] getSecondaryKey() {
		if (secondaryKey == null) {
			secondaryKey = makeSecondaryKey(random);
		}
		return secondaryKey;
	}

	/** Calculate and return the LMHash */
	private byte[] getLMHash() throws NTLMEngineException {
		if (lmHash == null) {
			lmHash = lmHash(password);
		}
		return lmHash;
	}

	/** Calculate and return the LMResponse */
	public byte[] getLMResponse() throws NTLMEngineException {
		if (lmResponse == null) {
			lmResponse = lmResponse(getLMHash(), challenge);
		}
		return lmResponse;
	}

	/** Calculate and return the NTLMHash */
	private byte[] getNTLMHash() throws NTLMEngineException {
		if (ntlmHash == null) {
			ntlmHash = ntlmHash(password);
		}
		return ntlmHash;
	}

	/** Calculate and return the NTLMResponse */
	public byte[] getNTLMResponse() throws NTLMEngineException {
		if (ntlmResponse == null) {
			ntlmResponse = lmResponse(getNTLMHash(), challenge);
		}
		return ntlmResponse;
	}

	/** Calculate the LMv2 hash */
	private byte[] getLMv2Hash() throws NTLMEngineException {
		if (lmv2Hash == null) {
			lmv2Hash = lmv2Hash(domain, user, getNTLMHash());
		}
		return lmv2Hash;
	}

	/** Calculate the NTLMv2 hash */
	private byte[] getNTLMv2Hash() throws NTLMEngineException {
		if (ntlmv2Hash == null) {
			ntlmv2Hash = ntlmv2Hash(domain, user, getNTLMHash());
		}
		return ntlmv2Hash;
	}

	/** Calculate a timestamp */
	private byte[] getTimestamp() {
		if (timestamp == null) {
			long time = this.currentTime;
			time += 11644473600000l; // milliseconds from January 1, 1601 -> epoch.
			time *= 10000; // tenths of a microsecond.
			// convert to little-endian byte array.
			timestamp = new byte[8];
			for (int i = 0; i < 8; i++) {
				timestamp[i] = (byte) time;
				time >>>= 8;
			}
		}
		return timestamp;
	}

	/** Calculate the NTLMv2Blob */
	private byte[] getNTLMv2Blob() {
		if (ntlmv2Blob == null) {
			ntlmv2Blob = createBlob(getClientChallenge2(), targetInformation, getTimestamp());
		}
		return ntlmv2Blob;
	}

	/**
	 * Creates the NTLMv2 blob from the given target information block and
	 * client challenge.
	 *
	 * @param targetInformation
	 *			The target information block from the Type 2 message.
	 * @param clientChallenge
	 *			The random 8-byte client challenge.
	 *
	 * @return The blob, used in the calculation of the NTLMv2 Response.
	 */
	private static byte[] createBlob(
		final byte[] clientChallenge,
		final byte[] targetInformation,
		final byte[] timestamp
	) {
		final byte[] blobSignature = new byte[] { (byte) 0x01, (byte) 0x01, (byte) 0x00, (byte) 0x00 };
		final byte[] reserved = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
		final byte[] unknown1 = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
		final byte[] unknown2 = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
		final byte[] blob = new byte[blobSignature.length +
		reserved.length +
		timestamp.length +
		8 +
		unknown1.length +
		targetInformation.length +
		unknown2.length];
		int offset = 0;
		System.arraycopy(blobSignature, 0, blob, offset, blobSignature.length);
		offset += blobSignature.length;
		System.arraycopy(reserved, 0, blob, offset, reserved.length);
		offset += reserved.length;
		System.arraycopy(timestamp, 0, blob, offset, timestamp.length);
		offset += timestamp.length;
		System.arraycopy(clientChallenge, 0, blob, offset, 8);
		offset += 8;
		System.arraycopy(unknown1, 0, blob, offset, unknown1.length);
		offset += unknown1.length;
		System.arraycopy(targetInformation, 0, blob, offset, targetInformation.length);
		offset += targetInformation.length;
		System.arraycopy(unknown2, 0, blob, offset, unknown2.length);
		offset += unknown2.length;
		return blob;
	}

	/** Calculate the NTLMv2Response */
	public byte[] getNTLMv2Response() throws NTLMEngineException {
		if (ntlmv2Response == null) {
			ntlmv2Response = lmv2Response(getNTLMv2Hash(), challenge, getNTLMv2Blob());
		}
		return ntlmv2Response;
	}

	/** Calculate the LMv2Response */
	public byte[] getLMv2Response() throws NTLMEngineException {
		if (lmv2Response == null) {
			lmv2Response = lmv2Response(getLMv2Hash(), challenge, getClientChallenge());
		}
		return lmv2Response;
	}

	/** Get NTLM2SessionResponse */
	public byte[] getNTLM2SessionResponse() throws NTLMEngineException {
		if (ntlm2SessionResponse == null) {
			ntlm2SessionResponse = ntlm2SessionResponse(getNTLMHash(), challenge, getClientChallenge());
		}
		return ntlm2SessionResponse;
	}

	/**
	 * Calculates the NTLM2 Session Response for the given challenge, using the
	 * specified password and client challenge.
	 *
	 * @param ntlmHash
	 * @param challenge
	 * @param clientChallenge
	 * @return The NTLM2 Session Response. This is placed in the NTLM response
	 *		 field of the Type 3 message; the LM response field contains the
	 *		 client challenge, null-padded to 24 bytes.
	 */
	private static byte[] ntlm2SessionResponse(
		final byte[] ntlmHash,
		final byte[] challenge,
		final byte[] clientChallenge
	) throws NTLMEngineException {
		try {
			final MessageDigest md5 = EncryptionUtils.getMD5();
			md5.update(challenge);
			md5.update(clientChallenge);
			final byte[] digest = md5.digest();

			final byte[] sessionHash = new byte[8];
			System.arraycopy(digest, 0, sessionHash, 0, 8);
			return lmResponse(ntlmHash, sessionHash);
		} catch (final NTLMEngineException e) {
			throw (NTLMEngineException) e;
		} catch (final Exception e) {
			throw new NTLMEngineException(e.getMessage(), e);
		}
	}

	/**
	 * Creates the LM Response from the given hash and Type 2 challenge.
	 *
	 * @param hash
	 *			The LM or NTLM Hash.
	 * @param challenge
	 *			The server challenge from the Type 2 message.
	 *
	 * @return The response (either LM or NTLM, depending on the provided hash).
	 */
	private static byte[] lmResponse(final byte[] hash, final byte[] challenge) throws NTLMEngineException {
		try {
			final byte[] keyBytes = new byte[21];
			System.arraycopy(hash, 0, keyBytes, 0, 16);
			final Key lowKey = createDESKey(keyBytes, 0);
			final Key middleKey = createDESKey(keyBytes, 7);
			final Key highKey = createDESKey(keyBytes, 14);
			final Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
			des.init(Cipher.ENCRYPT_MODE, lowKey);
			final byte[] lowResponse = des.doFinal(challenge);
			des.init(Cipher.ENCRYPT_MODE, middleKey);
			final byte[] middleResponse = des.doFinal(challenge);
			des.init(Cipher.ENCRYPT_MODE, highKey);
			final byte[] highResponse = des.doFinal(challenge);
			final byte[] lmResponse = new byte[24];
			System.arraycopy(lowResponse, 0, lmResponse, 0, 8);
			System.arraycopy(middleResponse, 0, lmResponse, 8, 8);
			System.arraycopy(highResponse, 0, lmResponse, 16, 8);
			return lmResponse;
		} catch (final Exception e) {
			throw new NTLMEngineException(e.getMessage(), e);
		}
	}

	/**
	 * Creates the LM Hash of the user's password.
	 *
	 * @param password
	 *			The password.
	 *
	 * @return The LM Hash of the given password, used in the calculation of the
	 *		 LM Response.
	 */
	private static byte[] lmHash(final String password) throws NTLMEngineException {
		try {
			final byte[] oemPassword = password.toUpperCase(Locale.ROOT).getBytes(NTLMEngineUtils.DEFAULT_CHARSET);

			final int length = Math.min(oemPassword.length, 14);
			final byte[] keyBytes = new byte[14];
			System.arraycopy(oemPassword, 0, keyBytes, 0, length);
			final Key lowKey = createDESKey(keyBytes, 0);
			final Key highKey = createDESKey(keyBytes, 7);
			final byte[] magicConstant = "KGS!@#$%".getBytes(NTLMEngineUtils.DEFAULT_CHARSET);
			final Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
			des.init(Cipher.ENCRYPT_MODE, lowKey);
			final byte[] lowHash = des.doFinal(magicConstant);
			des.init(Cipher.ENCRYPT_MODE, highKey);
			final byte[] highHash = des.doFinal(magicConstant);
			final byte[] lmHash = new byte[16];
			System.arraycopy(lowHash, 0, lmHash, 0, 8);
			System.arraycopy(highHash, 0, lmHash, 8, 8);
			return lmHash;
		} catch (final Exception e) {
			throw new NTLMEngineException(e.getMessage(), e);
		}
	}

	/**
	 * Creates a DES encryption key from the given key material.
	 *
	 * @param bytes
	 *			A byte array containing the DES key material.
	 * @param offset
	 *			The offset in the given byte array at which the 7-byte key
	 *			material starts.
	 *
	 * @return A DES encryption key created from the key material starting at
	 *		 the specified offset in the given byte array.
	 */
	private static Key createDESKey(final byte[] bytes, final int offset) {
		final byte[] keyBytes = new byte[7];
		System.arraycopy(bytes, offset, keyBytes, 0, 7);
		final byte[] material = new byte[8];
		material[0] = keyBytes[0];
		material[1] = (byte) ((keyBytes[0] << 7) | ((keyBytes[1] & 0xff) >>> 1));
		material[2] = (byte) ((keyBytes[1] << 6) | ((keyBytes[2] & 0xff) >>> 2));
		material[3] = (byte) ((keyBytes[2] << 5) | ((keyBytes[3] & 0xff) >>> 3));
		material[4] = (byte) ((keyBytes[3] << 4) | ((keyBytes[4] & 0xff) >>> 4));
		material[5] = (byte) ((keyBytes[4] << 3) | ((keyBytes[5] & 0xff) >>> 5));
		material[6] = (byte) ((keyBytes[5] << 2) | ((keyBytes[6] & 0xff) >>> 6));
		material[7] = (byte) (keyBytes[6] << 1);
		oddParity(material);
		return new SecretKeySpec(material, "DES");
	}

	/**
	 * Applies odd parity to the given byte array.
	 *
	 * @param bytes
	 *			The data whose parity bits are to be adjusted for odd parity.
	 */
	private static void oddParity(final byte[] bytes) {
		for (int i = 0; i < bytes.length; i++) {
			final byte b = bytes[i];
			final boolean needsParity =
				(((b >>> 7) ^ (b >>> 6) ^ (b >>> 5) ^ (b >>> 4) ^ (b >>> 3) ^ (b >>> 2) ^ (b >>> 1)) & 0x01) == 0;
			if (needsParity) {
				bytes[i] |= (byte) 0x01;
			} else {
				bytes[i] &= (byte) 0xfe;
			}
		}
	}

	/** Calculate and return LM2 session response */
	public byte[] getLM2SessionResponse() {
		if (lm2SessionResponse == null) {
			final byte[] clntChallenge = getClientChallenge();
			lm2SessionResponse = new byte[24];
			System.arraycopy(clntChallenge, 0, lm2SessionResponse, 0, clntChallenge.length);
			Arrays.fill(lm2SessionResponse, clntChallenge.length, lm2SessionResponse.length, (byte) 0x00);
		}
		return lm2SessionResponse;
	}

	/** Get LMUserSessionKey */
	public byte[] getLMUserSessionKey() throws NTLMEngineException {
		if (lmUserSessionKey == null) {
			lmUserSessionKey = new byte[16];
			System.arraycopy(getLMHash(), 0, lmUserSessionKey, 0, 8);
			Arrays.fill(lmUserSessionKey, 8, 16, (byte) 0x00);
		}
		return lmUserSessionKey;
	}

	/** Get NTLMUserSessionKey */
	public byte[] getNTLMUserSessionKey() throws NTLMEngineException {
		if (ntlmUserSessionKey == null) {
			final MD4 md4 = new MD4();
			md4.update(getNTLMHash());
			ntlmUserSessionKey = md4.getOutput();
		}
		return ntlmUserSessionKey;
	}

	/** GetNTLMv2UserSessionKey */
	public byte[] getNTLMv2UserSessionKey() throws NTLMEngineException {
		if (ntlmv2UserSessionKey == null) {
			final byte[] ntlmv2hash = getNTLMv2Hash();
			final byte[] truncatedResponse = new byte[16];
			System.arraycopy(getNTLMv2Response(), 0, truncatedResponse, 0, 16);
			ntlmv2UserSessionKey = hmacMD5(truncatedResponse, ntlmv2hash);
		}
		return ntlmv2UserSessionKey;
	}

	/** Get NTLM2SessionResponseUserSessionKey */
	public byte[] getNTLM2SessionResponseUserSessionKey() throws NTLMEngineException {
		if (ntlm2SessionResponseUserSessionKey == null) {
			final byte[] ntlm2SessionResponseNonce = getLM2SessionResponse();
			final byte[] sessionNonce = new byte[challenge.length + ntlm2SessionResponseNonce.length];
			System.arraycopy(challenge, 0, sessionNonce, 0, challenge.length);
			System.arraycopy(ntlm2SessionResponseNonce, 0, sessionNonce, challenge.length, ntlm2SessionResponseNonce.length);
			ntlm2SessionResponseUserSessionKey = hmacMD5(sessionNonce, getNTLMUserSessionKey());
		}
		return ntlm2SessionResponseUserSessionKey;
	}

	/** Get LAN Manager session key */
	public byte[] getLanManagerSessionKey() throws NTLMEngineException {
		if (lanManagerSessionKey == null) {
			try {
				final byte[] keyBytes = new byte[14];
				System.arraycopy(getLMHash(), 0, keyBytes, 0, 8);
				Arrays.fill(keyBytes, 8, keyBytes.length, (byte) 0xbd);
				final Key lowKey = createDESKey(keyBytes, 0);
				final Key highKey = createDESKey(keyBytes, 7);
				final byte[] truncatedResponse = new byte[8];
				System.arraycopy(getLMResponse(), 0, truncatedResponse, 0, truncatedResponse.length);
				Cipher des = Cipher.getInstance("DES/ECB/NoPadding");
				des.init(Cipher.ENCRYPT_MODE, lowKey);
				final byte[] lowPart = des.doFinal(truncatedResponse);
				des = Cipher.getInstance("DES/ECB/NoPadding");
				des.init(Cipher.ENCRYPT_MODE, highKey);
				final byte[] highPart = des.doFinal(truncatedResponse);
				lanManagerSessionKey = new byte[16];
				System.arraycopy(lowPart, 0, lanManagerSessionKey, 0, lowPart.length);
				System.arraycopy(highPart, 0, lanManagerSessionKey, lowPart.length, highPart.length);
			} catch (final Exception e) {
				throw new NTLMEngineException(e.getMessage(), e);
			}
		}
		return lanManagerSessionKey;
	}

	/**
	 * Creates the NTLM Hash of the user's password.
	 *
	 * @param password
	 *			The password.
	 *
	 * @return The NTLM Hash of the given password, used in the calculation of
	 *		 the NTLM Response and the NTLMv2 and LMv2 Hashes.
	 */
	private static byte[] ntlmHash(final String password) throws NTLMEngineException {
		if (NTLMEngineUtils.UNICODE_LITTLE_UNMARKED == null) {
			throw new NTLMEngineException("Unicode not supported");
		}
		final byte[] unicodePassword = password.getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED);
		final MD4 md4 = new MD4();
		md4.update(unicodePassword);
		return md4.getOutput();
	}

	/**
	 * Creates the LMv2 Hash of the user's password.
	 *
	 * @return The LMv2 Hash, used in the calculation of the NTLMv2 and LMv2
	 *		 Responses.
	 */
	private static byte[] lmv2Hash(final String domain, final String user, final byte[] ntlmHash)
		throws NTLMEngineException {
		if (NTLMEngineUtils.UNICODE_LITTLE_UNMARKED == null) {
			throw new NTLMEngineException("Unicode not supported");
		}
		final HMACMD5 hmacMD5 = new HMACMD5(ntlmHash);
		// Upper case username, upper case domain!
		hmacMD5.update(user.toUpperCase(Locale.ROOT).getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED));
		if (domain != null) {
			hmacMD5.update(domain.toUpperCase(Locale.ROOT).getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED));
		}
		return hmacMD5.getOutput();
	}

	/**
	 * Creates the NTLMv2 Hash of the user's password.
	 *
	 * @return The NTLMv2 Hash, used in the calculation of the NTLMv2 and LMv2
	 *		 Responses.
	 */
	private static byte[] ntlmv2Hash(final String domain, final String user, final byte[] ntlmHash)
		throws NTLMEngineException {
		if (NTLMEngineUtils.UNICODE_LITTLE_UNMARKED == null) {
			throw new NTLMEngineException("Unicode not supported");
		}
		final HMACMD5 hmacMD5 = new HMACMD5(ntlmHash);
		// Upper case username, mixed case target!!
		hmacMD5.update(user.toUpperCase(Locale.ROOT).getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED));
		if (domain != null) {
			hmacMD5.update(domain.getBytes(NTLMEngineUtils.UNICODE_LITTLE_UNMARKED));
		}
		return hmacMD5.getOutput();
	}

	/**
	 * Creates the LMv2 Response from the given hash, client data, and Type 2
	 * challenge.
	 *
	 * @param hash
	 *			The NTLMv2 Hash.
	 * @param clientData
	 *			The client data (blob or client challenge).
	 * @param challenge
	 *			The server challenge from the Type 2 message.
	 *
	 * @return The response (either NTLMv2 or LMv2, depending on the client
	 *		 data).
	 */
	private static byte[] lmv2Response(final byte[] hash, final byte[] challenge, final byte[] clientData) {
		final HMACMD5 hmacMD5 = new HMACMD5(hash);
		hmacMD5.update(challenge);
		hmacMD5.update(clientData);
		final byte[] mac = hmacMD5.getOutput();
		final byte[] lmv2Response = new byte[mac.length + clientData.length];
		System.arraycopy(mac, 0, lmv2Response, 0, mac.length);
		System.arraycopy(clientData, 0, lmv2Response, mac.length, clientData.length);
		return lmv2Response;
	}

	/** Calculate a challenge block */
	private static byte[] makeRandomChallenge(final Random random) {
		final byte[] rval = new byte[8];
		synchronized (random) {
			random.nextBytes(rval);
		}
		return rval;
	}

	/** Calculate a 16-byte secondary key */
	private static byte[] makeSecondaryKey(final Random random) {
		final byte[] rval = new byte[16];
		synchronized (random) {
			random.nextBytes(rval);
		}
		return rval;
	}

	/** Calculates HMAC-MD5 */
	private static byte[] hmacMD5(final byte[] value, final byte[] key) {
		final HMACMD5 hmacMD5 = new HMACMD5(key);
		hmacMD5.update(value);
		return hmacMD5.getOutput();
	}
}
