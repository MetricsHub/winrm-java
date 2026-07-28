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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

final class CliArguments implements AutoCloseable {

	enum Operation {
		HELP,
		VERSION,
		WQL,
		COMMAND,
		SHELL
	}

	static final long DEFAULT_TIMEOUT = 60_000L;

	private final Operation operation;
	private final String hostname;
	private final String username;
	private char[] password;
	private final WinRMHttpProtocolEnum protocol;
	private final int port;
	private final long timeout;
	private final boolean permissiveHttps;
	private final AuthenticationEnum authentication;
	private final String kerberosKdc;
	private final String kerberosRealm;
	private final boolean kerberosRealmInferred;
	private final String input;

	private CliArguments(final Builder builder) {
		operation = builder.operation;
		hostname = builder.hostname;
		username = builder.username;
		password = builder.password;
		protocol = builder.https ? WinRMHttpProtocolEnum.HTTPS : WinRMHttpProtocolEnum.HTTP;
		port = WinRMEndpoint.getEndpointPort(protocol, builder.port);
		timeout = builder.timeout;
		permissiveHttps = builder.permissiveHttps;
		authentication = builder.kerberos ? AuthenticationEnum.KERBEROS : AuthenticationEnum.NTLM;
		kerberosKdc = builder.kerberosKdc;
		kerberosRealm = builder.kerberosRealm;
		kerberosRealmInferred = builder.kerberosRealmInferred;
		input = builder.input;
	}

	static CliArguments parse(final String[] arguments) throws CliUsageException {
		final Builder builder = new Builder();
		try {
			int index = 0;
			while (index < arguments.length) {
				final String argument = arguments[index];
				if (isSubcommand(argument)) {
					parseOperation(builder, argument, Arrays.asList(arguments).subList(index + 1, arguments.length));
					break;
				}
				index = parseOption(builder, arguments, index);
				if (builder.operation == Operation.HELP || builder.operation == Operation.VERSION) {
					break;
				}
			}
			validate(builder);
			return new CliArguments(builder);
		} catch (final CliUsageException | RuntimeException e) {
			builder.clearPassword();
			throw e;
		}
	}

