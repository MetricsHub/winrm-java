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
import java.util.Map;

final class JsonLinesWriter {

	private JsonLinesWriter() {}

	static void write(final Map<String, Object> row, final PrintStream output) {
		output.print('{');
		boolean first = true;
		for (final Map.Entry<String, Object> entry : row.entrySet()) {
			if (!first) {
				output.print(',');
			}
			writeString(entry.getKey(), output);
			output.print(':');
			final Object value = entry.getValue();
			if (value == null) {
				output.print("null");
			} else {
				writeString(String.valueOf(value), output);
			}
			first = false;
		}
		output.println('}');
	}

	private static void writeString(final String value, final PrintStream output) {
		output.print('"');
		for (int index = 0; index < value.length(); index++) {
			final char character = value.charAt(index);
			switch (character) {
			case '"':
				output.print("\\\"");
				break;
			case '\\':
				output.print("\\\\");
				break;
			case '\b':
				output.print("\\b");
				break;
			case '\f':
				output.print("\\f");
				break;
			case '\n':
				output.print("\\n");
				break;
			case '\r':
				output.print("\\r");
				break;
			case '\t':
				output.print("\\t");
				break;
			default:
				writeOrdinaryCharacter(character, output);
				break;
			}
		}
		output.print('"');
	}

	private static void writeOrdinaryCharacter(final char character, final PrintStream output) {
		if (character < 0x20) {
			output.printf("\\u%04x", (int) character);
		} else {
			output.print(character);
		}
	}
}
