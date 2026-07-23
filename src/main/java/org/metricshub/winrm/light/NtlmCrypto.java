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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * NTLM message sealing (encrypt+sign) and unsealing (decrypt+verify) for WinRM's
 * {@code multipart/encrypted} framing. Ported verbatim in wire behavior from
 * NtlmEncryptionUtils + Decryptor, but operating on byte[] and WinRMSession instead of
 * CXF Message + apache NTCredentials.
 */
final class NtlmCrypto {

	static final String ENCRYPTED_CONTENT_TYPE = "multipart/encrypted;protocol=\"application/HTTP-SPNEGO-session-encrypted\";boundary=\"Encrypted Boundary\"";

	private static final String BOUNDARY_CR = "--Encrypted Boundary\r\n";
	private static final String BOUNDARY_END = "--Encrypted Boundary--\r\n";

	private NtlmCrypto() {}

	static byte[] encryptAndSign(final WinRMSession session, final byte[] messageBody) {
		try (final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			out.write(BOUNDARY_CR.getBytes(StandardCharsets.US_ASCII));
			out.write("\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n".getBytes(StandardCharsets.US_ASCII));
			out.write(
				String
					.format("\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length=%d\r\n", messageBody.length)
					.getBytes(StandardCharsets.US_ASCII)
			);
			out.write(BOUNDARY_CR.getBytes(StandardCharsets.US_ASCII));
			out.write("\tContent-Type: application/octet-stream\r\n".getBytes(StandardCharsets.US_ASCII));

			final long seqNum = session.getSequenceNumberOutgoing().incrementAndGet();
			// Seal the body FIRST (advances the stateful cipher), even though the signature is written before it.
			final byte[] sealed = session.seal(messageBody);
			final ByteArrayOutputStream signature = new ByteArrayOutputStream();
			calculateSignature(session, messageBody, seqNum, signature, true);

			out.write(ByteArrayUtils.getLittleEndianUnsignedInt(signature.size()));
			out.write(signature.toByteArray());
			out.write(sealed);

			out.write(BOUNDARY_END.getBytes(StandardCharsets.US_ASCII));
			return out.toByteArray();
		} catch (final Exception e) {
			throw new IllegalStateException("Cannot encrypt WinRM message", e);
		}
	}

	static byte[] decrypt(final WinRMSession session, final byte[] rawBytes) {
		final byte[] payload = unwrap(rawBytes);
		final int signatureLength = (int) ByteArrayUtils.readLittleEndianUnsignedInt(payload, 0);
		final byte[] signatureBytes = Arrays.copyOfRange(payload, 4, 4 + signatureLength);
		final byte[] sealedBytes = Arrays.copyOfRange(payload, 4 + signatureLength, payload.length);

		final byte[] unsealed = session.unseal(sealedBytes);
		verify(session, unsealed, signatureBytes);
		return unsealed;
	}

	private static void verify(final WinRMSession session, final byte[] unsealed, final byte[] signatureBytes) {
		final long seqNum = ByteArrayUtils.readLittleEndianUnsignedInt(signatureBytes, 12);
		final int checkSumOffset = session.hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY)
			? 4
			: 8;
		final byte[] checksum = Arrays.copyOfRange(signatureBytes, checkSumOffset, 12);
		final ByteArrayOutputStream expected = new ByteArrayOutputStream();
		calculateSignature(session, unsealed, seqNum, expected, false);
		final byte[] expectedChecksum = Arrays.copyOfRange(expected.toByteArray(), checkSumOffset, 12);
		final long expectedSeqNum = ByteArrayUtils.readLittleEndianUnsignedInt(expected.toByteArray(), 12);
		if (!Arrays.equals(checksum, expectedChecksum)) {
			throw new IllegalStateException(
				"Checksum mismatch\n" +
					ByteArrayUtils.formatHexDump(checksum) +
					"--\n" +
					ByteArrayUtils.formatHexDump(expectedChecksum)
			);
		}
		if (expectedSeqNum != seqNum) {
			throw new IllegalStateException(String.format("Sequence number mismatch: %d != %d", seqNum, expectedSeqNum));
		}
		session.getSequenceNumberIncoming().incrementAndGet();
	}

	/**
	 * @param outgoing true to sign an outgoing message (client signing key + client sealing stream),
	 *        false to verify an incoming one (server signing key + server sealing stream).
	 */
	private static void calculateSignature(
		final WinRMSession session,
		final byte[] messageBody,
		final long seqNum,
		final ByteArrayOutputStream signature,
		final boolean outgoing
	) {
		try {
			final byte[] signingKey = outgoing ? session.getClientSigningKey() : session.getServerSigningKey();
			if (session.hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY)) {
				byte[] checksum = EncryptionUtils.hmacMd5(
					signingKey,
					ByteArrayUtils.concat(ByteArrayUtils.getLittleEndianUnsignedInt(seqNum), messageBody)
				);
				checksum = Arrays.copyOfRange(checksum, 0, 8);
				if (session.hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_KEY_EXCH)) {
					checksum = outgoing ? session.seal(checksum) : session.unseal(checksum);
				}
				signature.write(new byte[] { 1, 0, 0, 0 });
				signature.write(checksum);
				signature.write(ByteArrayUtils.getLittleEndianUnsignedInt(seqNum));
			} else {
				final CRC32 crc = new CRC32();
				crc.update(messageBody);
				final long messageCrc = crc.getValue();
				signature.write(new byte[] { 1, 0, 0, 0 });
				signature.write(sealPad(session, outgoing, 0));
				signature.write(sealPad(session, outgoing, messageCrc));
				signature.write(sealPad(session, outgoing, seqNum));
			}
		} catch (final Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] sealPad(final WinRMSession session, final boolean outgoing, final long value) {
		final byte[] v = ByteArrayUtils.getLittleEndianUnsignedInt(value);
		return outgoing ? session.seal(v) : session.unseal(v);
	}

	/** Strip the two MIME parts and return the raw {sig-len | signature | sealed-body} block. */
	private static byte[] unwrap(final byte[] rawBytes) {
		final Cursor c = new Cursor(rawBytes);
		c.skipOver(BOUNDARY_CR);
		c.skipUntil("\n" + BOUNDARY_CR);
		c.skipUntil("\r\n");
		final int start = c.index;
		final int end = rawBytes.length - BOUNDARY_END.length();
		return Arrays.copyOfRange(rawBytes, start, end);
	}

	/** Minimal forward scanner over the response bytes (ported from Decryptor's skip logic). */
	private static final class Cursor {

		private final byte[] bytes;
		private int index;

		Cursor(final byte[] bytes) {
			this.bytes = bytes;
		}

		void skipOver(final String s) {
			final byte[] expected = s.getBytes(StandardCharsets.US_ASCII);
			for (int i = 0; i < expected.length; i++) {
				if (index >= bytes.length || expected[i] != bytes[index++]) {
					throw new IllegalStateException("Unexpected encrypted-response framing at byte " + index);
				}
			}
		}

		void skipUntil(final String s) {
			final byte[] expected = s.getBytes(StandardCharsets.US_ASCII);
			int next = index;
			outer: while (true) {
				for (int i = 0; i < expected.length; i++) {
					if (next + i >= bytes.length) {
						throw new IllegalStateException("Encrypted-response framing terminated early looking for delimiter");
					}
					if (expected[i] != bytes[next + i]) {
						next++;
						continue outer;
					}
				}
				index = next + expected.length;
				return;
			}
		}
	}
}