	private static int parseOption(final Builder builder, final String[] arguments, final int index)
		throws CliUsageException {
		final String argument = arguments[index];
		final String option = optionName(argument);
		switch (option) {
		case "--help":
			builder.operation = Operation.HELP;
			return index + 1;
		case "--version":
			builder.operation = Operation.VERSION;
			return index + 1;
		case "--hostname":
		case "-h":
			builder.hostname = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--username":
		case "-u":
			builder.username = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--password":
		case "-p":
			if (builder.passwordFile) {
				throw new CliUsageException("--password and --password-file are mutually exclusive");
			}
			builder.directPassword = true;
			builder.replacePassword(consumePassword(arguments, index, option));
			return nextIndex(argument, index);
		case "--password-file":
		case "-pf":
			if (builder.directPassword) {
				throw new CliUsageException("--password and --password-file are mutually exclusive");
			}
			builder.passwordFile = true;
			builder.replacePassword(readPassword(optionValue(arguments, index, option)));
			return nextIndex(argument, index);
		case "--port":
		case "-P":
			builder.port = parsePort(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--timeout":
		case "-t":
			builder.timeout = parseTimeout(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--ntlm":
			builder.ntlm = true;
			return index + 1;
		case "--kerberos":
			builder.kerberos = true;
			return index + 1;
		case "--kerberos-kdc":
			builder.kerberosKdc = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--kerberos-realm":
			builder.kerberosRealm = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--https":
			builder.https = true;
			return index + 1;
		case "--https-permissive":
			builder.permissiveHttps = true;
			return index + 1;
		default:
			throw new CliUsageException("unknown option " + safeOptionName(argument));
		}
	}

	private static void parseOperation(final Builder builder, final String name, final List<String> values)
		throws CliUsageException {
		if ("shell".equals(name)) {
			builder.operation = Operation.SHELL;
			if (!values.isEmpty()) {
				throw new CliUsageException("shell takes no argument");
			}
			return;
		}
		if ("wql".equals(name)) {
			builder.operation = Operation.WQL;
			builder.input = String.join(" ", values);
		} else {
			builder.operation = Operation.COMMAND;
			builder.input = CommandLineBuilder.join(values);
		}
		if (values.isEmpty()) {
			throw new CliUsageException(name + " requires an argument");
		}
	}

	private static void validate(final Builder builder) throws CliUsageException {
		if (builder.operation == Operation.HELP || builder.operation == Operation.VERSION) {
			return;
		}
		if (builder.operation == null) {
			throw new CliUsageException("missing subcommand (wql, command, or shell)");
		}
		if (builder.operation != Operation.SHELL && isBlank(builder.input)) {
			throw new CliUsageException(
				builder.operation == Operation.WQL ? "wql requires a query" : "command requires a command line"
			);
		}
		if (isBlank(builder.hostname)) {
			throw new CliUsageException("missing required option --hostname");
		}
		if (isBlank(builder.username)) {
			throw new CliUsageException("missing required option --username");
		}
		if (builder.directPassword && builder.passwordFile) {
			throw new CliUsageException("--password and --password-file are mutually exclusive");
		}
		if (builder.ntlm && builder.kerberos) {
			throw new CliUsageException("--ntlm and --kerberos are mutually exclusive");
		}
		if (builder.kerberos && !builder.https) {
			throw new CliUsageException("--kerberos requires --https");
		}
		if (!builder.kerberos && (builder.kerberosKdc != null || builder.kerberosRealm != null)) {
			throw new CliUsageException("--kerberos-kdc and --kerberos-realm require --kerberos");
		}
		if (builder.kerberosRealm != null && builder.kerberosKdc == null) {
			throw new CliUsageException("--kerberos-realm requires --kerberos-kdc");
		}
		if (builder.kerberosKdc != null) {
			validateKerberosConfiguration(builder);
		}
		if (builder.permissiveHttps && !builder.https) {
			throw new CliUsageException("--https-permissive requires --https");
		}
	}

	private static void validateKerberosConfiguration(final Builder builder) throws CliUsageException {
		builder.kerberosKdc = builder.kerberosKdc.trim();
		if (builder.kerberosKdc.isEmpty()) {
			throw new CliUsageException("--kerberos-kdc requires a value");
		}
		if (builder.kerberosRealm != null) {
			builder.kerberosRealm = builder.kerberosRealm.trim();
			if (builder.kerberosRealm.isEmpty()) {
				throw new CliUsageException("--kerberos-realm requires a value");
			}
			return;
		}
		builder.kerberosRealm = inferKerberosRealm(builder.kerberosKdc);
		if (builder.kerberosRealm == null) {
			throw new CliUsageException(
				"cannot infer a realm from --kerberos-kdc; specify --kerberos-realm"
			);
		}
		builder.kerberosRealmInferred = true;
	}

	private static String inferKerberosRealm(final String kdc) {
		String host = kdc;
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		if (host.indexOf(':') >= 0
			|| host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
			return null;
		}
		final int separator = host.indexOf('.');
		if (separator <= 0 || separator == host.length() - 1) {
			return null;
		}
		return host.substring(separator + 1).toUpperCase(Locale.ROOT);
	}

	private static String optionValue(final String[] arguments, final int index, final String option)
		throws CliUsageException {
		final int separator = arguments[index].indexOf('=');
		if (separator >= 0) {
			final String value = arguments[index].substring(separator + 1);
			if (value.isEmpty()) {
				throw new CliUsageException(option + " requires a value");
			}
			return value;
		}
		if (index + 1 >= arguments.length) {
			throw new CliUsageException(option + " requires a value");
		}
		return arguments[index + 1];
	}

	private static char[] consumePassword(final String[] arguments, final int index, final String option)
		throws CliUsageException {
		final String value = optionValue(arguments, index, option);
		final char[] password = value.toCharArray();
		if (arguments[index].indexOf('=') >= 0) {
			arguments[index] = option;
		} else {
			arguments[index + 1] = "";
		}
		return password;
	}

	private static char[] readPassword(final String fileName) throws CliUsageException {
		final byte[] bytes;
		try {
			final Path path = Path.of(fileName);
			bytes = Files.readAllBytes(path);
		} catch (final IOException | InvalidPathException e) {
			throw new CliUsageException("cannot read --password-file", e);
		}

		CharBuffer characters = null;
		char[] decoded = null;
		try {
			characters = StandardCharsets.UTF_8
				.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes));
			decoded = new char[characters.remaining()];
			characters.get(decoded);
			final int length = passwordLengthWithoutLineEnding(decoded);
			return Arrays.copyOf(decoded, length);
		} catch (final CharacterCodingException e) {
			throw new CliUsageException("--password-file is not valid UTF-8", e);
		} finally {
			Arrays.fill(bytes, (byte) 0);
			if (characters != null && characters.hasArray()) {
				Arrays.fill(characters.array(), '\0');
			}
			if (decoded != null) {
				Arrays.fill(decoded, '\0');
			}
		}
	}

