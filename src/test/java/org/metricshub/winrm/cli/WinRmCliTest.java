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
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellCreation;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellDeletion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.light.FakeWsmanResponses;
import org.metricshub.winrm.light.FakeWsmanServer;

class WinRmCliTest {

	private static final String[] REQUIRED = { "-h", "host", "-u", "user", "-p", "secret" };
	private static final String INSECURE_TLS_PROPERTY = "org.metricshub.winrm.tls.insecure";
	private static final String KERBEROS_KDC_PROPERTY = "java.security.krb5.kdc";
	private static final String KERBEROS_REALM_PROPERTY = "java.security.krb5.realm";

	private static final String COMMAND_ID = "CMD-1";
	private static final String DIR = "C:\\inetpub\\logs";

	/** 2026-01-02T03:04:05.6789012Z as a Windows file time, and as the CLI prints it. */
	private static final long FILETIME = 134_117_966_456_789_012L;
	private static final String TIMESTAMP = "2026-01-02T03:04:05.6789012Z";

	@Test
	void helpAndVersionDoNotConnect() throws Exception {
		final Invocation help = invoke(new String[] { "--help" }, arguments -> failingRemote());
		assertEquals(0, help.exitCode);
		assertTrue(help.stdout.contains("command|cmd|exec|run"));
		assertTrue(help.stdout.contains("[options] shell"));
		assertTrue(help.stdout.contains("-P, --port"));
		assertTrue(help.stdout.contains("-d, --directory"));
		assertTrue(help.stdout.contains("--env <NAME=VALUE>"));
		assertTrue(help.stdout.contains("--kerberos-kdc"));
		assertTrue(help.stdout.contains("--kerberos-realm"));
		assertTrue(help.stdout.contains("--allow-delegate"));
		assertTrue(help.stdout.contains("[options] ls <directory>"));
		assertTrue(help.stdout.contains("[options] get <file> [<local path>]"));
		assertTrue(help.stdout.contains("--modified-after <date>"));
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
	void passesTheDirectoryOptionThroughAsTheWorkingDirectory() throws Exception {
		final FakeRemote remote = new FakeRemote();
		final Invocation invocation = invoke(concat(REQUIRED, "-d", "C:\\build", "exec", "build.cmd"), args -> remote);
		assertEquals(0, invocation.exitCode);
		assertEquals("C:\\build", remote.workingDirectory);

		// The shell subcommand starts in the requested directory too.
		final FakeRemote shellRemote = new FakeRemote();
		assertEquals(0, invoke(concat(REQUIRED, "--directory=C:\\build", "shell"), args -> shellRemote).exitCode);
		assertEquals("C:\\build", shellRemote.workingDirectory);

		// Without the option, no working directory is sent: the remote default applies.
		final FakeRemote defaulted = new FakeRemote();
		assertEquals(0, invoke(concat(REQUIRED, "exec", "build.cmd"), args -> defaulted).exitCode);
		org.junit.jupiter.api.Assertions.assertNull(defaulted.workingDirectory);
	}

	@Test
	void passesTheEnvOptionsThroughAsTheShellEnvironment() throws Exception {
		final FakeRemote remote = new FakeRemote();
		final Invocation invocation = invoke(
			concat(REQUIRED, "--env", "BUILD_NUMBER=42", "--env", "CONFIG=release", "exec", "build.cmd"),
			args -> remote
		);
		assertEquals(0, invocation.exitCode);
		assertEquals(Map.of("BUILD_NUMBER", "42", "CONFIG", "release"), remote.environment);

		// The shell subcommand gets the environment too.
		final FakeRemote shellRemote = new FakeRemote();
		assertEquals(0, invoke(concat(REQUIRED, "--env=CONFIG=release", "shell"), args -> shellRemote).exitCode);
		assertEquals(Map.of("CONFIG", "release"), shellRemote.environment);

		// Without the option, an empty environment is passed: the remote default applies.
		final FakeRemote defaulted = new FakeRemote();
		assertEquals(0, invoke(concat(REQUIRED, "exec", "build.cmd"), args -> defaulted).exitCode);
		assertTrue(defaulted.environment.isEmpty());
	}

	@Test
	void sendsTheWorkingDirectoryInTheCreateShellRequest() throws Exception {
		// Full stack against the in-process WSMan server, through the CLI's real connect factory:
		// --directory must reach the wire as rsp:WorkingDirectory in the Create shell request.
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueCommandExchange(server, "ok".getBytes(StandardCharsets.UTF_8), new byte[0], 0);
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
						"-d",
						"C:\\build",
						"--env",
						"BUILD_NUMBER=42",
						"exec",
						"build.cmd"
				},
				WinRmCli::connect
			);

