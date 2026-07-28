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

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.metricshub.winrm.AuthScheme;
import org.metricshub.winrm.WinRMClient;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WqlRow;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * Command-line interface for WQL queries and remote command execution through WinRM, built on the
 * streaming terminals of the fluent {@link WinRMClient} API.
 * <p>
 * WQL results are emitted as UTF-8 JSON Lines on standard output, <b>row by row as the
 * WS-Enumeration pages arrive</b> — a large query starts producing output immediately and memory
 * stays bounded, but a mid-stream failure can leave partial output on standard output (with a
 * nonzero exit code). Remote command output is forwarded <b>live</b> to the matching local output
 * stream while the command runs. Diagnostics are written only to standard error.
 * <p>
 * NTLM is the default authentication scheme. Kerberos requires HTTPS. HTTPS validates certificates
 * and hostnames unless the explicitly insecure {@code --https-permissive} option is used.
 * Password-file input is UTF-8 and has exactly one final LF, CRLF, or CR removed. Direct password
 * arguments can be visible to other local processes and should be avoided in automation. When
 * neither password option is supplied, the password is read securely from the interactive console.
 * <p>
 * Usage errors exit with 64, connection/TLS errors with 69, WinRM protocol errors with 70,
 * authentication errors with 77, and timeouts with 124. Representable remote command exit codes
 * (0 through 255) are propagated directly.
 */
public final class WinRmCli {

	static final int EXIT_USAGE = 64;
	static final int EXIT_PROTOCOL = 70;
	static final int EXIT_CONNECTION = 69;
	static final int EXIT_AUTHENTICATION = 77;
	static final int EXIT_TIMEOUT = 124;

	private static final String KERBEROS_KDC_PROPERTY = "java.security.krb5.kdc";
	private static final String KERBEROS_REALM_PROPERTY = "java.security.krb5.realm";

	private WinRmCli() {}

	/**
	 * Run the WinRM command-line client.
	 *
	 * @param arguments command-line arguments
	 */
	public static void main(final String[] arguments) {
		System.exit(run(arguments, System.out, System.err, WinRmCli::connect));
	}

	static int run(
		final String[] arguments,
		final PrintStream standardOutput,
		final PrintStream standardError,
		final RemoteFactory remoteFactory
	) {
		return run(arguments, standardOutput, standardError, remoteFactory, WinRmCli::readConsolePassword);
	}

	static int run(
		final String[] arguments,
		final PrintStream standardOutput,
		final PrintStream standardError,
		final RemoteFactory remoteFactory,
		final PasswordReader passwordReader
	) {
		try (CliArguments parsed = CliArguments.parse(arguments)) {
			if (parsed.operation() == CliArguments.Operation.HELP) {
				standardOutput.print(help());
				return 0;
			}
			if (parsed.operation() == CliArguments.Operation.VERSION) {
				standardOutput.println("winrm-java " + version());
				return 0;
			}
			ensurePassword(parsed, passwordReader);
			return execute(parsed, standardOutput, standardError, remoteFactory);
		} catch (final CliUsageException e) {
			standardError.println("winrm-java: " + e.getMessage());
			standardError.println("Try 'winrm-java --help' for usage.");
			return EXIT_USAGE;
		}
	}

	private static void ensurePassword(final CliArguments arguments, final PasswordReader passwordReader)
		throws CliUsageException {
		if (arguments.password() != null) {
			return;
		}
		final char[] password = passwordReader.readPassword();
		if (password == null) {
			throw new CliUsageException("no password was entered");
		}
		arguments.replacePassword(password);
	}

	private static char[] readConsolePassword() throws CliUsageException {
		final Console console = System.console();
		if (console == null) {
			throw new CliUsageException(
				"no interactive console is available; use --password-file for non-interactive runs"
			);
		}
		return console.readPassword("Password: ");
	}