	private static int passwordLengthWithoutLineEnding(final char[] password) {
		int length = password.length;
		if (length > 0 && password[length - 1] == '\n') {
			length--;
			if (length > 0 && password[length - 1] == '\r') {
				length--;
			}
		} else if (length > 0 && password[length - 1] == '\r') {
			length--;
		}
		return length;
	}

	private static Integer parsePort(final String value, final String option) throws CliUsageException {
		final long port = parsePositiveNumber(value, option);
		if (port > 65_535L) {
			throw new CliUsageException(option + " must be between 1 and 65535");
		}
		return (int) port;
	}

	private static long parseTimeout(final String value, final String option) throws CliUsageException {
		return parsePositiveNumber(value, option);
	}

	private static long parsePositiveNumber(final String value, final String option) throws CliUsageException {
		try {
			final long number = Long.parseLong(value);
			if (number <= 0) {
				throw new CliUsageException(option + " must be greater than zero");
			}
			return number;
		} catch (final NumberFormatException e) {
			throw new CliUsageException(option + " must be a number", e);
		}
	}

	private static int nextIndex(final String argument, final int index) {
		return argument.indexOf('=') >= 0 ? index + 1 : index + 2;
	}

	private static String optionName(final String argument) {
		final int separator = argument.indexOf('=');
		return separator >= 0 ? argument.substring(0, separator) : argument;
	}

	private static String safeOptionName(final String argument) {
		if (argument.startsWith("-")) {
			return "'" + optionName(argument) + "'";
		}
		return "before subcommand";
	}

	private static boolean isSubcommand(final String value) {
		return "wql".equals(value)
			||
			"command".equals(value)
			||
			"cmd".equals(value)
			||
			"exec".equals(value)
			||
			"run".equals(value)
			||
			"shell".equals(value);
	}

	private static boolean isBlank(final String value) {
		return value == null || value.trim().isEmpty();
	}

	Operation operation() {
		return operation;
	}

	String hostname() {
		return hostname;
	}

	String username() {
		return username;
	}

	char[] password() {
		return password;
	}

	void replacePassword(final char[] replacement) {
		if (password != null) {
			Arrays.fill(password, '\0');
		}
		password = replacement;
	}

	WinRMHttpProtocolEnum protocol() {
		return protocol;
	}

	int port() {
		return port;
	}

	long timeout() {
		return timeout;
	}

	boolean permissiveHttps() {
		return permissiveHttps;
	}

	List<AuthenticationEnum> authentications() {
		final List<AuthenticationEnum> result = new ArrayList<>(1);
		result.add(authentication);
		return result;
	}

	String kerberosKdc() {
		return kerberosKdc;
	}

	String kerberosRealm() {
		return kerberosRealm;
	}

	boolean kerberosRealmInferred() {
		return kerberosRealmInferred;
	}

	String input() {
		return input;
	}

	@Override
	public void close() {
		if (password != null) {
			Arrays.fill(password, '\0');
		}
	}

	private static final class Builder {

		private Operation operation;
		private String hostname;
		private String username;
		private char[] password;
		private boolean directPassword;
		private boolean passwordFile;
		private boolean https;
		private boolean permissiveHttps;
		private boolean ntlm;
		private boolean kerberos;
		private String kerberosKdc;
		private String kerberosRealm;
		private boolean kerberosRealmInferred;
		private Integer port;
		private long timeout = DEFAULT_TIMEOUT;
		private String input;

		private void replacePassword(final char[] replacement) {
			clearPassword();
			password = replacement;
		}

		private void clearPassword() {
			if (password != null) {
				Arrays.fill(password, '\0');
				password = null;
			}
		}
	}
}
