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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueCommandExchange;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueEnumeration;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellCreation;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellDeletion;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.light.FakeWsmanServer;

class WinRmCliTest {

	private static final String[] REQUIRED = { "-h", "host", "-u", "user", "-p", "secret" };
	private static final String INSECURE_TLS_PROPERTY = "org.metricshub.winrm.tls.insecure";
	private static final String KERBEROS_KDC_PROPERTY = "java.security.krb5.kdc";
	private static final String KERBEROS_REALM_PROPERTY = "java.security.krb5.realm";

	@Test
	void helpAndVersionDoNotConnect() throws Exception {
		final Invocation help = invoke(new String[] { "--help" }, arguments -> failingRemote());
		assertEquals(0, help.exitCode);
		assertTrue(help.stdout.contains("command|cmd|exec|run"));
		assertTrue(help.stdout.contains("-P, --port"));
		assertTrue(help.stdout.contains("--kerberos-kdc"));
		assertTrue(help.stdout.contains("--kerberos-realm"));
		// The details (streaming behavior, password files, exit codes) live in the online manual.
		assertTrue(help.stdout.contains("https://metricshub.org/winrm-java/cli.html"));
		assertEquals("", help.stderr);

		final Invocation version = invoke(new String[] { "--version" }, arguments -> failingRemote());
		assertEquals(0, version.exitCode);
		assertTrue(version.stdout.startsWith("winrm-java "));
	}

	@Test
	void writesWqlAsJsonLines() throws Exception {
		final Map<String, Object> first = new LinkedHashMap<>();
		first.put("Name", "Spooler");
		first.put("State", "Running");
		final Map<String, Object> second = new LinkedHashMap<>();
		second.put("Name", "WinRM");
		second.put("State", "Running");
		final FakeRemote remote = new FakeRemote();
		remote.rows = List.of(first, second);

		final Invocation invocation = invoke(
			concat(REQUIRED, "wql", "SELECT Name,State FROM Win32_Service"),
			args -> remote
		);

		assertEquals(0, invocation.exitCode);
		assertEquals(
			"{\"Name\":\"Spooler\",\"State\":\"Running\"}" +
				System.lineSeparator() +
				"{\"Name\":\"WinRM\",\"State\":\"Running\"}" +
				System.lineSeparator(),
			invocation.stdout
		);
		assertEquals("", invocation.stderr);
		assertTrue(remote.closed);
	}

	@Test
	void forwardsCommandStreamsAndExitCode() throws Exception {
		final FakeRemote remote = new FakeRemote();
		remote.stdoutChunks = List.of("out", "put");
		remote.stderrChunks = List.of("warning");
		remote.commandExitCode = 7;

		final Invocation invocation = invoke(concat(REQUIRED, "exec", "echo", "hello world"), args -> remote);

		assertEquals(7, invocation.exitCode);
		assertEquals("output", invocation.stdout);
		assertEquals("warning", invocation.stderr);
		assertEquals("echo \"hello world\"", remote.command);
	}

	@Test
	void mapsUsageTimeoutConnectionAuthenticationAndProtocolFailures() throws Exception {
		assertEquals(
			WinRmCli.EXIT_USAGE,
			invoke(new String[]
			{ "--password=do-not-print" }, args -> failingRemote()).exitCode
		);

		final FakeRemote timeout = new FakeRemote();
		timeout.failure = new TimeoutException();
		assertEquals(WinRmCli.EXIT_TIMEOUT, invoke(concat(REQUIRED, "command", "whoami"), args -> timeout).exitCode);

		final FakeRemote connection = new FakeRemote();
		connection.failure = new ConnectException("connection refused");
		assertEquals(WinRmCli.EXIT_CONNECTION, invoke(concat(REQUIRED, "command", "whoami"), args -> connection).exitCode);

		final FakeRemote authentication = new FakeRemote();
		authentication.failure = new IllegalStateException("Authentication error on endpoint");
		assertEquals(
			WinRmCli.EXIT_AUTHENTICATION,
			invoke(concat(REQUIRED, "command", "whoami"), args -> authentication).exitCode
		);

		final FakeRemote protocol = new FakeRemote();
		protocol.failure = new IllegalStateException("WSMan fault");
		assertEquals(WinRmCli.EXIT_PROTOCOL, invoke(concat(REQUIRED, "command", "whoami"), args -> protocol).exitCode);
	}

