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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

/**
 * Incremental, stateful charset encoding for streamed command input — the mirror image of
 * {@link ChunkDecoder}. The text fed to a command's standard input arrives in flush-sized pieces,
 * and a per-piece {@code String.getBytes(Charset)} would not encode it the way one whole-string
 * encode would: a stateful charset (e.g. UTF-16) writes its byte-order mark on every call instead
 * of once, and a surrogate pair split across two pieces becomes two replacement characters. A
 * single {@link CharsetEncoder} carried across calls keeps that state: the mark is emitted once,
 * and a trailing lone high surrogate is withheld until the character is completed by the next
 * piece. Malformed and unmappable input is replaced, matching {@link String#getBytes(Charset)}.
 */
final class ChunkEncoder {

	private final CharsetEncoder encoder;

	// Unencoded tail of the previous piece — a lone high surrogate — replayed in front of the
	// next piece.
	private char[] pending = new char[0];

	ChunkEncoder(final Charset charset) {
		this.encoder = charset
			.newEncoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}

	/**
	 * Encode the next piece of input, returning the bytes that are complete so far. A trailing
	 * lone high surrogate is withheld until the next call completes the pair — unless
	 * {@code endOfInput} is set, which flushes the encoder: the dangling half becomes a
	 * replacement, exactly as a whole-string encode would render it.
	 */
	byte[] encode(final String piece, final boolean endOfInput) {
		final CharBuffer in = CharBuffer.allocate(pending.length + piece.length());
		in.put(pending);
		in.put(piece);
		in.flip();

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final ByteBuffer out = ByteBuffer.allocate(Math.max(16, in.remaining() * 4));
		CoderResult result;
		do {
			result = encoder.encode(in, out, endOfInput);
			bytes.write(out.array(), 0, out.position());
			out.clear();
			// With REPLACE in force the only non-underflow result is overflow: loop for more room.
		} while (result.isOverflow());

		if (endOfInput) {
			do {
				result = encoder.flush(out);
				bytes.write(out.array(), 0, out.position());
				out.clear();
			} while (result.isOverflow());
			pending = new char[0];
		} else {
			// Whatever the encoder left in the input is an incomplete character: carry it over.
			pending = new char[in.remaining()];
			in.get(pending);
		}
		return bytes.toByteArray();
	}
}
