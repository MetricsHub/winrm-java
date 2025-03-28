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

import org.apache.commons.codec.binary.Base64;
import org.apache.http.impl.auth.NTLMEngineException;

/**
 * Type 2 message class
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
class Type2Message extends NTLMMessage {

	private final byte[] challenge;
	private String target;
	private byte[] targetInfo;
	private final int flags;

	Type2Message(final String messageBody) throws NTLMEngineException {
		this(Base64.decodeBase64(messageBody.getBytes(NTLMEngineUtils.DEFAULT_CHARSET)));
	}

	private Type2Message(final byte[] message) throws NTLMEngineException {
		super(message, 2);
		// Type 2 message is laid out as follows:
		// First 8 bytes: NTLMSSP[0]
		// Next 4 bytes: Ulong, value 2
		// Next 8 bytes, starting at offset 12: target field (2 ushort lengths, 1 ulong offset)
		// Next 4 bytes, starting at offset 20: Flags, e.g. 0x22890235
		// Next 8 bytes, starting at offset 24: Challenge
		// Next 8 bytes, starting at offset 32: ??? (8 bytes of zeros)
		// Next 8 bytes, starting at offset 40: targetinfo field (2 ushort lengths, 1 ulong offset)
		// Next 2 bytes, major/minor version number (e.g. 0x05 0x02)
		// Next 8 bytes, build number
		// Next 2 bytes, protocol version number (e.g. 0x00 0x0f)
		// Next, various text fields, and a ushort of value 0 at the end

		// Parse out the rest of the info we need from the message
		// The nonce is the 8 bytes starting from the byte in position 24.
		challenge = new byte[8];
		readBytes(challenge, 24);

		flags = readULong(20);

		// Do the target!
		target = null;
		// The TARGET_DESIRED flag is said to not have understood semantics
		// in Type2 messages, so use the length of the packet to decide how to proceed instead
		if (getMessageLength() >= 12 + 8) {
			final byte[] bytes = readSecurityBuffer(12);
			if (bytes.length != 0) {
				target = new String(bytes, NTLMEngineUtils.getCharset(flags));
			}
		}

		// Do the target info!
		targetInfo = null;
		// TARGET_DESIRED flag cannot be relied on, so use packet length
		if (getMessageLength() >= 40 + 8) {
			final byte[] bytes = readSecurityBuffer(40);
			if (bytes.length != 0) {
				targetInfo = bytes;
			}
		}
	}

	/** Get the message length */
	private int getMessageLength() {
		return currentOutputPosition;
	}

	/** Read a bunch of bytes from a position in the message buffer */
	private void readBytes(final byte[] buffer, final int position) throws NTLMEngineException {
		if (messageContents.length < position + buffer.length) {
			throw new NTLMEngineException("NTLM: Message too short");
		}
		System.arraycopy(messageContents, position, buffer, 0, buffer.length);
	}

	/** Read a security buffer from a position within the message buffer */
	private byte[] readSecurityBuffer(final int position) {
		final int length = readUShort(messageContents, position);
		final int offset = readULong(messageContents, position + 4);
		if (messageContents.length < offset + length) {
			return new byte[length];
		}
		final byte[] buffer = new byte[length];
		System.arraycopy(messageContents, offset, buffer, 0, length);
		return buffer;
	}

	private static int readUShort(final byte[] src, final int index) {
		if (src.length < index + 2) {
			return 0;
		}
		return (src[index] & 0xff) | ((src[index + 1] & 0xff) << 8);
	}

	/** Retrieve the challenge */
	byte[] getChallenge() {
		return challenge;
	}

	/** Retrieve the target */
	String getTarget() {
		return target;
	}

	/** Retrieve the target info */
	byte[] getTargetInfo() {
		return targetInfo;
	}

	/** Retrieve the response flags */
	int getFlags() {
		return flags;
	}
}