	@Test
	void neverPrintsPasswordInUsageDiagnostics() throws Exception {
		final String secret = "unique-password-value";
		final Invocation invocation = invoke(
			new String[]
			{ "--password=" + secret, "--unknown=" + secret },
			args -> failingRemote()
		);

		assertEquals(WinRmCli.EXIT_USAGE, invocation.exitCode);
		assertFalse(invocation.stdout.contains(secret));
		assertFalse(invocation.stderr.contains(secret));
	}

	@Test
	void requestsAnOmittedPasswordAndClearsThePromptBuffer() throws Exception {
		final char[] promptedPassword = "prompted-secret".toCharArray();
		final String[] observedPassword = new String[1];
		final FakeRemote remote = new FakeRemote();

		final Invocation invocation = invoke(
			new String[]
			{ "-h", "host", "-u", "user", "command", "whoami" },
			arguments -> {
				observedPassword[0] = new String(arguments.password());
				return remote;
			},
			() -> promptedPassword
		);

		assertEquals(0, invocation.exitCode);
		assertEquals("prompted-secret", observedPassword[0]);
		assertArrayEquals(new char[promptedPassword.length], promptedPassword);
	}

	@Test
	void reportsMissingConsoleForNonInteractiveRuns() throws Exception {
		final Invocation invocation = invoke(
			new String[]
			{ "-h", "host", "-u", "user", "command", "whoami" },
			arguments -> failingRemote(),
			() -> {
				throw new CliUsageException(
					"no interactive console is available; use --password-file for non-interactive runs"
				);
			}
		);

		assertEquals(WinRmCli.EXIT_USAGE, invocation.exitCode);
		assertTrue(invocation.stderr.contains("no interactive console is available"));
		assertTrue(invocation.stderr.contains("--password-file"));
	}

	@Test
	void configuresInferredKerberosRealmBeforeConnectingAndRestoresProperties() throws Exception {
		final String originalKdc = System.getProperty(KERBEROS_KDC_PROPERTY);
		final String originalRealm = System.getProperty(KERBEROS_REALM_PROPERTY);
		System.setProperty(KERBEROS_KDC_PROPERTY, "previous-kdc.example.net");
		System.setProperty(KERBEROS_REALM_PROPERTY, "PREVIOUS.EXAMPLE.NET");
		try {
			final Invocation invocation = invoke(
				concat(
					REQUIRED,
					"--https",
					"--kerberos",
					"--kerberos-kdc",
					"camus.internal.sentrysoftware.net",
					"command",
					"whoami"
				),
				arguments -> {
					assertEquals(
						"camus.internal.sentrysoftware.net",
						System.getProperty(KERBEROS_KDC_PROPERTY)
					);
					assertEquals(
						"INTERNAL.SENTRYSOFTWARE.NET",
						System.getProperty(KERBEROS_REALM_PROPERTY)
					);
					return new FakeRemote();
				}
			);

			assertEquals(0, invocation.exitCode);
			assertTrue(
				invocation.stderr.contains(
					"using Kerberos realm INTERNAL.SENTRYSOFTWARE.NET inferred from KDC " +
						"camus.internal.sentrysoftware.net"
				)
			);
			assertEquals("previous-kdc.example.net", System.getProperty(KERBEROS_KDC_PROPERTY));
			assertEquals("PREVIOUS.EXAMPLE.NET", System.getProperty(KERBEROS_REALM_PROPERTY));
		} finally {
			restoreProperty(KERBEROS_KDC_PROPERTY, originalKdc);
			restoreProperty(KERBEROS_REALM_PROPERTY, originalRealm);
		}
	}

	@Test
	void honorsAnAmbientInsecureTlsProperty() throws Exception {
		final String original = System.getProperty(INSECURE_TLS_PROPERTY);
		System.setProperty(INSECURE_TLS_PROPERTY, Boolean.TRUE.toString());
		try {
			final Invocation invocation = invoke(
				concat(REQUIRED, "--https", "command", "whoami"),
				arguments -> {
					assertEquals(Boolean.TRUE.toString(), System.getProperty(INSECURE_TLS_PROPERTY));
					return new FakeRemote();
				}
			);

			assertEquals(0, invocation.exitCode);
			assertEquals(Boolean.TRUE.toString(), System.getProperty(INSECURE_TLS_PROPERTY));
		} finally {
			restoreProperty(INSECURE_TLS_PROPERTY, original);
		}
	}

