package org.metricshub.winrm.cli;

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

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class JsonLinesWriter {

	private JsonLinesWriter() {}

	static void write(final Map<String, Object> row, final PrintStream output) {
		final StringBuilder json = new StringBuilder();
		json.append('{');
		boolean first = true;
		for (final Map.Entry<String, Object> entry : row.entrySet()) {
			if (!first) {
				json.append(',');
			}
			writeString(entry.getKey(), json);
			json.append(':');
			final Object value = entry.getValue();
			if (value == null) {
				json.append("null");
			} else {
				writeString(String.valueOf(value), json);
			}
			first = false;
		}
		json.append('}').append(System.lineSeparator());
		final byte[] utf8 = json.toString().getBytes(StandardCharsets.UTF_8);
		output.write(utf8, 0, utf8.length);
	}

	private static void writeString(final String value, final StringBuilder output) {
		output.append('"');
		for (int index = 0; index < value.length(); index++) {
			final char character = value.charAt(index);
			switch (character) {
			case '"':
				output.append("\\\"");
				break;
			case '\\':
				output.append("\\\\");
				break;
			case '\b':
				output.append("\\b");
				break;
			case '\f':
				output.append("\\f");
				break;
			case '\n':
				output.append("\\n");
				break;
			case '\r':
				output.append("\\r");
				break;
			case '\t':
				output.append("\\t");
				break;
			default:
				writeOrdinaryCharacter(character, output);
				break;
			}
		}
		output.append('"');
	}

	private static void writeOrdinaryCharacter(final char character, final StringBuilder output) {
		if (character < 0x20) {
			output.append(String.format("\\u%04x", (int) character));
		} else {
			output.append(character);
		}
	}
}
