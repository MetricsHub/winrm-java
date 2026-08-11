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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The password flows through {@link CipherGen} as a char[] and is never turned into a String (an
 * immutable String copy could not be wiped, breaking the credentials contract). These tests pin
 * that the char[]-based preprocessing is byte-identical to the String-based derivation it
 * replaced — String.toUpperCase(Locale.ROOT) semantics (including one-to-many expansions like
 * ß → SS) and String.getBytes(Charset) replacement semantics.
 */
class CipherGenTest {

	@Test
	void upperCaseMatchesStringToUpperCase() {
		final String[] samples = {
				"", // empty
				"plain ASCII s3cret!", // the common case
				"pässwörd-ß", // ß expands to SS
				"straße & ẞ", // sharp s next to capital sharp s
				"ﬁle ﬂow oﬃce ﬆop", // Latin ligatures expand (ﬁ → FI, ...)
				"ﬓﬔﬕﬖﬗ", // Armenian ligatures expand
				"ΐ and ΰ", // Greek: expand to three chars each
				"ᾀᾧᾲῴ", // Greek ypogegrammeni: simple mapping exists AND full mapping expands
				"ŉ ǰ ẖ ẗ ẘ ẙ ẚ", // more one-to-many expansions
				"𐐷𐑏 supplementary", // surrogate pairs (Deseret, 1:1 mapping)
				"broken \ud800 surrogate" // unpaired surrogate passes through unchanged
		};
		for (final String sample : samples) {
			assertArrayEquals(
				sample.toUpperCase(Locale.ROOT).toCharArray(),
				CipherGen.upperCase(sample.toCharArray()),
				sample
			);
		}
	}

	@Test
	void upperCaseMatchesStringToUpperCaseForEveryCodePoint() {
		for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
			if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
				continue;
			}
			final String s = new String(Character.toChars(codePoint));
			final char[] expected = s.toUpperCase(Locale.ROOT).toCharArray();
			final char[] actual = CipherGen.upperCase(s.toCharArray());
			// Guard to avoid building 1.1M failure messages; assert only on divergence.
			if (!Arrays.equals(expected, actual)) {
				assertArrayEquals(expected, actual, "U+" + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT));
			}
		}
	}

	@Test
	void encodeMatchesStringGetBytes() {
		final String[] samples = { "", "ASCII only", "pässw0rd-€-好-😀", "broken \ud800 surrogate", "ß" };
		for (final String sample : samples) {
			assertArrayEquals(
				sample.getBytes(StandardCharsets.UTF_16LE),
				CipherGen.encode(sample.toCharArray(), StandardCharsets.UTF_16LE),
				sample
			);
			assertArrayEquals(
				sample.getBytes(StandardCharsets.US_ASCII),
				CipherGen.encode(sample.toCharArray(), StandardCharsets.US_ASCII),
				sample
			);
		}
	}

	@Test
	void lmResponseAppliesFullUppercaseExpansions() throws Exception {
		// The legacy LM hash upper-cases the password before hashing, and ß expands to SS there:
		// the LM response for "ß" must therefore equal the one for "ss" (both hash the OEM bytes
		// "SS"). A per-char uppercase (Character.toUpperCase) would leave ß in place, turn it into
		// '?' in the OEM charset, and derive a different response.
		final byte[] challenge = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef };
		final byte[] fromExpansion = new CipherGen(new Random(0), 0L, "DOM", "user", "ß".toCharArray(), challenge, null)
			.getLMResponse();
		final byte[] fromPlain = new CipherGen(new Random(0), 0L, "DOM", "user", "ss".toCharArray(), challenge, null)
			.getLMResponse();
		assertArrayEquals(fromPlain, fromExpansion);
	}
}
