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
 * NTLM message generation, base class
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
class NTLMMessage {

	/** The signature string as bytes in the default encoding */
	private static final byte[] SIGNATURE;

	static {
		final byte[] bytesWithoutNull = "NTLMSSP".getBytes(NTLMEngineUtils.DEFAULT_CHARSET);
		final byte[] target = new byte[bytesWithoutNull.length + 1];
		System.arraycopy(bytesWithoutNull, 0, target, 0, bytesWithoutNull.length);
		target[bytesWithoutNull.length] = (byte) 0x00;

		SIGNATURE = target;
	}

	/** The current response */
	protected byte[] messageContents = null;

	/** The current output position */
	protected int currentOutputPosition = 0;

	/** Constructor to use when message contents are not yet known */
	NTLMMessage() {}

	/** Constructor to use when message bytes are known */
	NTLMMessage(final byte[] message, final int expectedType) throws NTLMEngineException {
		messageContents = message;
		// Look for NTLM message
		if (messageContents.length < SIGNATURE.length) {
			throw new NTLMEngineException("NTLM message decoding error - packet too short");
		}
		int i = 0;
		while (i < SIGNATURE.length) {
			if (messageContents[i] != SIGNATURE[i]) {
				throw new NTLMEngineException("NTLM message expected - instead got unrecognized bytes");
			}
			i++;
		}

		// Check to be sure there's a type 2 message indicator next
		final int type = readULong(SIGNATURE.length);
		if (type != expectedType) {
			throw new NTLMEngineException(
				String.format("NTLM type %d message expected - instead got type %d", expectedType, type)
			);
		}

		currentOutputPosition = messageContents.length;
	}

	/** Read a ulong from a position within the message buffer */
	int readULong(final int position) {
		return readULong(messageContents, position);
	}

	static int readULong(final byte[] src, final int index) {
		if (src.length < index + 4) {
			return 0;
		}
		return (
			(src[index] & 0xff) |
			((src[index + 1] & 0xff) << 8) |
			((src[index + 2] & 0xff) << 16) |
			((src[index + 3] & 0xff) << 24)
		);
	}

	/**
	 * Prepares the object to create a response of the given length.
	 *
	 * @param maxlength
	 *			the maximum length of the response to prepare,
	 *			including the type and the signature (which this method
	 *			adds).
	 */
	void prepareResponse(final int maxlength, final int messageType) {
		messageContents = new byte[maxlength];
		currentOutputPosition = 0;
		addBytes(SIGNATURE);
		addULong(messageType);
	}

	/**
	 * Adds the given byte to the response.
	 *
	 * @param b
	 *			the byte to add.
	 */
	private void addByte(final byte b) {
		messageContents[currentOutputPosition] = b;
		currentOutputPosition++;
	}

	/**
	 * Adds the given bytes to the response.
	 *
	 * @param bytes
	 *			the bytes to add.
	 */
	void addBytes(final byte[] bytes) {
		if (bytes == null) {
			return;
		}
		for (final byte b : bytes) {
			messageContents[currentOutputPosition] = b;
			currentOutputPosition++;
		}
	}

	/** Adds a USHORT to the response */
	void addUShort(final int value) {
		addByte((byte) (value & 0xff));
		addByte((byte) ((value >> 8) & 0xff));
	}

	/** Adds a ULong to the response */
	void addULong(final int value) {
		addByte((byte) (value & 0xff));
		addByte((byte) ((value >> 8) & 0xff));
		addByte((byte) ((value >> 16) & 0xff));
		addByte((byte) ((value >> 24) & 0xff));
	}

	/**
	 * Returns the response that has been generated after shrinking the
	 * array if required and base64 encodes the response.
	 *
	 * @return The response as above.
	 */
	String getResponse() {
		return new String(Base64.encodeBase64(getBytes()), NTLMEngineUtils.DEFAULT_CHARSET);
	}

	private byte[] getBytes() {
		if (messageContents == null) {
			buildMessage();
		}

		if (messageContents.length > currentOutputPosition) {
			final byte[] tmp = new byte[currentOutputPosition];
			System.arraycopy(messageContents, 0, tmp, 0, currentOutputPosition);
			messageContents = tmp;
		}
		return messageContents;
	}

	protected void buildMessage() {
		throw new RuntimeException("Message builder not implemented for " + getClass().getName());
	}
}
