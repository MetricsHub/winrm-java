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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;

/**
 * Holds the negotiated NTLM session state: the exported session key, the derived
 * signing/sealing keys, the two stateful RC4 stream ciphers (one per direction), and
 * the message sequence counters. Replaces the CXF/apache-coupled NTCredentialsWithEncryption
 * and folds in the key-derivation logic from NtlmKeys.
 */
final class WinRMSession {

	// Protocol-defined constants: they MUST encode to the same bytes on every JVM, so pin US-ASCII
	// rather than relying on the platform default charset (which could differ, e.g. UTF-16).
	private static final byte[] CLIENT_SIGNING =
		"session key to client-to-server signing key magic constant\0".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SERVER_SIGNING =
		"session key to server-to-client signing key magic constant\0".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] CLIENT_SEALING =
		"session key to client-to-server sealing key magic constant\0".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SERVER_SEALING =
		"session key to server-to-client sealing key magic constant\0".getBytes(StandardCharsets.US_ASCII);

	private final String domain;
	private final String workstation;
	private final String username;
	private final String password;

	private long negotiateFlags;
	private byte[] clientSigningKey;
	private byte[] serverSigningKey;
	private Cipher encryptor;
	private Cipher decryptor;
	private boolean authenticated;

	private final AtomicLong sequenceOutgoing = new AtomicLong(-1);
	private final AtomicLong sequenceIncoming = new AtomicLong(-1);

	WinRMSession(final String domain, final String workstation, final String username, final String password) {
		this.domain = domain;
		this.workstation = workstation;
		this.username = username;
		this.password = password;
	}

	String getDomain() {
		return domain;
	}

	String getWorkstation() {
		return workstation;
	}

	String getUsername() {
		return username;
	}

	String getPassword() {
		return password;
	}

	boolean isAuthenticated() {
		return authenticated;
	}

	boolean hasNegotiateFlag(final long flag) {
		return (negotiateFlags & flag) == flag;
	}

	byte[] getClientSigningKey() {
		return clientSigningKey;
	}

	byte[] getServerSigningKey() {
		return serverSigningKey;
	}

	AtomicLong getSequenceNumberOutgoing() {
		return sequenceOutgoing;
	}

	AtomicLong getSequenceNumberIncoming() {
		return sequenceIncoming;
	}

	/** Continue the outgoing (client) RC4 keystream. */
	byte[] seal(final byte[] in) {
		return encryptor.update(in);
	}

	/** Continue the incoming (server) RC4 keystream. */
	byte[] unseal(final byte[] in) {
		return decryptor.update(in);
	}

	/**
	 * Reset to the unauthenticated state so a fresh NTLM handshake runs. Required when the
	 * underlying TCP connection is lost, because NTLM auth and the RC4 keystreams are bound to it.
	 */
	void reset() {
		authenticated = false;
		negotiateFlags = 0;
		clientSigningKey = null;
		serverSigningKey = null;
		encryptor = null;
		decryptor = null;
		sequenceOutgoing.set(-1);
		sequenceIncoming.set(-1);
	}

	/** Derive signing/sealing keys from the Type 3 exported session key and open both RC4 ciphers. */
	void applyKeys(final Type3Message type3) {
		final byte[] exportedSessionKey = type3.getExportedSessionKey();
		negotiateFlags = type3.getType2Flags();

		clientSigningKey = signKey(exportedSessionKey, CLIENT_SIGNING);
		serverSigningKey = signKey(exportedSessionKey, SERVER_SIGNING);
		encryptor = EncryptionUtils.arc4(sealKey(exportedSessionKey, CLIENT_SEALING));
		decryptor = EncryptionUtils.arc4(sealKey(exportedSessionKey, SERVER_SEALING));
		authenticated = true;
	}

	private static byte[] signKey(final byte[] exportedSessionKey, final byte[] magic) {
		return EncryptionUtils.md5digest(ByteArrayUtils.concat(exportedSessionKey, magic));
	}

	private byte[] sealKey(final byte[] exportedSessionKey, final byte[] magic) {
		if (hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY)) {
			if (hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_128)) {
				return EncryptionUtils.md5digest(ByteArrayUtils.concat(exportedSessionKey, magic));
			}
			if (hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_56)) {
				return EncryptionUtils.md5digest(ByteArrayUtils.concat(Arrays.copyOfRange(exportedSessionKey, 0, 7), magic));
			}
			return EncryptionUtils.md5digest(ByteArrayUtils.concat(Arrays.copyOfRange(exportedSessionKey, 0, 5), magic));
		}
		if (hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_LM_KEY)) {
			throw new UnsupportedOperationException("LM KEY negotiate mode not implemented; use extended session security");
		}
		return exportedSessionKey;
	}
}