	@Test
	void decodesCommandOutputUsingTheRemoteWindowsCodePage() throws Exception {
		final Charset windowsCharset = Charset.forName("windows-1251");

		// Full stack against the in-process WSMan server, through the CLI's real connect factory
		// and its streaming forwarders: the remote reports Windows code page 1251 and the command
		// output arrives in that encoding — the CLI must query the code page and decode the stream
		// bytes with it, or the Cyrillic output turns into mojibake.
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueEnumeration(server, instance("Win32_OperatingSystem", "CodeSet", "1251"));
			enqueueShellCreation(server);
			enqueueCommandExchange(server, "Результат".getBytes(windowsCharset), new byte[0], 0);
			enqueueShellDeletion(server);

			final Invocation invocation = invoke(
				new String[]
				{
						"-h",
						"127.0.0.1",
						"-P",
						String.valueOf(server.port()),
						"-u",
						"FAKE\\user",
						"-p",
						"secret",
						"-t",
						"30000",
						"exec",
						"whoami"
				},
				WinRmCli::connect
			);

			assertEquals(0, invocation.exitCode);
			assertEquals("Результат", invocation.stdout);
			assertEquals("", invocation.stderr);

			// The decoding charset really came from the remote code-page query
			assertTrue(
				server
					.decryptedRequests()
					.stream()
					.anyMatch(request -> request.contains("SELECT CodeSet FROM Win32_OperatingSystem"))
			);
		}
	}

	private static WinRmCli.RemoteOperations failingRemote() {
		throw new AssertionError("No connection expected");
	}

	private static Invocation invoke(final String[] arguments, final WinRmCli.RemoteFactory factory) throws Exception {
		return invoke(
			arguments,
			factory,
			() -> {
				throw new AssertionError("No password prompt expected");
			}
		);
	}

	private static Invocation invoke(
		final String[] arguments,
		final WinRmCli.RemoteFactory factory,
		final WinRmCli.PasswordReader passwordReader
	) throws Exception {
		final ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
		final ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
		try (
			PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8.name());
			PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8.name())) {
			final int exitCode = WinRmCli.run(arguments, stdout, stderr, factory, passwordReader);
			return new Invocation(
				exitCode,
				stdoutBytes.toString(StandardCharsets.UTF_8.name()),
				stderrBytes.toString(StandardCharsets.UTF_8.name())
			);
		}
	}

	private static String[] concat(final String[] prefix, final String... suffix) {
		final String[] result = new String[prefix.length + suffix.length];
		System.arraycopy(prefix, 0, result, 0, prefix.length);
		System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
		return result;
	}

	private static void restoreProperty(final String name, final String value) {
		if (value == null) {
			System.clearProperty(name);
		} else {
			System.setProperty(name, value);
		}
	}

	private static final class Invocation {

		private final int exitCode;
		private final String stdout;
		private final String stderr;

		private Invocation(final int exitCode, final String stdout, final String stderr) {
			this.exitCode = exitCode;
			this.stdout = stdout;
			this.stderr = stderr;
		}
	}

	private static final class FakeRemote implements WinRmCli.RemoteOperations {

		private List<Map<String, Object>> rows = List.of();
		private List<String> stdoutChunks = List.of();
		private List<String> stderrChunks = List.of();
		private int commandExitCode;
		private Exception failure;
		private String command;
		private boolean closed;

		@Override
		public void streamWql(final String query, final long timeout, final Consumer<Map<String, Object>> rowConsumer)
			throws Exception {
			failIfConfigured();
			rows.forEach(rowConsumer);
		}

		@Override
		public int executeCommand(
			final String command,
			final long timeout,
			final Consumer<String> stdoutConsumer,
			final Consumer<String> stderrConsumer
		) throws Exception {
			this.command = command;
			failIfConfigured();
			stdoutChunks.forEach(stdoutConsumer);
			stderrChunks.forEach(stderrConsumer);
			return commandExitCode;
		}

		@Override
		public void close() {
			closed = true;
		}

		private void failIfConfigured() throws Exception {
			if (failure != null) {
				throw failure;
			}
		}
	}
}
