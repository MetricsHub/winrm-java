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

/**
 * Cryptography support - MD4. The following class was based loosely on the
 * RFC and on code found at http://www.cs.umd.edu/~harry/jotp/src/md.java.
 * Code correctness was verified by looking at MD4.java from the jcifs
 * library (http://jcifs.samba.org). It was massaged extensively to the
 * final form found here by Karl Wright (kwright@metacarta.com).
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngineImpl
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public class MD4 {

	private int a = 0x67452301;
	private int b = 0xefcdab89;
	private int c = 0x98badcfe;
	private int d = 0x10325476;
	private long count = 0L;
	private final byte[] dataBuffer = new byte[64];

	void update(final byte[] input) {
		// We always deal with 512 bits at a time. Correspondingly, there is
		// a buffer 64 bytes long that we write data into until it gets
		// full.
		int curBufferPos = (int) (count & 63L);
		int inputIndex = 0;
		while (input.length - inputIndex + curBufferPos >= dataBuffer.length) {
			// We have enough data to do the next step. Do a partial copy
			// and a transform, updating inputIndex and curBufferPos
			// accordingly
			final int transferAmt = dataBuffer.length - curBufferPos;
			System.arraycopy(input, inputIndex, dataBuffer, curBufferPos, transferAmt);
			count += transferAmt;
			curBufferPos = 0;
			inputIndex += transferAmt;
			processBuffer();
		}

		// If there's anything left, copy it into the buffer and leave it.
		// We know there's not enough left to process.
		if (inputIndex < input.length) {
			final int transferAmt = input.length - inputIndex;
			System.arraycopy(input, inputIndex, dataBuffer, curBufferPos, transferAmt);
			count += transferAmt;
			curBufferPos += transferAmt;
		}
	}

	byte[] getOutput() {
		// Feed pad/length data into engine. This must round out the input
		// to a multiple of 512 bits.
		final int bufferIndex = (int) (count & 63L);
		final int padLen = (bufferIndex < 56) ? (56 - bufferIndex) : (120 - bufferIndex);
		final byte[] postBytes = new byte[padLen + 8];
		// Leading 0x80, specified amount of zero padding, then length in
		// bits.
		postBytes[0] = (byte) 0x80;
		// Fill out the last 8 bytes with the length
		for (int i = 0; i < 8; i++) {
			postBytes[padLen + i] = (byte) ((count * 8) >>> (8 * i));
		}

		// Update the engine
		update(postBytes);

		// Calculate final result
		final byte[] result = new byte[16];
		writeULong(result, a, 0);
		writeULong(result, b, 4);
		writeULong(result, c, 8);
		writeULong(result, d, 12);
		return result;
	}

	private static void writeULong(final byte[] buffer, final int value, final int offset) {
		buffer[offset] = (byte) (value & 0xff);
		buffer[offset + 1] = (byte) ((value >> 8) & 0xff);
		buffer[offset + 2] = (byte) ((value >> 16) & 0xff);
		buffer[offset + 3] = (byte) ((value >> 24) & 0xff);
	}

	private void processBuffer() {
		// Convert current buffer to 16 ulongs
		final int[] d = new int[16];

		for (int i = 0; i < 16; i++) {
			d[i] =
				(dataBuffer[i * 4] & 0xff) +
				((dataBuffer[i * 4 + 1] & 0xff) << 8) +
				((dataBuffer[i * 4 + 2] & 0xff) << 16) +
				((dataBuffer[i * 4 + 3] & 0xff) << 24);
		}

		// Do a round of processing
		final int aa = a;
		final int bb = b;
		final int cc = c;
		final int dd = this.d;
		round1(d);
		round2(d);
		round3(d);
		a += aa;
		b += bb;
		c += cc;
		this.d += dd;
	}

	private void round1(final int[] d) {
		a = rotintlft((a + f(b, c, this.d) + d[0]), 3);
		this.d = rotintlft((this.d + f(a, b, c) + d[1]), 7);
		c = rotintlft((c + f(this.d, a, b) + d[2]), 11);
		b = rotintlft((b + f(c, this.d, a) + d[3]), 19);

		a = rotintlft((a + f(b, c, this.d) + d[4]), 3);
		this.d = rotintlft((this.d + f(a, b, c) + d[5]), 7);
		c = rotintlft((c + f(this.d, a, b) + d[6]), 11);
		b = rotintlft((b + f(c, this.d, a) + d[7]), 19);

		a = rotintlft((a + f(b, c, this.d) + d[8]), 3);
		this.d = rotintlft((this.d + f(a, b, c) + d[9]), 7);
		c = rotintlft((c + f(this.d, a, b) + d[10]), 11);
		b = rotintlft((b + f(c, this.d, a) + d[11]), 19);

		a = rotintlft((a + f(b, c, this.d) + d[12]), 3);
		this.d = rotintlft((this.d + f(a, b, c) + d[13]), 7);
		c = rotintlft((c + f(this.d, a, b) + d[14]), 11);
		b = rotintlft((b + f(c, this.d, a) + d[15]), 19);
	}

	private void round2(final int[] d) {
		a = rotintlft((a + g(b, c, this.d) + d[0] + 0x5a827999), 3);
		this.d = rotintlft((this.d + g(a, b, c) + d[4] + 0x5a827999), 5);
		c = rotintlft((c + g(this.d, a, b) + d[8] + 0x5a827999), 9);
		b = rotintlft((b + g(c, this.d, a) + d[12] + 0x5a827999), 13);

		a = rotintlft((a + g(b, c, this.d) + d[1] + 0x5a827999), 3);
		this.d = rotintlft((this.d + g(a, b, c) + d[5] + 0x5a827999), 5);
		c = rotintlft((c + g(this.d, a, b) + d[9] + 0x5a827999), 9);
		b = rotintlft((b + g(c, this.d, a) + d[13] + 0x5a827999), 13);

		a = rotintlft((a + g(b, c, this.d) + d[2] + 0x5a827999), 3);
		this.d = rotintlft((this.d + g(a, b, c) + d[6] + 0x5a827999), 5);
		c = rotintlft((c + g(this.d, a, b) + d[10] + 0x5a827999), 9);
		b = rotintlft((b + g(c, this.d, a) + d[14] + 0x5a827999), 13);

		a = rotintlft((a + g(b, c, this.d) + d[3] + 0x5a827999), 3);
		this.d = rotintlft((this.d + g(a, b, c) + d[7] + 0x5a827999), 5);
		c = rotintlft((c + g(this.d, a, b) + d[11] + 0x5a827999), 9);
		b = rotintlft((b + g(c, this.d, a) + d[15] + 0x5a827999), 13);
	}

	private void round3(final int[] d) {
		a = rotintlft((a + h(b, c, this.d) + d[0] + 0x6ed9eba1), 3);
		this.d = rotintlft((this.d + h(a, b, c) + d[8] + 0x6ed9eba1), 9);
		c = rotintlft((c + h(this.d, a, b) + d[4] + 0x6ed9eba1), 11);
		b = rotintlft((b + h(c, this.d, a) + d[12] + 0x6ed9eba1), 15);

		a = rotintlft((a + h(b, c, this.d) + d[2] + 0x6ed9eba1), 3);
		this.d = rotintlft((this.d + h(a, b, c) + d[10] + 0x6ed9eba1), 9);
		c = rotintlft((c + h(this.d, a, b) + d[6] + 0x6ed9eba1), 11);
		b = rotintlft((b + h(c, this.d, a) + d[14] + 0x6ed9eba1), 15);

		a = rotintlft((a + h(b, c, this.d) + d[1] + 0x6ed9eba1), 3);
		this.d = rotintlft((this.d + h(a, b, c) + d[9] + 0x6ed9eba1), 9);
		c = rotintlft((c + h(this.d, a, b) + d[5] + 0x6ed9eba1), 11);
		b = rotintlft((b + h(c, this.d, a) + d[13] + 0x6ed9eba1), 15);

		a = rotintlft((a + h(b, c, this.d) + d[3] + 0x6ed9eba1), 3);
		this.d = rotintlft((this.d + h(a, b, c) + d[11] + 0x6ed9eba1), 9);
		c = rotintlft((c + h(this.d, a, b) + d[7] + 0x6ed9eba1), 11);
		b = rotintlft((b + h(c, this.d, a) + d[15] + 0x6ed9eba1), 15);
	}

	private static int f(final int x, final int y, final int z) {
		return ((x & y) | (~x & z));
	}

	private static int g(final int x, final int y, final int z) {
		return ((x & y) | (x & z) | (y & z));
	}

	private static int h(final int x, final int y, final int z) {
		return (x ^ y ^ z);
	}

	private static int rotintlft(final int val, final int numbits) {
		return ((val << numbits) | (val >>> (32 - numbits)));
	}
}
