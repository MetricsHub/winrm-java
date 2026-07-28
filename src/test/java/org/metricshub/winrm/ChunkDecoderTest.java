package org.metricshub.winrm;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link ChunkDecoder}: incrementally decoding a byte sequence — split at any
 * boundary, including inside multibyte characters — must yield exactly the text a whole-buffer
 * {@code new String(bytes, charset)} yields.
 */
class ChunkDecoderTest {

	/** Decode the bytes in two chunks split at the given position, plus the final flush. */
	private static String decodeSplit(final byte[] bytes, final int split, final Charset charset) {
		final ChunkDecoder decoder = new ChunkDecoder(charset);
		return (decoder.decode(Arrays.copyOfRange(bytes, 0, split)) +
			decoder.decode(Arrays.copyOfRange(bytes, split, bytes.length)) +
			decoder.finish());
	}

	@Test
	void anySplitOfMultibyteUtf8MatchesWholeBufferDecoding() {
		// 2-byte (é), 3-byte (€) and 4-byte (🙂) UTF-8 sequences.
		final String text = "aé€🙂z";
		final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		for (int split = 0; split <= bytes.length; split++) {
			assertEquals(text, decodeSplit(bytes, split, StandardCharsets.UTF_8), "split at " + split);
		}
	}

	@Test
	void oneByteAtATimeMatchesWholeBufferDecoding() {
		final String text = "é€🙂";
		final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		final ChunkDecoder decoder = new ChunkDecoder(StandardCharsets.UTF_8);
		final StringBuilder decoded = new StringBuilder();
		for (final byte b : bytes) {
			decoded.append(decoder.decode(new byte[] { b }));
		}
		decoded.append(decoder.finish());
		assertEquals(text, decoded.toString());
	}

	@Test
	void malformedInputIsReplacedLikeStringConstructor() {
		// A stray continuation byte and a truncated 2-byte sequence at the very end.
		final byte[] bytes = { 'a', (byte) 0xA9, 'b', (byte) 0xC3 };
		final String expected = new String(bytes, StandardCharsets.UTF_8);
		for (int split = 0; split <= bytes.length; split++) {
			assertEquals(expected, decodeSplit(bytes, split, StandardCharsets.UTF_8), "split at " + split);
		}
	}

	@Test
	void singleByteCharsetsPassThrough() {
		final Charset cp1252 = Charset.forName("windows-1252");
		final String text = "café au lait";
		final byte[] bytes = text.getBytes(cp1252);
		assertEquals(text, decodeSplit(bytes, bytes.length / 2, cp1252));
	}

	@Test
	void emptyChunksProduceNoOutput() {
		final ChunkDecoder decoder = new ChunkDecoder(StandardCharsets.UTF_8);
		assertEquals("", decoder.decode(new byte[0]));
		assertEquals("", decoder.decode(new byte[0]));
		assertEquals("", decoder.finish());
	}
}
