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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.metricshub.winrm.Utils;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.ByteArrayUtils
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class ByteArrayUtils {

	private ByteArrayUtils() {}

	private static final int WIDTH = 32;

	public static String formatHexDump(final byte[] array) {
		if (array == null) {
			return "null";
		}

		// from https://gist.github.com/jen20/906db194bd97c14d91df

		final StringBuilder builder = new StringBuilder();

		for (int rowOffset = 0; rowOffset < array.length; rowOffset += WIDTH) {
			builder.append(String.format("%06d: ", rowOffset));

			for (int index = 0; index < WIDTH; index++) {
				if (rowOffset + index < array.length) {
					builder.append(String.format("%02x", array[rowOffset + index]));
				} else {
					builder.append("  ");
				}

				if (index % 4 == 3) {
					builder.append(" ");
				}
			}

			if (rowOffset < array.length) {
				builder.append(" | ");
				for (int index = 0; index < WIDTH; index++) {
					if (rowOffset + index < array.length) {
						final byte c = array[rowOffset + index];
						builder.append((c >= 20 && c < 127) ? (char) c : '.');

						if (index % 8 == 7) builder.append(" ");
					}
				}
			}

			builder.append(Utils.NEW_LINE);
		}

		return builder.toString();
	}

	public static byte[] getLittleEndianUnsignedInt(final long x) {
		final ByteBuffer byteBuffer = ByteBuffer.allocate(4);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		byteBuffer.putInt((int) (x & 0xFFFFFFFF));
		return byteBuffer.array();
	}

	public static long readLittleEndianUnsignedInt(final byte[] input, final int offset) {
		final ByteBuffer byteBuffer = ByteBuffer.wrap(input);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		return Integer.toUnsignedLong(byteBuffer.getInt(offset));
	}

	public static byte[] concat(final byte[]... sequences) {
		try (final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (byte[] s : sequences) {
				out.write(s);
			}
			return out.toByteArray();
		} catch (final IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
