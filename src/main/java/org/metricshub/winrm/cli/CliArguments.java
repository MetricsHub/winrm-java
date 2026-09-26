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
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

final class CliArguments implements AutoCloseable {

	enum Operation {
		HELP,
		VERSION,
		WQL,
		COMMAND,
		SHELL,
		LS,
		STAT,
		CAT,
		GET
	}

	static final long DEFAULT_TIMEOUT = 60_000L;

	/**
	 * Smallest usable {@code --timeout} for the interactive shell: it caps each bounded poll of
	 * the session pump, and a poll below the WSMan floor (a 500 ms server-side hold plus transit
	 * slack) never reaches the wire — the shell would spin locally without ever fetching output.
	 */
	static final long MIN_SHELL_TIMEOUT = 1_000L;

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
	private final boolean allowDelegate;
	private final boolean forwardStdin;
	private final String directory;
	private final Map<String, String> environment;
	private final String input;
	private final Path localFile;
	private final String glob;
	private final boolean recursive;
	private final int depth;
	private final boolean filesOnly;
	private final boolean directoriesOnly;
	private final Instant modifiedAfter;
	private final long minSize;
	private final boolean json;
	private final long offset;
	private final long length;
	private final Charset charset;

	private CliArguments(final Builder builder) {
		operation = builder.operation;
		hostname = builder.hostname;
		username = builder.username;
		password = builder.password;
		protocol = builder.https ? WinRMHttpProtocolEnum.HTTPS : WinRMHttpProtocolEnum.HTTP;
		port = WinRMEndpoint.getEndpointPort(protocol, builder.port);
		timeout = builder.timeout;
		permissiveHttps = builder.permissiveHttps;
		authentication = builder.basic
			? AuthenticationEnum.BASIC
			: builder.kerberos ? AuthenticationEnum.KERBEROS : AuthenticationEnum.NTLM;
		kerberosKdc = builder.kerberosKdc;
		kerberosRealm = builder.kerberosRealm;
		kerberosRealmInferred = builder.kerberosRealmInferred;
		allowDelegate = builder.allowDelegate;
		forwardStdin = builder.forwardStdin;
		directory = builder.directory;
		environment = builder.environment;
		input = builder.input;
		localFile = builder.localFile;
		glob = builder.glob;
		recursive = builder.recursive;
		depth = builder.depth;
		filesOnly = builder.filesOnly;
		directoriesOnly = builder.directoriesOnly;
		modifiedAfter = builder.modifiedAfter;
		minSize = builder.minSize;
		json = builder.json;
		offset = builder.offset;
		length = builder.length;
		charset = builder.charset;
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
		case "--directory":
		case "-d":
			builder.directory = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--env":
			parseEnvironmentVariable(builder, optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--ntlm":
			builder.ntlm = true;
			return index + 1;
		case "--kerberos":
			builder.kerberos = true;
			return index + 1;
		case "--basic":
			builder.basic = true;
			return index + 1;
		case "--kerberos-kdc":
			builder.kerberosKdc = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--kerberos-realm":
			builder.kerberosRealm = optionValue(arguments, index, option);
			return nextIndex(argument, index);
		case "--allow-delegate":
			builder.allowDelegate = true;
			return index + 1;
		case "--https":
			builder.https = true;
			return index + 1;
		case "--https-permissive":
			builder.permissiveHttps = true;
			return index + 1;
		case "--stdin":
		case "-i":
			builder.forwardStdin = true;
			return index + 1;
		default:
			throw new CliUsageException("unknown option " + safeOptionName(argument));
		}
	}

	/**
	 * Record one {@code --env NAME=VALUE} occurrence, winrs-style: the value is split on its FIRST
	 * {@code =} (so the variable's value may itself contain {@code =}), the name must be non-blank,
	 * insertion order is preserved, and repeating a name replaces its value.
	 */
	private static void parseEnvironmentVariable(final Builder builder, final String value, final String option)
		throws CliUsageException {
		final int separator = value.indexOf('=');
		if (separator < 0 || value.substring(0, separator).trim().isEmpty()) {
			throw new CliUsageException(option + " requires NAME=VALUE");
		}
		builder.environment.put(value.substring(0, separator), value.substring(separator + 1));
	}

