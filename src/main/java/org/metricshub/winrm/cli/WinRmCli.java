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
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.metricshub.winrm.AuthScheme;
import org.metricshub.winrm.CommandRequest;
import org.metricshub.winrm.RemoteProcess;
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
 * stream while the command runs; when the local standard input is not an interactive console
 * (piped or redirected), it is forwarded as the remote command's standard input. The {@code shell}
 * subcommand starts {@code cmd.exe} on the remote host and bridges it to the local terminal,
 * line by line, until the remote shell exits. Diagnostics are written only to standard error.
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
		System.exit(
			run(
				arguments,
				System.out,
				System.err,
				WinRmCli::connect,
				WinRmCli::readConsolePassword,
				new LocalInput(!detectRedirectedStdin(), System.in)
			)
		);
	}

	/**
	 * Whether the local standard input looks piped or redirected — the signal engaging automatic
	 * stdin forwarding for the {@code command} subcommand. {@code System.console()} alone is not
	 * it: it is also null when only the <i>output</i> is redirected ({@code ... > result.txt}),
	 * and consuming an interactive terminal in that case would hang the CLI waiting for an EOF
	 * the user never sends. So the input itself is probed: a seekable descriptor is a redirected
	 * file (terminals and pipes are not seekable — this catches empty files and the null device
	 * too), and bytes already waiting can only come from a pipe or a redirection. The undetectable
	 * remainder — a pipe whose producer has not written anything by the time the CLI starts — is
	 * what the explicit {@code --stdin} option is for.
	 */
	private static boolean detectRedirectedStdin() {
		if (System.console() != null) {
			return false;
		}
		return stdinIsSeekable() || stdinHasAvailableInput(System.in);
	}

	/** Bytes already waiting on the given (non-console) input: a pipe or a redirection. */
	static boolean stdinHasAvailableInput(final InputStream stdin) {
		try {
			return stdin.available() > 0;
		} catch (final IOException e) {
			return false;
		}
	}

	/** Whether descriptor 0 is seekable — a redirected file or the null device. */
	private static boolean stdinIsSeekable() {
		// Deliberately never closed: closing a stream created on FileDescriptor.in closes the
		// process's standard input itself.
		final FileInputStream probe = new FileInputStream(FileDescriptor.in);
		try {
			probe.getChannel().position();
			return true;
		} catch (final IOException e) {
			return false;
		}
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
		// Tests reaching this overload behave as if run from an interactive console: no local
		// standard input is forwarded implicitly (the test JVM's System.in is not theirs to consume).
		return run(
			arguments,
			standardOutput,
			standardError,
			remoteFactory,
			passwordReader,
			new LocalInput(true, System.in)
		);
	}

	static int run(
		final String[] arguments,
		final PrintStream standardOutput,
		final PrintStream standardError,
		final RemoteFactory remoteFactory,
		final PasswordReader passwordReader,
		final LocalInput localInput
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
			return execute(parsed, standardOutput, standardError, remoteFactory, localInput);
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
		final RemoteFactory remoteFactory,
		final LocalInput localInput
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
				if (arguments.operation() == CliArguments.Operation.SHELL) {
					return interactiveShell(arguments, standardOutput, standardError, remote, localInput);
				}
				// Forward each output chunk as it arrives, so a long-running command can be followed live.
				// Piped/redirected local standard input travels the other way, as the command's stdin —
				// automatically when detected, unconditionally with --stdin.
				final int exitCode = remote.executeCommand(
					arguments.input(),
					arguments.directory(),
					arguments.environment(),
					arguments.timeout(),
					arguments.forwardStdin() || !localInput.terminal ? localInput.stream : null,
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

	/**
	 * Run the interactive {@code shell} session: local Ctrl+C is rerouted to the remote child (a
	 * WSMan {@code ctrl_c} Signal) for the duration of the session, and the remote shell's exit
	 * code is propagated through the usual contract.
	 */
	private static int interactiveShell(
		final CliArguments arguments,
		final PrintStream standardOutput,
		final PrintStream standardError,
		final RemoteOperations remote,
		final LocalInput localInput
	) throws Exception {
		final AtomicBoolean interruptRequested = new AtomicBoolean();
		final Runnable restoreInterruptHandler = forwardSigint(interruptRequested);
		try {
			final int exitCode = remote.shell(
				arguments.timeout(),
				arguments.directory(),
				arguments.environment(),
				localInput.stream,
				standardOutput,
				standardError,
				interruptRequested
			);
			return remoteExitCode(exitCode, standardError);
		} finally {
			restoreInterruptHandler.run();
		}
	}

	/**
	 * Reroute Ctrl+C (SIGINT) into the given flag instead of killing this JVM, so the interactive
	 * shell can forward it to the remote child process. Uses {@code sun.misc.Signal} reflectively:
	 * the JDK ships it in {@code jdk.unsupported}, but it is not part of the Java SE API, so a
	 * runtime without it simply keeps the default behavior (Ctrl+C ends the CLI — and the remote
	 * shell with it, through the client's close). Returns the action restoring the previous
	 * handler.
	 */
	private static Runnable forwardSigint(final AtomicBoolean interruptRequested) {
		try {
			final Class<?> signalClass = Class.forName("sun.misc.Signal");
			final Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
			final InvocationHandler invocationHandler = (proxy, method, args) -> {
				if ("handle".equals(method.getName())) {
					interruptRequested.set(true);
					return null;
				}
				// Object methods a well-behaved proxy must answer (equals/hashCode/toString).
				return invokeObjectMethod(proxy, method, args);
			};
			final Object handler = Proxy.newProxyInstance(
				handlerClass.getClassLoader(),
				new Class<?>[]
				{ handlerClass },
				invocationHandler
			);
			final Object sigint = signalClass.getConstructor(String.class).newInstance("INT");
			final Method handle = signalClass.getMethod("handle", signalClass, handlerClass);
			final Object previous = handle.invoke(null, sigint, handler);
			return () -> {
				try {
					handle.invoke(null, sigint, previous);
				} catch (final ReflectiveOperationException | RuntimeException ignored) {
					// The session is over either way; the JVM exits right after.
				}
			};
		} catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
			return () -> {};
		}
	}

	/** Default implementations of the {@link Object} methods for a reflective proxy. */
	private static Object invokeObjectMethod(final Object proxy, final Method method, final Object[] args) {
		switch (method.getName()) {
		case "equals":
			return proxy == args[0];
		case "hashCode":
			return System.identityHashCode(proxy);
		default:
			return "winrm-java SIGINT forwarder";
		}
	}

	static RemoteOperations connect(final CliArguments arguments) {
		return connect(arguments, 0);
	}

	/**
	 * Connect, optionally pinning the remote command shell's console code page (0 keeps the
	 * default 65001). The interactive shell needs a single-byte code page — see
	 * {@link FluentRemoteOperations#shell}.
	 */
	static FluentRemoteOperations connect(final CliArguments arguments, final int consoleCodePage) {
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
		if (consoleCodePage > 0) {
			builder.consoleCodePage(consoleCodePage);
		}
		final List<AuthenticationEnum> authentications = arguments.authentications();
		if (authentications != null && !authentications.isEmpty()) {
			builder.authentication(
				authentications
					.stream()
					.map(
						scheme -> scheme == AuthenticationEnum.KERBEROS
							? AuthScheme.KERBEROS
							: scheme == AuthenticationEnum.BASIC ? AuthScheme.BASIC : AuthScheme.NTLM
					)
					.toArray(AuthScheme[]::new)
			);
		}
		return new FluentRemoteOperations(builder.build(), codePage -> connect(arguments, codePage).client);
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
			"  winrm-java [options] shell\n" +
			"\n" +
			"Connection options:\n" +
			"  -h, --hostname <host>       Target hostname or IP address (required)\n" +
			"  -u, --username <user>       User name, optionally DOMAIN\\\\user (required)\n" +
			"  -p, --password <password>   Password (visible to local processes; avoid in automation)\n" +
			"  -pf, --password-file <file> Read a UTF-8 password from a file (preferred for automation)\n" +
			"  -P, --port <port>           Target port (default: HTTP 5985, HTTPS 5986)\n" +
			"  -t, --timeout <ms>          Operation timeout in milliseconds (default: 60000)\n" +
			"  -d, --directory <path>      Working directory of the remote command or shell\n" +
			"      --env <NAME=VALUE>      Environment variable of the remote command or shell (repeatable)\n" +
			"  -i, --stdin                 Forward the local standard input to the remote command\n" +
			"      --https                 Use HTTPS\n" +
			"      --https-permissive      Trust any HTTPS certificate and hostname (insecure)\n" +
			"      --ntlm                  Use NTLM authentication (default)\n" +
			"      --kerberos              Use Kerberos authentication (requires HTTPS)\n" +
			"      --basic                 Use HTTP Basic authentication (use HTTPS to protect the credential)\n" +
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

	/** The local standard input: whether it is an interactive console, and the stream itself. */
	static final class LocalInput {

		private final boolean terminal;
		private final InputStream stream;

		LocalInput(final boolean terminal, final InputStream stream) {
			this.terminal = terminal;
			this.stream = stream;
		}
	}

	interface RemoteOperations extends AutoCloseable {
		/** Run the WQL query, handing each row to the consumer as it arrives. */
		void streamWql(String query, long timeout, Consumer<Map<String, Object>> rowConsumer) throws Exception;

		/**
		 * Run the command, forwarding each decoded output chunk to the matching consumer as it
		 * arrives, and return the remote exit code. A non-null {@code workingDirectory} is the
		 * directory the command starts in; a non-empty {@code environment} is set in the remote
		 * shell. A non-null {@code stdin} is consumed to its end and forwarded as the command's
		 * standard input.
		 */
		int executeCommand(
			String command,
			String workingDirectory,
			Map<String, String> environment,
			long timeout,
			InputStream stdin,
			Consumer<String> stdoutConsumer,
			Consumer<String> stderrConsumer
		) throws Exception;

		/**
		 * Start {@code cmd.exe} on the remote host — in the given working directory when non-null,
		 * with the given environment variables when non-empty — and bridge it to the given local
		 * streams until it exits; return its exit code. See {@link InteractiveShell}.
		 */
		int shell(
			long timeout,
			String workingDirectory,
			Map<String, String> environment,
			InputStream localInput,
			PrintStream out,
			PrintStream err,
			AtomicBoolean interruptRequested
		) throws Exception;

		@Override
		void close();
	}

	/** The real remote operations: the streaming terminals of the fluent {@link WinRMClient}. */
	static final class FluentRemoteOperations implements RemoteOperations {

		/**
		 * What the {@code shell} subcommand runs remotely: {@code cmd.exe} with its command echo
		 * off ({@code /Q}), so a forwarded command line is never repeated in the output.
		 */
		static final String SHELL_COMMAND = "cmd.exe /Q";

		/**
		 * The code page the interactive shell falls back to when the remote machine cannot be
		 * asked for its ANSI one: 1252, the most widespread Windows ANSI code page. ASCII — the
		 * whole command vocabulary — is identical in every ANSI code page, so only the non-ASCII
		 * characters of an unqueryable host are at risk.
		 */
		static final int DEFAULT_SHELL_CODE_PAGE = 1252;

		/** The UTF-8 code page: the client's default for commands, and unusable for a shell. */
		private static final int UTF8_CODE_PAGE = 65001;

		private final WinRMClient client;
		private final java.util.function.IntFunction<WinRMClient> clientFactory;

		FluentRemoteOperations(final WinRMClient client) {
			this(client, codePage -> {
				throw new UnsupportedOperationException("This operations handle cannot open a second connection.");
			});
		}

		FluentRemoteOperations(final WinRMClient client, final java.util.function.IntFunction<WinRMClient> clientFactory) {
			this.client = client;
			this.clientFactory = clientFactory;
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
			final String workingDirectory,
			final Map<String, String> environment,
			final long timeout,
			final InputStream stdin,
			final Consumer<String> stdoutConsumer,
			final Consumer<String> stderrConsumer
		) {
			final CommandRequest request = client
				.command(command)
				.timeout(Duration.ofMillis(timeout))
				.onStdout(stdoutConsumer)
				.onStderr(stderrConsumer);
			// A CLI invocation is one client running one command, so the API's "first command
			// only" pinning of the working directory and environment always applies here.
			if (workingDirectory != null) {
				request.workingDirectory(workingDirectory);
			}
			if (environment != null) {
				environment.forEach(request::environment);
			}
			if (stdin != null) {
				request.stdin(stdin);
			}
			return request.execute().exitCode();
		}

		@Override
		public int shell(
			final long timeout,
			final String workingDirectory,
			final Map<String, String> environment,
			final InputStream localInput,
			final PrintStream out,
			final PrintStream err,
			final AtomicBoolean interruptRequested
		) throws Exception {
			// An interactive shell cannot run under console code page 65001, this client's default:
			// a remote cmd.exe decodes the command lines it reads from its standard input ONE BYTE
			// AT A TIME under that page, so every non-ASCII character becomes U+FFFD (measured on
			// Windows Server 2008 R2 and 2022, in both stdin modes and for every input encoding).
			// Any single-byte code page works, so the session runs under the remote machine's ANSI
			// one and both directions use the matching charset.
			//
			// The code page is fixed when the shell is created, so it must be known BEFORE the
			// first command: the probe runs on this connection (a WQL query creates no shell) and
			// the session then opens its own connection with the page pinned.
			final SessionEncoding encoding = sessionEncoding(remoteAnsiCodePage(timeout));
			try (WinRMClient shellClient = clientFactory.apply(encoding.codePage())) {
				// cmd.exe /Q: no command echo — the local terminal already shows what the user
				// types. Pipe-mode stdin (stdin()) makes the local end-of-input a real EOF, which
				// cmd.exe exits on.
				final CommandRequest shellRequest = shellClient
					.command(SHELL_COMMAND)
					.timeout(Duration.ofMillis(timeout))
					.charset(encoding.charset())
					.stdin();
				// The session client's first (and only) command is the shell itself, so the
				// working directory and environment always take effect.
				if (workingDirectory != null) {
					shellRequest.workingDirectory(workingDirectory);
				}
				if (environment != null) {
					environment.forEach(shellRequest::environment);
				}
				try (RemoteProcess process = shellRequest.start()) {
					return InteractiveShell.run(
						process,
						localInput,
						out,
						err,
						interruptRequested,
						InteractiveShell.DEFAULT_POLL_MILLIS,
						null
					);
				}
			}
		}

		/**
		 * The console code page an interactive session runs under, together with the charset that
		 * encodes and decodes it — resolved as ONE decision, so the shell can never be created
		 * under a page whose bytes the session would then misread.
		 */
		static final class SessionEncoding {

			private final int codePage;
			private final Charset charset;

			SessionEncoding(final int codePage, final Charset charset) {
				this.codePage = codePage;
				this.charset = charset;
			}

			int codePage() {
				return codePage;
			}

			Charset charset() {
				return charset;
			}
		}

		/**
		 * Resolve the session's code page and charset from the code page the remote machine
		 * reports. Two answers are refused, and both fall back to {@link #DEFAULT_SHELL_CODE_PAGE}
		 * for BOTH the page and the charset:
		 * <ul>
		 * <li>65001 — a host configured for UTF-8 reports it as its ANSI page, and 65001 is
		 * precisely the page an interactive {@code cmd.exe} cannot read command lines under;</li>
		 * <li>a page this JVM has no charset for — encoding the session with a charset that does
		 * not match the shell's page would corrupt both directions.</li>
		 * </ul>
		 *
		 * @param reportedCodePage the code page the remote machine reported
		 * @return the code page to pin and the charset to use with it, always consistent
		 */
		static SessionEncoding sessionEncoding(final int reportedCodePage) {
			if (reportedCodePage != UTF8_CODE_PAGE) {
				for (final String name : new String[] {
						"windows-" + reportedCodePage,
						"IBM" + reportedCodePage,
						"x-IBM" + reportedCodePage,
						"MS" + reportedCodePage
				}) {
					try {
						return new SessionEncoding(reportedCodePage, Charset.forName(name));
					} catch (final RuntimeException ignored) {
						// Try the next naming convention.
					}
				}
			}
			return new SessionEncoding(DEFAULT_SHELL_CODE_PAGE, Charset.forName("windows-" + DEFAULT_SHELL_CODE_PAGE));
		}

		/**
		 * The remote machine's ANSI code page. {@code Win32_OperatingSystem.CodeSet} reports exactly
		 * that value (it is the ANSI page — the very reason it is the wrong answer for decoding
		 * command OUTPUT, which follows the console page instead). A host that cannot answer falls
		 * back to {@link #DEFAULT_SHELL_CODE_PAGE}: an unusable answer must degrade non-ASCII
		 * input, never prevent the session from opening.
		 */
		private int remoteAnsiCodePage(final long timeout) {
			try {
				final List<WqlRow> rows;
				try (
					Stream<WqlRow> stream = client
						.wql("SELECT CodeSet FROM Win32_OperatingSystem")
						.timeout(Duration.ofMillis(timeout))
						.stream()) {
					rows = stream.collect(java.util.stream.Collectors.toList());
				}
				if (!rows.isEmpty()) {
					final String codeSet = rows.get(0).string("CodeSet");
					if (codeSet != null && !codeSet.trim().isEmpty()) {
						return Integer.parseInt(codeSet.trim());
					}
				}
			} catch (final RuntimeException ignored) {
				// No WMI access, or an answer that is not a code page number: fall back below.
			}
			return DEFAULT_SHELL_CODE_PAGE;
		}

		@Override
		public void close() {
			client.close();
		}
	}
}