			assertEquals(0, invocation.exitCode);
			assertEquals("ok", invocation.stdout);
			final String create = server.decryptedRequests().get(0);
			assertTrue(create.contains("<rsp:WorkingDirectory>C:\\build</rsp:WorkingDirectory>"), create);
			// --env reaches the wire too, as the rsp:Environment block of the same Create request.
			assertTrue(
				create.contains("<rsp:Environment><rsp:Variable Name=\"BUILD_NUMBER\">42</rsp:Variable></rsp:Environment>"),
				create
			);
		}
	}

	@Test
	void shellSubcommandBridgesTheRemoteShellAndPropagatesItsExitCode() throws Exception {
		final FakeRemote remote = new FakeRemote();
		remote.stdoutChunks = List.of("Microsoft Windows\r\nC:\\>");
		remote.commandExitCode = 3;

		final Invocation invocation = invoke(concat(REQUIRED, "shell"), args -> remote);

		assertEquals(3, invocation.exitCode);
		assertTrue(remote.shellStarted);
		assertEquals("Microsoft Windows\r\nC:\\>", invocation.stdout);
		assertTrue(remote.closed);
	}

	@Test
	void shellRunsAQuietCmdUnderASingleByteCodePage() throws Exception {
		// Full stack against the in-process WSMan server, through the CLI's real connect factory.
		// The shell must be echo-free (cmd.exe /Q) and must NOT run under console code page 65001:
		// a remote cmd.exe decodes the command lines it reads from stdin one byte at a time under
		// that page, losing every non-ASCII character. The ANSI code page probe precedes the shell
		// and its answer becomes the shell's console code page.
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			server.enqueue(
				200,
				FakeWsmanResponses.envelope(
					FakeWsmanResponses.enumerationDone(FakeWsmanResponses.instance("Win32_OperatingSystem", "CodeSet", "1252"))
				)
			);
			enqueueShellCreation(server);
			server
				.enqueue(200, FakeWsmanResponses.envelope(FakeWsmanResponses.commandResponse("CMD-1")))
				.enqueue(
					200,
					FakeWsmanResponses.envelope(FakeWsmanResponses.receiveResponse("", FakeWsmanResponses.done("CMD-1", 0)))
				)
				.enqueue(200, FakeWsmanResponses.envelope(FakeWsmanResponses.signalResponse()));
			enqueueShellDeletion(server);

			// A local input that never delivers anything: the pump only ever polls, keeping the
			// scripted exchange deterministic (the daemon reader thread blocks forever).
			final java.io.InputStream never = new java.io.InputStream() {
				@Override
				public int read() {
					try {
						new java.util.concurrent.CountDownLatch(1).await();
					} catch (final InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return -1;
				}
			};

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
						"10000",
						"-d",
						"C:\\build",
						"--env",
						"CONFIG=release",
						"shell"
				},
				WinRmCli::connect,
				new WinRmCli.LocalInput(true, never)
			);

			assertEquals(0, invocation.exitCode);
			final List<String> requests = server.decryptedRequests();
			assertTrue(requests.get(0).contains("Win32_OperatingSystem"), requests.get(0));
			final String create = requests.get(1);
			assertTrue(create.contains("<wsman:Option Name=\"WINRS_CODEPAGE\">1252</wsman:Option>"), create);
			assertTrue(create.contains("<rsp:WorkingDirectory>C:\\build</rsp:WorkingDirectory>"), create);
			assertTrue(
				create.contains("<rsp:Environment><rsp:Variable Name=\"CONFIG\">release</rsp:Variable></rsp:Environment>"),
				create
			);
			final String command = requests.get(2);
			assertTrue(command.contains("<rsp:Command>cmd.exe /Q</rsp:Command>"), command);
			assertTrue(command.contains("<wsman:Option Name=\"WINRS_CONSOLEMODE_STDIN\">FALSE</wsman:Option>"), command);
		}
	}

	@Test
	void theShellCodePageAndCharsetAreAlwaysResolvedTogether() {
		// A page this JVM has a charset for is used as reported...
		assertEquals(1252, WinRmCli.FluentRemoteOperations.sessionEncoding(1252).codePage());
		assertEquals(
			java.nio.charset.Charset.forName("windows-1252"),
			WinRmCli.FluentRemoteOperations.sessionEncoding(1252).charset()
		);
		assertEquals(850, WinRmCli.FluentRemoteOperations.sessionEncoding(850).codePage());
		assertEquals(
			java.nio.charset.Charset.forName("IBM850"),
			WinRmCli.FluentRemoteOperations.sessionEncoding(850).charset()
		);

		// ...but 65001 — what a host configured for UTF-8 reports as its ANSI page — is precisely
		// the page an interactive cmd.exe cannot read command lines under, so BOTH the page and
		// the charset fall back rather than pinning the shell to a page the session cannot use.
		assertEquals(1252, WinRmCli.FluentRemoteOperations.sessionEncoding(65001).codePage());
		assertEquals(
			java.nio.charset.Charset.forName("windows-1252"),
			WinRmCli.FluentRemoteOperations.sessionEncoding(65001).charset()
		);

		// An unknown page falls back on both counts too: a charset that does not match the shell's
		// code page would corrupt the session in both directions.
		assertEquals(1252, WinRmCli.FluentRemoteOperations.sessionEncoding(999_999).codePage());
		assertEquals(
			java.nio.charset.Charset.forName("windows-1252"),
			WinRmCli.FluentRemoteOperations.sessionEncoding(999_999).charset()
		);
	}

	@Test
	void shellTakesNoArgument() throws Exception {
		final Invocation invocation = invoke(concat(REQUIRED, "shell", "cmd.exe"), args -> failingRemote());
		assertEquals(WinRmCli.EXIT_USAGE, invocation.exitCode);
		assertTrue(invocation.stderr.contains("shell takes no argument"));
	}

	@Test
	void shellRejectsATimeoutBelowThePollFloor() throws Exception {
		// A --timeout below one poll round trip would make the session pump spin locally without
		// ever fetching output: the WSMan service holds a bounded Receive for at least 500 ms.
		final Invocation invocation = invoke(concat(REQUIRED, "-t", "500", "shell"), args -> failingRemote());
		assertEquals(WinRmCli.EXIT_USAGE, invocation.exitCode);
		assertTrue(invocation.stderr.contains("shell requires --timeout of at least 1000 milliseconds"));

		// The same timeout stays perfectly valid for the other subcommands.
		final FakeRemote remote = new FakeRemote();
		assertEquals(0, invoke(concat(REQUIRED, "-t", "500", "command", "whoami"), args -> remote).exitCode);
	}

	@Test
	void pipedLocalStandardInputIsForwardedToTheCommandButAConsoleIsNot() throws Exception {
		final java.io.ByteArrayInputStream piped = new java.io.ByteArrayInputStream(
			"beta\nalpha\n".getBytes(StandardCharsets.UTF_8)
		);
		final FakeRemote remote = new FakeRemote();
		Invocation invocation = invoke(
			concat(REQUIRED, "command", "sort"),
			args -> remote,
			new WinRmCli.LocalInput(false, piped)
		);
		assertEquals(0, invocation.exitCode);
		assertEquals(piped, remote.forwardedStdin);

		// From an interactive console, nothing is forwarded implicitly.
		final FakeRemote interactive = new FakeRemote();
		invocation = invoke(
			concat(REQUIRED, "command", "sort"),
			args -> interactive,
			new WinRmCli.LocalInput(true, piped)
		);
		assertEquals(0, invocation.exitCode);
		org.junit.jupiter.api.Assertions.assertNull(interactive.forwardedStdin);
	}

	@Test
	void detectsRedirectedStdinByProbingTheInputItself() {
		// Bytes already waiting on a non-console stdin: a pipe or a redirected file.
		assertTrue(WinRmCli.stdinHasAvailableInput(new java.io.ByteArrayInputStream(new byte[] { 1 })));

		// Nothing waiting: typically an interactive terminal whose OUTPUT is redirected
		// (System.console() is null then too) — consuming it would hang the CLI.
		assertFalse(WinRmCli.stdinHasAvailableInput(new java.io.ByteArrayInputStream(new byte[0])));

		// A probe failure counts as not redirected: never risk blocking on a terminal.
		assertFalse(
			WinRmCli.stdinHasAvailableInput(
				new java.io.InputStream() {
					@Override
					public int read() {
						return -1;
					}

					@Override
					public int available() throws java.io.IOException {
						throw new java.io.IOException("probe failure");
					}
				}
			)
		);
	}

	@Test
	void explicitStdinOptionForcesForwardingAndRequiresTheCommandSubcommand() throws Exception {
		// --stdin forwards even when the local input looks interactive (the undetectable cases:
		// an empty redirection, a pipe whose producer starts slowly).
		final java.io.ByteArrayInputStream local = new java.io.ByteArrayInputStream(new byte[0]);
		final FakeRemote remote = new FakeRemote();
		final Invocation invocation = invoke(
			concat(REQUIRED, "--stdin", "command", "sort"),
			args -> remote,
			new WinRmCli.LocalInput(true, local)
		);
		assertEquals(0, invocation.exitCode);
		assertEquals(local, remote.forwardedStdin);

		// The option is meaningless outside the command subcommand.
		final Invocation rejected = invoke(concat(REQUIRED, "--stdin", "wql", "SELECT 1"), args -> failingRemote());
		assertEquals(WinRmCli.EXIT_USAGE, rejected.exitCode);
		assertTrue(rejected.stderr.contains("--stdin requires the command subcommand"));
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
	void decodesCommandOutputAsUtf8WhateverTheRemoteLocale() throws Exception {
		// Full stack against the in-process WSMan server, through the CLI's real connect factory and
		// its streaming forwarders: the remote shell is created with code page 65001, so its output
		// arrives as UTF-8 and needs no code-page probe. Cyrillic and French accents must survive
		// verbatim — decoding them as a single-byte code page is what produced mojibake (#142).
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueCommandExchange(server, "Результат : numéro".getBytes(StandardCharsets.UTF_8), new byte[0], 0);
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
			assertEquals("Результат : numéro", invocation.stdout);
			assertEquals("", invocation.stderr);

			// No code-page probe: the shell's Create request pins 65001, so the charset is known.
			final List<String> requests = server.decryptedRequests();
			assertTrue(
				requests.stream().noneMatch(request -> request.contains("Win32_OperatingSystem")),
				() -> String.join("\n---\n", requests)
			);
			assertTrue(
				requests.get(0).contains("<wsman:Option Name=\"WINRS_CODEPAGE\">65001</wsman:Option>"),
				requests.get(0)
			);
		}
	}

	@Test
	void lsStreamsTheLongFormatAndExitsWith1WhenADirectoryCannotBeRead() throws Exception {
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			final String denied = "! " + b64(DIR + "\\W3SVC1\\private") + " " + b64("Access is denied.") + "\n";
			enqueueShellCreation(server);
			// The second entry arrives in a later Receive than the first.
			enqueueScript(
				server,
				0,
				directoryRecord(DIR + "\\W3SVC1") + denied.substring(0, 10),
				denied.substring(10) + fileRecord(DIR + "\\W3SVC1\\u_ex260101.log", 5_000_000_000L)
			);
			enqueueShellDeletion(server);

			// Record how far the exchange had gone when the first entry reached standard output.
			final int[] requestsAtFirstEntry = { -1 };
			final ByteArrayOutputStream stdout = new ByteArrayOutputStream() {
				@Override
				public synchronized void write(final byte[] bytes, final int offset, final int length) {
					if (requestsAtFirstEntry[0] < 0) {
						requestsAtFirstEntry[0] = server.decryptedRequests().size();
					}
					super.write(bytes, offset, length);
				}
			};
			final Invocation invocation = invokeAgainst(server, stdout, "ls", DIR, "--recursive");

			assertEquals(WinRmCli.EXIT_PARTIAL, invocation.exitCode);
			assertEquals(
				"d-----            0 " + TIMESTAMP + " " + DIR + "\\W3SVC1" + System.lineSeparator() +
					"-a----   5000000000 " + TIMESTAMP + " " + DIR + "\\W3SVC1\\u_ex260101.log" + System.lineSeparator(),
				invocation.stdout
			);
			assertEquals(
				"winrm-java: cannot read directory " + DIR + "\\W3SVC1\\private" + System.lineSeparator(),
				invocation.stderr
			);
			// Streamed: the first entry was written before the second Receive was even sent
			// (Create, Command, one Receive).
			assertEquals(3, requestsAtFirstEntry[0]);
			assertTrue(sentScripts(server).get(0).contains(";$md=" + Integer.MAX_VALUE + ";"), sentScripts(server).get(0));
		}
	}

	@Test
	void lsFiltersReachTheHostAndJsonCarriesEveryField() throws Exception {
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, fileRecord(DIR + "\\a.log", 10) + "\n");
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(
				server,
				"ls",
				"--glob",
				"*.log",
				DIR,
				"--depth=3",
				"--files-only",
				"--modified-after",
				"2026-01-01",
				"--min-size",
				"1024",
				"--json"
			);

			assertEquals(0, invocation.exitCode);
			assertEquals(json(DIR + "\\a.log", "-a----", 32, 10) + System.lineSeparator(), invocation.stdout);
			assertEquals("", invocation.stderr);
			final long after = 116_444_736_000_000_000L
				+ Instant.parse("2026-01-01T00:00:00Z").getEpochSecond() * 10_000_000L;
			final String script = sentScripts(server).get(0);
			assertTrue(script.contains(b64(DIR)), script);
			assertTrue(
				script.contains(
					"FromBase64String('" + b64("^.*\\.log$") + "'));$k=1;$mn=1024;$mx=" + Long.MAX_VALUE + ";$ta=" + after +
						";$tb=" + Long.MAX_VALUE + ";$md=3;"
				),
				script
			);
		}
	}

	@Test
	void statPrintsOneFieldPerLineOrJson() throws Exception {
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, directoryRecord(DIR).replace("F 16 ", "F 1046 "));
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(server, "stat", DIR);

			assertEquals(0, invocation.exitCode);
			// 1046 = 0x416: a hidden system directory that is a reparse point (a junction)
			assertEquals(
				String.join(
					System.lineSeparator(),
					"path: " + DIR,
					"mode: d--hsl",
					"attributes: 1046",
					"size: 0",
					"lastModified: " + TIMESTAMP,
					"created: " + TIMESTAMP,
					"lastAccessed: " + TIMESTAMP
				) +
					System.lineSeparator(),
				invocation.stdout
			);
			assertTrue(sentScripts(server).get(0).contains(b64(DIR)), sentScripts(server).get(0));
		}
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, fileRecord(DIR + "\\a.log", 10));
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(server, "stat", DIR + "\\a.log", "--json");

			assertEquals(0, invocation.exitCode);
			assertEquals(json(DIR + "\\a.log", "-a----", 32, 10) + System.lineSeparator(), invocation.stdout);
		}
	}

	@Test
	void aMissingRemotePathExitsWith66() throws Exception {
		// stat: the library reports a missing path as an empty result
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 2, "");
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(server, "stat", DIR + "\\missing.log");

			assertEquals(WinRmCli.EXIT_NOT_FOUND, invocation.exitCode);
			assertEquals("", invocation.stdout);
			assertEquals(
				"winrm-java: Remote path not found on 127.0.0.1: " + DIR + "\\missing.log" + System.lineSeparator(),
				invocation.stderr
			);
		}
		// cat, ls, get: as a failure
		for (final String subcommand : List.of("cat", "ls", "get")) {
			try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
				enqueueShellCreation(server);
				enqueueScript(server, 2, "");
				enqueueShellDeletion(server);

				final Invocation invocation = invokeAgainst(server, subcommand, DIR + "\\missing.log");

				assertEquals(WinRmCli.EXIT_NOT_FOUND, invocation.exitCode, subcommand);
				assertEquals(
					"winrm-java: Remote path not found on 127.0.0.1: " + DIR + "\\missing.log" + System.lineSeparator(),
					invocation.stderr,
					subcommand
				);
			}
		}
	}

	@Test
	void catWritesTheRemoteBytesUnconverted() throws Exception {
		// Every byte value, then sequences any text round trip would alter: CRLF, lone LF and CR,
		// invalid UTF-8, a UTF-16 byte order mark, Ctrl+Z.
		final byte[] content = new byte[256 + 9];
		for (int i = 0; i < 256; i++) {
			content[i] = (byte) i;
		}
		System
			.arraycopy(new byte[]
			{ '\r', '\n', '\n', '\r', (byte) 0xC3, 0x28, (byte) 0xFF, (byte) 0xFE, 0x1A }, 0, content, 256, 9);
		final String lines = b64(Arrays.copyOfRange(content, 0, 200)) + "\r\n"
			+ b64(Arrays.copyOfRange(content, 200, content.length)) +
			"\r\n";
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, lines.substring(0, 100), lines.substring(100));
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(server, "cat", "C:\\Windows\\Temp\\collect.bin");

			assertEquals(0, invocation.exitCode);
			assertArrayEquals(content, invocation.stdoutBytes);
			assertEquals("", invocation.stderr);
			// The whole file
			assertTrue(sentScripts(server).get(0).contains("$n=[long]0;$l=[long]-1;"), sentScripts(server).get(0));
		}
	}

	@Test
	void catReadsARangeAndDecodesTextWithTheGivenCharset() throws Exception {
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, b64(new byte[] { 'c', 'a', 'f', (byte) 0xE9 }) + "\r\n");
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(
				server,
				"cat",
				"C:\\legacy\\report.txt",
				"--offset",
				"-8192",
				"--length",
				"1024",
				"--charset",
				"windows-1252"
			);

			assertEquals(0, invocation.exitCode);
			// Decoded as windows-1252, printed in the local console's encoding (UTF-8 here)
			assertEquals("café", invocation.stdout);
			assertTrue(sentScripts(server).get(0).contains("$n=[long]-8192;$l=[long]1024;"), sentScripts(server).get(0));
		}
	}

	@Test
	void aClosedStandardOutputStopsTheRemoteRead() throws Exception {
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			// The first block of a longer file: the read is still running...
			server
				.enqueue(200, FakeWsmanResponses.envelope(FakeWsmanResponses.commandResponse(COMMAND_ID)))
				.enqueue(
					200,
					FakeWsmanResponses.envelope(FakeWsmanResponses.receiveResponse(stdoutStream(b64(new byte[]
					{ 1, 2, 3 }) + "\r\n"), null))
				)
				// ...when the closed output makes the CLI stop it: the terminate Signal, which a real
				// host answers only when its OperationTimeout expires, the read being blocked writing
				.enqueue(
					500,
					FakeWsmanResponses
						.fault(
							"2150858793",
							"The WS-Management service cannot complete the operation within the time specified in OperationTimeout."
						)
				);
			enqueueShellDeletion(server);

			// What "| head" does to the standard output once it has seen enough
			final OutputStream closed = new OutputStream() {
				@Override
				public void write(final int b) throws IOException {
					throw new IOException("The pipe is being closed");
				}
			};
			final Invocation invocation = invokeAgainst(server, closed, "cat", "D:\\logs\\huge.log");

			assertEquals(WinRmCli.EXIT_IO, invocation.exitCode);
			assertEquals("winrm-java: cannot write to standard output" + System.lineSeparator(), invocation.stderr);
			assertEquals(1, server.decryptedRequests().stream().filter(r -> r.contains("<rsp:Receive>")).count());
			assertTrue(server.decryptedRequests().stream().anyMatch(r -> r.contains("<rsp:Signal ")));
		}
	}

	@Test
	void getDownloadsIntoAnExistingDirectoryUnderTheRemoteName(@TempDir final Path directory) throws Exception {
		final byte[] content = "the log content".getBytes(StandardCharsets.US_ASCII);
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, probe(content));
			enqueueScript(server, 0, b64(content) + "\r\n");
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(
				server,
				"get",
				"C:\\Windows\\Temp\\collect.log",
				directory.toString()
			);

			assertEquals(0, invocation.exitCode);
			assertEquals("", invocation.stdout);
			assertEquals("", invocation.stderr);
			assertArrayEquals(content, Files.readAllBytes(directory.resolve("collect.log")));
		}
	}

	@Test
	void aLocalFileThatCannotBeWrittenExitsWith74(@TempDir final Path directory) throws Exception {
		// The destination's parent is a regular file: its directory cannot be created.
		final Path blocker = Files.write(directory.resolve("blocker"), new byte[0]);
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "secret")) {
			enqueueShellCreation(server);
			enqueueScript(server, 0, probe("remote".getBytes(StandardCharsets.US_ASCII)));
			enqueueShellDeletion(server);

			final Invocation invocation = invokeAgainst(
				server,
				"get",
				"C:\\Windows\\Temp\\collect.log",
				blocker.resolve("collect.log").toString()
			);

			// A local failure, not a connection failure
			assertEquals(WinRmCli.EXIT_IO, invocation.exitCode, invocation.stderr);
			assertTrue(
				invocation.stderr.startsWith("winrm-java: Download of C:\\Windows\\Temp\\collect.log"),
				invocation.stderr
			);
		}
	}

	private static String b64(final byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static String b64(final String text) {
		return b64(text.getBytes(StandardCharsets.UTF_8));
	}

	/** The record the metadata scripts write for a file (attributes 32: archive). */
	private static String fileRecord(final String path, final long size) {
		return "F 32 " + size + " " + FILETIME + " " + FILETIME + " " + FILETIME + " " + b64(path) + "\n";
	}

	/** The record the metadata scripts write for a directory (attributes 16). */
	private static String directoryRecord(final String path) {
		return "F 16 0 " + FILETIME + " " + FILETIME + " " + FILETIME + " " + b64(path) + "\n";
	}

	/** The --json line of an entry whose three timestamps are {@link #TIMESTAMP}. */
	private static String json(final String path, final String mode, final int attributes, final long size) {
		return "{\"path\":\"" + path.replace("\\", "\\\\") + "\",\"mode\":\"" + mode + "\",\"attributes\":" + attributes
			+ ",\"size\":" +
			size + ",\"lastModified\":\"" + TIMESTAMP + "\",\"created\":\"" + TIMESTAMP + "\",\"lastAccessed\":\"" + TIMESTAMP
			+ "\"}";
	}

	/** The probe output of a download: the size (8 bytes, little-endian), then the SHA-256 digest. */
	private static String probe(final byte[] content) throws Exception {
		return b64(
			ByteBuffer
				.allocate(40)
				.order(ByteOrder.LITTLE_ENDIAN)
				.putLong(content.length)
				.put(MessageDigest.getInstance("SHA-256").digest(content))
				.array()
		) +
			"\r\n";
	}

	private static String stdoutStream(final String text) {
		return FakeWsmanResponses.stream("stdout", COMMAND_ID, text.getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * Script one remote file script on the existing shell: the command, one Receive per stdout
	 * chunk (the last one completes with the exit code), and the Signal ending it.
	 */
	private static void enqueueScript(final FakeWsmanServer server, final int exitCode, final String... chunks) {
		server.enqueue(200, FakeWsmanResponses.envelope(FakeWsmanResponses.commandResponse(COMMAND_ID)));
		for (int i = 0; i < chunks.length; i++) {
			final boolean last = i == chunks.length - 1;
			server.enqueue(
				200,
				FakeWsmanResponses
					.envelope(
						FakeWsmanResponses
							.receiveResponse(stdoutStream(chunks[i]), last ? FakeWsmanResponses.done(COMMAND_ID, exitCode) : null)
					)
			);
		}
		server.enqueue(200, FakeWsmanResponses.envelope(FakeWsmanResponses.signalResponse()));
	}

	/** The PowerShell scripts the client sent, in order, decoded from their -EncodedCommand. */
	private static List<String> sentScripts(final FakeWsmanServer server) {
		final Pattern encoded = Pattern.compile("-EncodedCommand ([A-Za-z0-9+/=]+)");
		return server
			.decryptedRequests()
			.stream()
			.map(encoded::matcher)
			.filter(Matcher::find)
			.map(m -> new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_16LE))
			.collect(Collectors.toList());
	}

	private static String[] fakeServerOptions(final FakeWsmanServer server) {
		return new String[] {
				"-h",
				"127.0.0.1",
				"-P",
				String.valueOf(server.port()),
				"-u",
				"FAKE\\user",
				"-p",
				"secret",
				"-t",
				"30000" };
	}

	/** Run the CLI through its real connect factory against the fake server. */
	private static Invocation invokeAgainst(final FakeWsmanServer server, final String... subcommand) throws Exception {
		return invokeAgainst(server, new ByteArrayOutputStream(), subcommand);
	}

	/**
	 * Run the CLI through its real connect factory against the fake server, writing its standard output to the given
	 * stream.
	 */
	private static Invocation invokeAgainst(
		final FakeWsmanServer server,
		final OutputStream stdoutTarget,
		final String... subcommand
	)
		throws Exception {
		final ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
		try (
			PrintStream stdout = new PrintStream(stdoutTarget, true, StandardCharsets.UTF_8.name());
			PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8.name())) {
			final int exitCode = WinRmCli.run(
				concat(fakeServerOptions(server), subcommand),
				stdout,
				stderr,
				WinRmCli::connect,
				() -> {
					throw new AssertionError("No password prompt expected");
				}
			);
			return new Invocation(
				exitCode,
				stdoutTarget instanceof ByteArrayOutputStream
					? ((ByteArrayOutputStream) stdoutTarget).toByteArray() : new byte[0],
				stderrBytes.toString(StandardCharsets.UTF_8.name())
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
			return new Invocation(exitCode, stdoutBytes.toByteArray(), stderrBytes.toString(StandardCharsets.UTF_8.name()));
		}
	}

	private static Invocation invoke(
		final String[] arguments,
		final WinRmCli.RemoteFactory factory,
		final WinRmCli.LocalInput localInput
	) throws Exception {
		final ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
		final ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
		try (
			PrintStream stdout = new PrintStream(stdoutBytes, true, StandardCharsets.UTF_8.name());
			PrintStream stderr = new PrintStream(stderrBytes, true, StandardCharsets.UTF_8.name())) {
			final int exitCode = WinRmCli.run(
				arguments,
				stdout,
				stderr,
				factory,
				() -> {
					throw new AssertionError("No password prompt expected");
				},
				localInput
			);
			return new Invocation(exitCode, stdoutBytes.toByteArray(), stderrBytes.toString(StandardCharsets.UTF_8.name()));
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
		private final byte[] stdoutBytes;
		private final String stdout;
		private final String stderr;

		private Invocation(final int exitCode, final byte[] stdoutBytes, final String stderr) {
			this.exitCode = exitCode;
			this.stdoutBytes = stdoutBytes;
			this.stdout = new String(stdoutBytes, StandardCharsets.UTF_8);
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
		private String workingDirectory;
		private Map<String, String> environment;
		private java.io.InputStream forwardedStdin;
		private boolean shellStarted;
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
			final String workingDirectory,
			final Map<String, String> environment,
			final long timeout,
			final java.io.InputStream stdin,
			final Consumer<String> stdoutConsumer,
			final Consumer<String> stderrConsumer
		) throws Exception {
			this.command = command;
			this.workingDirectory = workingDirectory;
			this.environment = environment;
			this.forwardedStdin = stdin;
			failIfConfigured();
			stdoutChunks.forEach(stdoutConsumer);
			stderrChunks.forEach(stderrConsumer);
			return commandExitCode;
		}

		@Override
		public int shell(
			final long timeout,
			final String workingDirectory,
			final Map<String, String> environment,
			final java.io.InputStream localInput,
			final java.io.PrintStream out,
			final java.io.PrintStream err,
			final java.util.concurrent.atomic.AtomicBoolean interruptRequested
		) throws Exception {
			shellStarted = true;
			this.workingDirectory = workingDirectory;
			this.environment = environment;
			failIfConfigured();
			stdoutChunks.forEach(chunk -> {
				out.print(chunk);
				out.flush();
			});
			return commandExitCode;
		}

		@Override
		public org.metricshub.winrm.RemoteFile file(final String path) {
			throw new AssertionError("No remote file access expected");
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