	private static void parseOperation(final Builder builder, final String name, final List<String> values)
		throws CliUsageException {
		if (isFileSubcommand(name)) {
			builder.operation = Operation.valueOf(name.toUpperCase(Locale.ROOT));
			parseFileArguments(builder, name, values.toArray(new String[0]));
			return;
		}
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

	/**
	 * Parse what follows a file subcommand: its remote path (and, for {@code get}, an optional
	 * local path), passed through untouched, and its own options, in any order. Anything starting
	 * with {@code -} is an option.
	 */
	private static void parseFileArguments(final Builder builder, final String name, final String[] arguments)
		throws CliUsageException {
		final List<String> paths = new ArrayList<>(2);
		int index = 0;
		while (index < arguments.length) {
			if (arguments[index].startsWith("-")) {
				index = parseFileOption(builder, arguments, index);
			} else {
				paths.add(arguments[index]);
				index++;
			}
		}
		final boolean get = builder.operation == Operation.GET;
		if (paths.isEmpty() || paths.size() > (get ? 2 : 1) || isBlank(paths.get(0))) {
			throw new CliUsageException(
				get ? "get requires a remote path and an optional local path" : name + " requires one remote path"
			);
		}
		builder.input = paths.get(0);
		if (paths.size() == 2) {
			try {
				builder.localFile = Path.of(paths.get(1));
			} catch (final InvalidPathException e) {
				throw new CliUsageException("get: invalid local path", e);
			}
		}
	}

	private static int parseFileOption(final Builder builder, final String[] arguments, final int index)
		throws CliUsageException {
		final String argument = arguments[index];
		final String option = optionName(argument);
		switch (option) {
		case "--glob":
			requireSubcommand(builder, option, Operation.LS);
			builder.glob = optionValue(arguments, index, option);
			if (isBlank(builder.glob)) {
				throw new CliUsageException(option + " requires a value");
			}
			return nextIndex(argument, index);
		case "--recursive":
			requireSubcommand(builder, option, Operation.LS);
			builder.recursive = true;
			return index + 1;
		case "--depth":
			requireSubcommand(builder, option, Operation.LS);
			builder.depth = (int) Math
				.min(Integer.MAX_VALUE, parsePositiveNumber(optionValue(arguments, index, option), option));
			return nextIndex(argument, index);
		case "--files-only":
			requireSubcommand(builder, option, Operation.LS);
			builder.filesOnly = true;
			return index + 1;
		case "--directories-only":
			requireSubcommand(builder, option, Operation.LS);
			builder.directoriesOnly = true;
			return index + 1;
		case "--modified-after":
			requireSubcommand(builder, option, Operation.LS);
			builder.modifiedAfter = parseInstant(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--min-size":
			requireSubcommand(builder, option, Operation.LS);
			builder.minSize = parsePositiveNumber(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--json":
			requireSubcommand(builder, option, Operation.LS, Operation.STAT);
			builder.json = true;
			return index + 1;
		case "--offset":
			requireSubcommand(builder, option, Operation.CAT);
			builder.offset = parseNumber(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--length":
			requireSubcommand(builder, option, Operation.CAT);
			builder.length = parsePositiveNumber(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		case "--charset":
			requireSubcommand(builder, option, Operation.CAT);
			builder.charset = parseCharset(optionValue(arguments, index, option), option);
			return nextIndex(argument, index);
		default:
			throw new CliUsageException("unknown option " + safeOptionName(argument));
		}
	}

	private static void requireSubcommand(final Builder builder, final String option, final Operation... operations)
		throws CliUsageException {
		if (!Arrays.asList(operations).contains(builder.operation)) {
			throw new CliUsageException(
				option + " requires the " +
					Arrays.stream(operations).map(o -> o.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(" or ")) +
					" subcommand"
			);
		}
	}

	/**
	 * An ISO-8601 date-time with an offset ({@code 2026-01-31T12:00:00Z}), or a date
	 * ({@code 2026-01-31}), which stands for midnight UTC. A date-time without an offset is refused:
	 * its time zone would be a guess.
	 */
	private static Instant parseInstant(final String value, final String option) throws CliUsageException {
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (final DateTimeParseException e) {
			try {
				return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
			} catch (final DateTimeParseException notADate) {
				throw new CliUsageException(
					option + " must be an ISO-8601 date or date-time with an offset, e.g. 2026-01-31 or 2026-01-31T12:00:00Z",
					e
				);
			}
		}
	}

	private static Charset parseCharset(final String value, final String option) throws CliUsageException {
		try {
			return Charset.forName(value);
		} catch (final IllegalArgumentException e) {
			throw new CliUsageException(option + " must name a charset known to Java, e.g. UTF-8 or windows-1252", e);
		}
	}

	private static void validate(final Builder builder) throws CliUsageException {
		if (builder.operation == Operation.HELP || builder.operation == Operation.VERSION) {
			return;
		}
		if (builder.operation == null) {
			throw new CliUsageException("missing subcommand (wql, command, shell, ls, stat, cat, or get)");
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
		if (builder.ntlm && builder.basic) {
			throw new CliUsageException("--ntlm and --basic are mutually exclusive");
		}
		if (builder.kerberos && builder.basic) {
			throw new CliUsageException("--kerberos and --basic are mutually exclusive");
		}
		if (builder.kerberos && !builder.https) {
			throw new CliUsageException("--kerberos requires --https");
		}
		if (!builder.kerberos && (builder.kerberosKdc != null || builder.kerberosRealm != null)) {
			throw new CliUsageException("--kerberos-kdc and --kerberos-realm require --kerberos");
		}
		if (builder.allowDelegate && !builder.kerberos) {
			throw new CliUsageException("--allow-delegate requires --kerberos");
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
		if (builder.forwardStdin && builder.operation != Operation.COMMAND) {
			throw new CliUsageException("--stdin requires the command subcommand");
		}
		if (builder.directory != null && builder.directory.trim().isEmpty()) {
			throw new CliUsageException("--directory requires a value");
		}
		final boolean runsInShell = builder.operation == Operation.COMMAND || builder.operation == Operation.SHELL;
		if (builder.directory != null && !runsInShell) {
			throw new CliUsageException("--directory requires the command or shell subcommand");
		}
		if (!builder.environment.isEmpty() && !runsInShell) {
			throw new CliUsageException("--env requires the command or shell subcommand");
		}
		if (builder.filesOnly && builder.directoriesOnly) {
			throw new CliUsageException("--files-only and --directories-only are mutually exclusive");
		}
		if (builder.operation == Operation.SHELL && builder.timeout < MIN_SHELL_TIMEOUT) {
			throw new CliUsageException("shell requires --timeout of at least " + MIN_SHELL_TIMEOUT + " milliseconds");
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
		final long number = parseNumber(value, option);
		if (number <= 0) {
			throw new CliUsageException(option + " must be greater than zero");
		}
		return number;
	}

	private static long parseNumber(final String value, final String option) throws CliUsageException {
		try {
			return Long.parseLong(value);
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
			"shell".equals(value)
			||
			isFileSubcommand(value);
	}

	private static boolean isFileSubcommand(final String value) {
		return "ls".equals(value) || "stat".equals(value) || "cat".equals(value) || "get".equals(value);
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

	boolean allowDelegate() {
		return allowDelegate;
	}

	boolean forwardStdin() {
		return forwardStdin;
	}

	String directory() {
		return directory;
	}

	Map<String, String> environment() {
		return environment;
	}

	/** The query, the command line, or the remote path of a file subcommand. */
	String input() {
		return input;
	}

	/** The local destination of {@code get}, or {@code null} for the current directory. */
	Path localFile() {
		return localFile;
	}

	String glob() {
		return glob;
	}

	boolean recursive() {
		return recursive;
	}

	/** The deepest level {@code ls} lists, or 0 when not set. */
	int depth() {
		return depth;
	}

	boolean filesOnly() {
		return filesOnly;
	}

	boolean directoriesOnly() {
		return directoriesOnly;
	}

	Instant modifiedAfter() {
		return modifiedAfter;
	}

	long minSize() {
		return minSize;
	}

	boolean json() {
		return json;
	}

	long offset() {
		return offset;
	}

	/** How many bytes {@code cat} reads at most, or -1 to read to the end. */
	long length() {
		return length;
	}

	/** The charset {@code cat} decodes the file with, or {@code null} to copy the bytes. */
	Charset charset() {
		return charset;
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
		private boolean basic;
		private String kerberosKdc;
		private String kerberosRealm;
		private boolean kerberosRealmInferred;
		private boolean allowDelegate;
		private boolean forwardStdin;
		private String directory;
		private final Map<String, String> environment = new LinkedHashMap<>();
		private Integer port;
		private long timeout = DEFAULT_TIMEOUT;
		private String input;
		private Path localFile;
		private String glob;
		private boolean recursive;
		private int depth;
		private boolean filesOnly;
		private boolean directoriesOnly;
		private Instant modifiedAfter;
		private long minSize;
		private boolean json;
		private long offset;
		private long length = -1;
		private Charset charset;

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
