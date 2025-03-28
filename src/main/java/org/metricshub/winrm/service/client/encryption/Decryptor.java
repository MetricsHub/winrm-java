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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.message.Message;
import org.metricshub.winrm.service.client.auth.ntlm.NTCredentialsWithEncryption;
import org.metricshub.winrm.service.client.auth.ntlm.NTLMEngineUtils;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.NtlmEncryptionUtils.Decryptor
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class Decryptor {

	private final NTCredentialsWithEncryption credentials;
	private byte[] rawBytes;
	private byte[] encryptedPayloadBytes;
	private int index;
	private int lastBlockStart;
	private int lastBlockEnd;
	private byte[] signatureBytes;
	private byte[] sealedBytes;
	private byte[] unsealedBytes;

	public Decryptor(final NTCredentialsWithEncryption credentials) {
		this.credentials = credentials;
	}

	public void handle(final Message message) {
		final Object contentType = message.get(Message.CONTENT_TYPE);

		final boolean isEncrypted = contentType != null && contentType.toString().startsWith("multipart/encrypted");

		if (isEncrypted) {
			if (credentials == null) {
				throw new IllegalStateException("Encrypted payload from server when no credentials with encryption known");
			}
			if (!credentials.isAuthenticated()) {
				throw new IllegalStateException("Encrypted payload from server when not authenticated");
			}

			try {
				decrypt(message);
			} catch (final Exception e) {
				throw new IllegalStateException(e);
			}
		} else {
			if (credentials != null && credentials.isAuthenticated()) {
				throw new IllegalStateException(
					"Unencrypted payload from server when authenticated and encryption is required"
				);
			}
		}
	}

	void decrypt(final Message message) throws IOException {
		try (final InputStream in = message.getContent(InputStream.class)) {
			rawBytes = IOUtils.readBytesFromStream(in);
		}

		unwrap();

		final int signatureLength = (int) ByteArrayUtils.readLittleEndianUnsignedInt(encryptedPayloadBytes, 0);
		signatureBytes = Arrays.copyOfRange(encryptedPayloadBytes, 4, 4 + signatureLength);
		sealedBytes = Arrays.copyOfRange(encryptedPayloadBytes, 4 + signatureLength, encryptedPayloadBytes.length);

		unseal();

		// should set length and type headers - but they don't seem to be needed!

		verify();

		message.setContent(InputStream.class, new ByteArrayInputStream(unsealedBytes));
	}

	private void verify() throws IOException {
		final long seqNum = ByteArrayUtils.readLittleEndianUnsignedInt(signatureBytes, 12);
		final int checkSumOffset = credentials.hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY)
			? 4
			: 8;

		final byte[] checksum = Arrays.copyOfRange(signatureBytes, checkSumOffset, 12);

		try (final ByteArrayOutputStream signature = new ByteArrayOutputStream()) {
			NtlmEncryptionUtils.calculateSignature(
				unsealedBytes,
				seqNum,
				signature,
				credentials,
				NTCredentialsWithEncryption::getServerSigningKey,
				credentials.getStatefulDecryptor()::update
			);

			final byte[] expectedChecksum = Arrays.copyOfRange(signature.toByteArray(), checkSumOffset, 12);
			final long expectedSeqNum = ByteArrayUtils.readLittleEndianUnsignedInt(signature.toByteArray(), 12);

			if (!Arrays.equals(checksum, expectedChecksum)) {
				throw new IllegalStateException(
					String.format(
						"Checksum mismatch\n%s--\n%s",
						ByteArrayUtils.formatHexDump(checksum),
						ByteArrayUtils.formatHexDump(expectedChecksum)
					)
				);
			}

			if (expectedSeqNum != seqNum) {
				throw new IllegalStateException(String.format("Sequence number mismatch: %d != %d", seqNum, expectedSeqNum));
			}
		}

		credentials.getSequenceNumberIncoming().incrementAndGet();
	}

	void unwrap() {
		index = 0;
		skipOver(NtlmEncryptionUtils.ENCRYPTED_BOUNDARY_CR);
		skipUntil("\n" + NtlmEncryptionUtils.ENCRYPTED_BOUNDARY_CR);
		skipUntil("\r\n");

		// for credssh de-chunking might be needed, but not for ntlm

		lastBlockStart = index;
		lastBlockEnd = rawBytes.length - NtlmEncryptionUtils.ENCRYPTED_BOUNDARY_END.length();
		index = lastBlockEnd;
		skipOver(NtlmEncryptionUtils.ENCRYPTED_BOUNDARY_END);

		encryptedPayloadBytes = Arrays.copyOfRange(rawBytes, lastBlockStart, lastBlockEnd);
	}

	void skipOver(final String s) {
		skipOver(s.getBytes());
	}

	void skipOver(final byte[] expected) {
		int i = 0;
		while (i < expected.length) {
			if (index >= rawBytes.length) {
				throw new IllegalStateException(
					String.format(
						"Invalid format for response from server; terminated early (%d) when expecting '%s'\n%s",
						i,
						new String(expected),
						ByteArrayUtils.formatHexDump(rawBytes)
					)
				);
			}

			if (expected[i++] != rawBytes[index++]) {
				throw new IllegalStateException(
					String.format(
						"Invalid format for response from server; mismatch at position %d (%d) when expecting '%s'\n%s",
						index,
						i,
						new String(expected),
						ByteArrayUtils.formatHexDump(rawBytes)
					)
				);
			}
		}
	}

	void skipUntil(final String str) {
		final byte[] expected = str.getBytes();
		int nextBlock = index;
		outer:while (true) {
			for (int i = 0; i < expected.length && nextBlock + i < rawBytes.length; i++) {
				if (nextBlock + i >= rawBytes.length) {
					throw new IllegalStateException(
						String.format(
							"Invalid format for response from server; terminated early (%d) when looking for '%s'\n%s",
							i,
							new String(expected),
							ByteArrayUtils.formatHexDump(rawBytes)
						)
					);
				}
				if (expected[i] != rawBytes[nextBlock + i]) {
					nextBlock++;
					continue outer;
				}
			}
			lastBlockStart = index;
			lastBlockEnd = nextBlock;
			index = nextBlock + expected.length;
			return;
		}
	}

	private void unseal() {
		unsealedBytes = credentials.getStatefulDecryptor().update(sealedBytes);
	}
}
