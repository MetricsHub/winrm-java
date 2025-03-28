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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32;
import org.apache.cxf.message.Message;
import org.apache.http.auth.Credentials;
import org.metricshub.winrm.service.client.auth.ntlm.NTCredentialsWithEncryption;
import org.metricshub.winrm.service.client.auth.ntlm.NTLMEngineUtils;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.NtlmEncryptionUtils release
 * 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class NtlmEncryptionUtils {

	public static final String ENCRYPTED_BOUNDARY_PREFIX = "--Encrypted Boundary";
	public static final String ENCRYPTED_BOUNDARY_CR = ENCRYPTED_BOUNDARY_PREFIX + "\r\n";
	public static final String ENCRYPTED_BOUNDARY_END = ENCRYPTED_BOUNDARY_PREFIX + "--\r\n";

	protected final NTCredentialsWithEncryption credentials;

	private NtlmEncryptionUtils(final NTCredentialsWithEncryption credentials) {
		this.credentials = credentials;
	}

	static NtlmEncryptionUtils of(final Credentials credentials) {
		return credentials instanceof NTCredentialsWithEncryption
			? new NtlmEncryptionUtils((NTCredentialsWithEncryption) credentials)
			: null;
	}

	static NtlmEncryptionUtils of(final Message message) {
		final Credentials credentials = (Credentials) message.getExchange().get(Credentials.class.getName());
		return of(credentials);
	}

	public byte[] encryptAndSign(final Message message, final byte[] messageBody) {
		try (final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			out.write(ENCRYPTED_BOUNDARY_CR.getBytes());
			out.write(("\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n").getBytes());

			// message.get(Message.CONTENT_TYPE); - if we need the action
			// Content-Type -> application/soap+xml;
			// action="http://schemas.xmlsoap.org/ws/2004/09/transfer/Create"
			out.write(
				String
					.format("\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length=%d\r\n", messageBody.length)
					.getBytes()
			);

			out.write(ENCRYPTED_BOUNDARY_CR.getBytes());
			out.write("\tContent-Type: application/octet-stream\r\n".getBytes());

			// for credssh chunking might be needed, but not for ntlm

			writeNtlmEncrypted(messageBody, out);

			out.write(ENCRYPTED_BOUNDARY_END.getBytes());

			message.put(
				Message.CONTENT_TYPE,
				"multipart/encrypted;protocol=\"application/HTTP-SPNEGO-session-encrypted\";" +
				"boundary=\"Encrypted Boundary\""
			);
			message.put(Message.ENCODING, null);

			return out.toByteArray();
		} catch (final Exception e) {
			throw new IllegalStateException("Cannot encrypt WinRM message", e);
		}
	}

	private byte[] seal(final byte[] in) {
		return credentials.getStatefulEncryptor().update(in);
	}

	private void writeNtlmEncrypted(final byte[] messageBody, final ByteArrayOutputStream encrypted) throws IOException {
		long seqNum = credentials.getSequenceNumberOutgoing().incrementAndGet();

		try (
			final ByteArrayOutputStream signatureOs = new ByteArrayOutputStream();
			final ByteArrayOutputStream sealedOs = new ByteArrayOutputStream()
		) {
			// seal first, even though appended afterwards, because encryptor is stateful
			sealedOs.write(seal(messageBody));

			calculateSignature(
				messageBody,
				seqNum,
				signatureOs,
				credentials,
				NTCredentialsWithEncryption::getClientSigningKey,
				this::seal
			);

			encrypted.write(ByteArrayUtils.getLittleEndianUnsignedInt(signatureOs.size()));
			encrypted.write(signatureOs.toByteArray());
			encrypted.write(sealedOs.toByteArray());
		}
	}

	public void decrypt(final Message message) {
		new Decryptor(credentials).handle(message);
	}

	static void calculateSignature(
		final byte[] messageBody,
		final long seqNum,
		final ByteArrayOutputStream signature,
		final NTCredentialsWithEncryption credentials,
		final Function<NTCredentialsWithEncryption, byte[]> signingKeyFunction,
		final UnaryOperator<byte[]> sealer
	) throws IOException {
		if (credentials.hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY)) {
			// also see HMACMD5 in NTLMEngineIpml
			byte[] checksum = EncryptionUtils.hmacMd5(
				signingKeyFunction.apply(credentials),
				ByteArrayUtils.concat(ByteArrayUtils.getLittleEndianUnsignedInt(seqNum), messageBody)
			);

			checksum = Arrays.copyOfRange(checksum, 0, 8);

			if (credentials.hasNegotiateFlag(NTLMEngineUtils.NTLMSSP_NEGOTIATE_KEY_EXCH)) {
				checksum = sealer.apply(checksum);
			}
			// version
			signature.write(new byte[] { 1, 0, 0, 0 });
			// checksum
			signature.write(checksum);
			// seq num
			signature.write(ByteArrayUtils.getLittleEndianUnsignedInt(seqNum));
		} else {
			final CRC32 crc = new CRC32();
			crc.update(messageBody);
			final long messageCrc = crc.getValue();

			// version
			signature.write(new byte[] { 1, 0, 0, 0 });
			// random pad
			signature.write(sealer.apply(ByteArrayUtils.getLittleEndianUnsignedInt(0)));
			// checksum
			signature.write(sealer.apply(ByteArrayUtils.getLittleEndianUnsignedInt(messageCrc)));
			// seq num
			signature.write(sealer.apply(ByteArrayUtils.getLittleEndianUnsignedInt(seqNum)));
		}
	}
}
