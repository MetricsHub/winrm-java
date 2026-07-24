package org.metricshub.winrm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WindowsRemoteCommandResult;

class WinRmCliTest {

	private static final String[] REQUIRED = { "-h", "host", "-u", "user", "-p", "secret" };
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
		remote.commandResult = new WindowsRemoteCommandResult("output", "warning", 0.1f, 7);

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
		private WindowsRemoteCommandResult commandResult = new WindowsRemoteCommandResult("", "", 0.0f, 0);
		private Exception failure;
		private String command;
		private boolean closed;

		@Override
		public List<Map<String, Object>> executeWql(final String query, final long timeout) throws Exception {
			failIfConfigured();
			return rows;
		}

		@Override
		public WindowsRemoteCommandResult executeCommand(final String command, final long timeout) throws Exception {
			this.command = command;
			failIfConfigured();
			return commandResult;
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