	private static int execute(
		final CliArguments arguments,
		final PrintStream standardOutput,
		final PrintStream standardError,
		final RemoteFactory remoteFactory
	) {
		final String previousKerberosKdc = System.getProperty(KERBEROS_KDC_PROPERTY);
		final String previousKerberosRealm = System.getProperty(KERBEROS_REALM_PROPERTY);
		try {
			setKerberosConfiguration(arguments, standardError);
			try (RemoteOperations remote = remoteFactory.connect(arguments)) {
				if (arguments.operation() == CliArguments.Operation.WQL) {
					// Flush after every row so a downstream pipe sees each row as soon as the server
					// hands it out, not when the enumeration ends.
					remote.streamWql(
						arguments.input(),
						arguments.timeout(),
						row -> {
							JsonLinesWriter.write(row, standardOutput);
							standardOutput.flush();
						}
					);
					return 0;
				}
				// Forward each output chunk as it arrives, so a long-running command can be followed live.
				final int exitCode = remote.executeCommand(
					arguments.input(),
					arguments.timeout(),
					chunk -> {
						standardOutput.print(chunk);
						standardOutput.flush();
					},
					chunk -> {
						standardError.print(chunk);
						standardError.flush();
					}
				);
				return remoteExitCode(exitCode, standardError);
			}
		} catch (final TimeoutException | WinRMTimeoutException e) {
			diagnostic(standardError, "operation timed out");
			return EXIT_TIMEOUT;
		} catch (final Exception e) {
			final int exitCode = classify(e);
			diagnostic(standardError, safeMessage(e));
			return exitCode;
		} finally {
			restoreProperty(KERBEROS_KDC_PROPERTY, previousKerberosKdc);
			restoreProperty(KERBEROS_REALM_PROPERTY, previousKerberosRealm);
		}
	}

	static RemoteOperations connect(final CliArguments arguments) {
		final WinRMClient.Builder builder = WinRMClient
			.builder(arguments.hostname())
			.port(arguments.port())
			.credentials(arguments.username(), arguments.password())
			.timeout(Duration.ofMillis(arguments.timeout()));
		if (arguments.protocol() == WinRMHttpProtocolEnum.HTTPS) {
			builder.https();
		}
		if (arguments.permissiveHttps()) {
			// Per-client setting: unlike the legacy org.metricshub.winrm.tls.insecure system
			// property, it does not leak to (or race with) anything else in the JVM.
			builder.trustAllCertificates();
		}
		final List<AuthenticationEnum> authentications = arguments.authentications();
		if (authentications != null && !authentications.isEmpty()) {
			builder.authentication(
				authentications
					.stream()
					.map(scheme -> scheme == AuthenticationEnum.KERBEROS ? AuthScheme.KERBEROS : AuthScheme.NTLM)
					.toArray(AuthScheme[]::new)
			);
		}
		return new FluentRemoteOperations(builder.build());
	}

	private static int remoteExitCode(final int exitCode, final PrintStream standardError) {
		if (exitCode >= 0 && exitCode <= 255) {
			return exitCode;
		}
		diagnostic(standardError, "remote exit code " + exitCode + " cannot be represented as a process exit code");
		return EXIT_PROTOCOL;
	}

