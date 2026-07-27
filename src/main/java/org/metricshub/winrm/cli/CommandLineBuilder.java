package org.metricshub.winrm.cli;

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

import java.util.List;

final class CommandLineBuilder {

	private CommandLineBuilder() {}

	static String join(final List<String> arguments) {
		final StringBuilder result = new StringBuilder();
		for (final String argument : arguments) {
			if (result.length() > 0) {
				result.append(' ');
			}
			appendArgument(result, argument);
		}
		return result.toString();
	}

	private static void appendArgument(final StringBuilder result, final String argument) {
		if (!needsQuoting(argument)) {
			result.append(argument);
			return;
		}

		result.append('"');
		int backslashes = 0;
		for (int index = 0; index < argument.length(); index++) {
			final char character = argument.charAt(index);
			if (character == '\\') {
				backslashes++;
			} else if (character == '"') {
				appendRepeated(result, '\\', backslashes * 2 + 1);
				result.append('"');
				backslashes = 0;
			} else {
				appendRepeated(result, '\\', backslashes);
				backslashes = 0;
				result.append(character);
			}
		}
		appendRepeated(result, '\\', backslashes * 2);
		result.append('"');
	}

	private static boolean needsQuoting(final String argument) {
		if (argument.isEmpty()) {
			return true;
		}
		for (int index = 0; index < argument.length(); index++) {
			final char character = argument.charAt(index);
			if (Character.isWhitespace(character) || character == '"') {
				return true;
			}
		}
		return false;
	}

	private static void appendRepeated(final StringBuilder result, final char character, final int count) {
		for (int index = 0; index < count; index++) {
			result.append(character);
		}
	}
}
