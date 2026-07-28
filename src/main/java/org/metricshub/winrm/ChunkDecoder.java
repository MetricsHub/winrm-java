package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2026 MetricsHub
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * Incremental, stateful charset decoding for streamed command output. A multibyte character (e.g.
 * UTF-8) can be split across WSMan Stream elements or Receive responses, so each chunk is decoded
 * with a decoder that carries the partial-character bytes over to the next chunk — never a
 * per-chunk {@code new String(bytes)}, which would corrupt the boundary bytes into replacement
 * characters. Malformed and unmappable input is replaced, matching
 * {@link String#String(byte[], Charset)}, so incrementally decoding a byte sequence yields the
 * same text as decoding it in one piece.
 * <p>
 * This is a thin push-style convenience over the JDK's own {@link CharsetDecoder}, which does all
 * the actual decoding. The JDK's ready-made incremental decoders ({@code InputStreamReader} and
 * its underlying {@code StreamDecoder}) only fit <i>pull</i>-based streams — they block the caller
 * until input arrives — whereas output chunks here are <i>pushed</i> by the protocol loop as each
 * Receive response is processed; there is no public JDK type for that direction, only the
 * {@link CharsetDecoder}/{@link ByteBuffer} primitives this class packages.
 */
final class ChunkDecoder {

	private final CharsetDecoder decoder;

	// Undecoded tail bytes of the previous chunk — an incomplete multibyte character — replayed in
	// front of the next chunk.
	private byte[] pending = new byte[0];

	ChunkDecoder(final Charset charset) {
		this.decoder = charset
			.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}

	/**
	 * Decode the next chunk, returning the characters that are complete so far. An incomplete
	 * multibyte character at the end of the chunk is withheld until the next call completes it.
	 */
	String decode(final byte[] chunk) {
		return decode(chunk, false);
	}

	/**
	 * Flush the decoder at the end of the stream: a trailing incomplete character becomes a
	 * replacement character, exactly as a whole-buffer {@code new String(bytes)} would render it.
	 */
	String finish() {
		return decode(new byte[0], true);
	}

	private String decode(final byte[] chunk, final boolean endOfInput) {
		final ByteBuffer in = ByteBuffer.allocate(pending.length + chunk.length);
		in.put(pending);
		in.put(chunk);
		in.flip();

		final StringBuilder text = new StringBuilder();
		final CharBuffer out = CharBuffer.allocate(Math.max(16, in.remaining() * 2));
		CoderResult result;
		do {
			result = decoder.decode(in, out, endOfInput);
			out.flip();
			text.append(out);
			out.clear();
			// With REPLACE in force the only non-underflow result is overflow: loop for more room.
		} while (result.isOverflow());

		if (endOfInput) {
			do {
				result = decoder.flush(out);
				out.flip();
				text.append(out);
				out.clear();
			} while (result.isOverflow());
			pending = new byte[0];
		} else {
			// Whatever the decoder left in the input is an incomplete character: carry it over.
			pending = new byte[in.remaining()];
			in.get(pending);
		}
		return text.toString();
	}
}