	private static int classify(final Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			final String className = current.getClass().getName();
			final String message = current.getMessage();
			if (className.startsWith("javax.security.auth.login.")
				||
				className.startsWith("org.ietf.jgss.")
				||
				(message != null && message.toLowerCase(Locale.ROOT).contains("authentication error"))) {
				return EXIT_AUTHENTICATION;
			}
			if (current instanceof ConnectException
				||
				current instanceof NoRouteToHostException
				||
				current instanceof UnknownHostException
				||
				current instanceof SocketException
				||
				current instanceof SSLException
				||
				current instanceof IOException) {
				return EXIT_CONNECTION;
			}
		}
		return EXIT_PROTOCOL;
	}

	private static String safeMessage(final Throwable throwable) {
		final String message = throwable.getMessage();
		if (message == null || message.trim().isEmpty()) {
			return throwable.getClass().getSimpleName();
		}
		return message.replace('\r', ' ').replace('\n', ' ');
	}

	private static void diagnostic(final PrintStream standardError, final String message) {
		standardError.println("winrm-java: " + message);
	}

	private static void setKerberosConfiguration(
		final CliArguments arguments,
		final PrintStream standardError
	) {
		if (arguments.kerberosKdc() == null) {
			return;
		}
		System.setProperty(KERBEROS_KDC_PROPERTY, arguments.kerberosKdc());
		System.setProperty(KERBEROS_REALM_PROPERTY, arguments.kerberosRealm());
		if (arguments.kerberosRealmInferred()) {
			diagnostic(
				standardError,
				"using Kerberos realm " +
					arguments.kerberosRealm() +
					" inferred from KDC " +
					arguments.kerberosKdc()
			);
		}
	}

	private static void restoreProperty(final String name, final String value) {
		if (value == null) {
			System.clearProperty(name);
		} else {
			System.setProperty(name, value);
		}
	}

	private static String version() {
		final Package cliPackage = WinRmCli.class.getPackage();
		final String implementationVersion = cliPackage == null ? null : cliPackage.getImplementationVersion();
		return implementationVersion == null ? "development" : implementationVersion;
	}

	private static String help() {
		return "Usage:\n" +
			"  winrm-java [options] wql <query>\n" +
			"  winrm-java [options] command|cmd|exec|run <command line...>\n" +
			"\n" +
			"Connection options:\n" +
			"  -h, --hostname <host>       Target hostname or IP address (required)\n" +
			"  -u, --username <user>       User name, optionally DOMAIN\\\\user (required)\n" +
			"  -p, --password <password>   Password (visible to local processes; avoid in automation)\n" +
			"  -pf, --password-file <file> Read a UTF-8 password from a file (preferred for automation)\n" +
			"  -P, --port <port>           Target port (default: HTTP 5985, HTTPS 5986)\n" +
			"  -t, --timeout <ms>          Operation timeout in milliseconds (default: 60000)\n" +
			"      --https                 Use HTTPS\n" +
			"      --https-permissive      Trust any HTTPS certificate and hostname (insecure)\n" +
			"      --ntlm                  Use NTLM authentication (default)\n" +
			"      --kerberos              Use Kerberos authentication (requires HTTPS)\n" +
			"      --kerberos-kdc <host>   Set the Kerberos KDC; infer realm from its DNS suffix\n" +
			"      --kerberos-realm <realm> Override the realm inferred from --kerberos-kdc\n" +
			"      --help                  Show this help\n" +
			"      --version               Show the project version\n" +
			"\n" +
			"If neither password option is given, the password is requested from the interactive console.\n" +
			"\n" +
			"Full manual - streaming behavior, password files, Kerberos, exit codes:\n" +
			"  https://metricshub.org/winrm-java/cli.html\n";
	}

	@FunctionalInterface
	interface RemoteFactory {
		RemoteOperations connect(CliArguments arguments) throws Exception;
	}

	@FunctionalInterface
	interface PasswordReader {
		char[] readPassword() throws CliUsageException;
	}

	interface RemoteOperations extends AutoCloseable {
		/** Run the WQL query, handing each row to the consumer as it arrives. */
		void streamWql(String query, long timeout, Consumer<Map<String, Object>> rowConsumer) throws Exception;

		/**
		 * Run the command, forwarding each decoded output chunk to the matching consumer as it
		 * arrives, and return the remote exit code.
		 */
		int executeCommand(String command, long timeout, Consumer<String> stdoutConsumer, Consumer<String> stderrConsumer)
			throws Exception;

		@Override
		void close();
	}

	/** The real remote operations: the streaming terminals of the fluent {@link WinRMClient}. */
	static final class FluentRemoteOperations implements RemoteOperations {

		private final WinRMClient client;

		FluentRemoteOperations(final WinRMClient client) {
			this.client = client;
		}

		@Override
		public void streamWql(final String query, final long timeout, final Consumer<Map<String, Object>> rowConsumer) {
			try (Stream<WqlRow> rows = client.wql(query).timeout(Duration.ofMillis(timeout)).stream()) {
				rows.forEach(row -> rowConsumer.accept(row.asMap()));
			}
		}

		@Override
		public int executeCommand(
			final String command,
			final long timeout,
			final Consumer<String> stdoutConsumer,
			final Consumer<String> stderrConsumer
		) {
			return client
				.command(command)
				.timeout(Duration.ofMillis(timeout))
				.onStdout(stdoutConsumer)
				.onStderr(stderrConsumer)
				.execute()
				.exitCode();
		}

		@Override
		public void close() {
			client.close();
		}
	}
}
