package org.metricshub.winrm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

class CliArgumentsTest {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void parsesDefaultsAndClearsDirectPassword() throws Exception {
		final String[] arguments = {
				"-h",
				"server.example.net",
				"-u",
				"DOMAIN\\user",
				"-p",
				"secret",
				"wql",
				"SELECT Name FROM Win32_Service"
		};

		final CliArguments parsed = CliArguments.parse(arguments);
		assertEquals(CliArguments.Operation.WQL, parsed.operation());
		assertEquals("server.example.net", parsed.hostname());
		assertEquals("DOMAIN\\user", parsed.username());
		assertArrayEquals("secret".toCharArray(), parsed.password());
		assertEquals("", arguments[5]);
		assertEquals(WinRMHttpProtocolEnum.HTTP, parsed.protocol());
		assertEquals(5985, parsed.port());
		assertEquals(CliArguments.DEFAULT_TIMEOUT, parsed.timeout());
		assertEquals(List.of(AuthenticationEnum.NTLM), parsed.authentications());

		final char[] password = parsed.password();
		parsed.close();
		assertArrayEquals(new char[password.length], password);
	}

	@Test
	void parsesHttpsKerberosAndExplicitValues() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{
						"--hostname=host.example.net",
						"--username=user@example.net",
						"--password=secret",
						"--https",
						"--https-permissive",
						"--kerberos",
						"--kerberos-kdc=kdc.example.net",
						"--kerberos-realm=CORP.EXAMPLE.NET",
						"--port=1234",
						"--timeout=9876",
						"wql",
						"SELECT",
						"Name",
						"FROM",
						"Win32_Service"
				}
			)) {
			assertEquals(WinRMHttpProtocolEnum.HTTPS, parsed.protocol());
			assertEquals(1234, parsed.port());
			assertEquals(9876, parsed.timeout());
			assertTrue(parsed.permissiveHttps());
			assertEquals(List.of(AuthenticationEnum.KERBEROS), parsed.authentications());
			assertEquals("kdc.example.net", parsed.kerberosKdc());
			assertEquals("CORP.EXAMPLE.NET", parsed.kerberosRealm());
			assertFalse(parsed.kerberosRealmInferred());
			assertEquals("SELECT Name FROM Win32_Service", parsed.input());
		}
	}

	@Test
	void parsesBasicOverHttp() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{
						"--hostname=host.example.net",
						"--username=user@example.net",
						"--password=secret",
						"--basic",
						"command",
						"whoami"
				}
			)) {
			// Basic is not HTTPS-only (unlike Kerberos): a plain HTTP endpoint is valid.
			assertEquals(WinRMHttpProtocolEnum.HTTP, parsed.protocol());
			assertEquals(List.of(AuthenticationEnum.BASIC), parsed.authentications());
		}
	}

	@Test
	void infersKerberosRealmFromKdcDnsSuffix() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{
						"-h",
						"host",
						"-u",
						"user",
						"-p",
						"secret",
						"--https",
						"--kerberos",
						"--kerberos-kdc",
						"camus.internal.sentrysoftware.net",
						"command",
						"whoami"
				}
			)) {
			assertEquals("camus.internal.sentrysoftware.net", parsed.kerberosKdc());
			assertEquals("INTERNAL.SENTRYSOFTWARE.NET", parsed.kerberosRealm());
			assertTrue(parsed.kerberosRealmInferred());
		}
	}

	@Test
	void httpsUsesItsDefaultPort() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "-p", "secret", "--https", "command", "hostname" }
			)) {
			assertEquals(5986, parsed.port());
		}
	}

	@Test
	void acceptsOmittedPasswordForInteractivePrompting() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "command", "whoami" }
			)) {
			assertNull(parsed.password());
		}
	}

	@Test
	void parsesTheRemoteWorkingDirectory() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "-p", "secret", "-d", "C:\\build", "command", "build.cmd" }
			)) {
			assertEquals("C:\\build", parsed.directory());
		}
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "-p", "secret", "--directory=C:\\build", "shell" }
			)) {
			assertEquals("C:\\build", parsed.directory());
		}
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "-p", "secret", "command", "whoami" }
			)) {
			assertNull(parsed.directory());
		}
	}

	@Test
	void parsesRepeatableEnvironmentVariables() throws Exception {
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{
						"-h",
						"host",
						"-u",
						"user",
						"-p",
						"secret",
						"--env",
						"BUILD_NUMBER=42",
						"--env=CONFIG=release",
						"--env",
						"OPTIONS=a=b",
						"command",
						"build.cmd"
				}
			)) {
			// Insertion order preserved; the value is split on the FIRST '=' only.
			assertEquals(List.of("BUILD_NUMBER", "CONFIG", "OPTIONS"), List.copyOf(parsed.environment().keySet()));
			assertEquals("42", parsed.environment().get("BUILD_NUMBER"));
			assertEquals("release", parsed.environment().get("CONFIG"));
			assertEquals("a=b", parsed.environment().get("OPTIONS"));
		}
		// A repeated name replaces the value; an empty value is allowed (winrs-style).
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "-p", "secret", "--env", "A=1", "--env", "A=2", "--env", "B=", "shell" }
			)) {
			assertEquals("2", parsed.environment().get("A"));
			assertEquals("", parsed.environment().get("B"));
		}
		// Without the option, the environment is empty.
		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{ "-h", "host", "-u", "user", "-p", "secret", "command", "whoami" }
			)) {
			assertTrue(parsed.environment().isEmpty());
		}
	}

	@Test
	void acceptsEveryCommandAlias() throws Exception {
		for (final String alias : List.of("command", "cmd", "exec", "run")) {
			try (
				CliArguments parsed = CliArguments.parse(
					new String[]
					{ "-h", "host", "-u", "user", "-p", "secret", alias, "ipconfig", "/all" }
				)) {
				assertEquals(CliArguments.Operation.COMMAND, parsed.operation());
				assertEquals("ipconfig /all", parsed.input());
			}
		}
	}

	@Test
	void parsesTheFileSubcommandsAndTheirOptions() throws Exception {
		final String[] base = { "-h", "host", "-u", "user", "-p", "secret" };
		try (
			CliArguments parsed = CliArguments.parse(
				concat(
					base,
					"ls",
					"C:\\inetpub\\logs",
					"--glob",
					"*.log",
					"--recursive",
					"--depth=3",
					"--files-only",
					"--modified-after",
					"2026-01-31T12:00:00+02:00",
					"--min-size",
					"1024",
					"--json"
				)
			)) {
			assertEquals(CliArguments.Operation.LS, parsed.operation());
			assertEquals("C:\\inetpub\\logs", parsed.input());
			assertEquals("*.log", parsed.glob());
			assertTrue(parsed.recursive());
			assertEquals(3, parsed.depth());
			assertTrue(parsed.filesOnly());
			assertFalse(parsed.directoriesOnly());
			assertEquals(Instant.parse("2026-01-31T10:00:00Z"), parsed.modifiedAfter());
			assertEquals(1024, parsed.minSize());
			assertTrue(parsed.json());
		}
		// Options may come before the path; a date alone stands for midnight UTC.
		try (
			CliArguments parsed = CliArguments.parse(
				concat(base, "ls", "--directories-only", "--modified-after=2026-01-31", "D:\\data")
			)) {
			assertEquals("D:\\data", parsed.input());
			assertTrue(parsed.directoriesOnly());
			assertEquals(Instant.parse("2026-01-31T00:00:00Z"), parsed.modifiedAfter());
			assertFalse(parsed.recursive());
			assertEquals(0, parsed.depth());
			assertNull(parsed.glob());
			assertFalse(parsed.json());
		}
		try (CliArguments parsed = CliArguments.parse(concat(base, "stat", "C:\\x.log", "--json"))) {
			assertEquals(CliArguments.Operation.STAT, parsed.operation());
			assertTrue(parsed.json());
		}
		// A negative offset is the option's value, not an option.
		try (
			CliArguments parsed = CliArguments.parse(
				concat(base, "cat", "D:\\logs\\huge.log", "--offset", "-8192", "--length", "65536", "--charset", "windows-1252")
			)) {
			assertEquals(CliArguments.Operation.CAT, parsed.operation());
			assertEquals(-8192, parsed.offset());
			assertEquals(65_536, parsed.length());
			assertEquals(Charset.forName("windows-1252"), parsed.charset());
		}
		// --length alone reads from the start, like head -c; nothing set reads the whole file as bytes.
		try (CliArguments parsed = CliArguments.parse(concat(base, "cat", "C:\\x.bin", "--length=10"))) {
			assertEquals(0, parsed.offset());
			assertEquals(10, parsed.length());
		}
		try (CliArguments parsed = CliArguments.parse(concat(base, "cat", "C:\\x.bin"))) {
			assertEquals(-1, parsed.length());
			assertNull(parsed.charset());
		}
		try (
			CliArguments parsed = CliArguments
				.parse(concat(base, "get", "C:\\Windows\\Temp\\collect.log", "logs/collect.log"))) {
			assertEquals(CliArguments.Operation.GET, parsed.operation());
			assertEquals("C:\\Windows\\Temp\\collect.log", parsed.input());
			assertEquals(Path.of("logs/collect.log"), parsed.localFile());
		}
		try (CliArguments parsed = CliArguments.parse(concat(base, "get", "C:\\Windows\\Temp\\collect.log"))) {
			assertNull(parsed.localFile());
		}
		// Remote paths are passed through untouched: spaces, UNC, a trailing backslash.
		try (CliArguments parsed = CliArguments.parse(concat(base, "ls", "\\\\server\\share\\my logs\\"))) {
			assertEquals("\\\\server\\share\\my logs\\", parsed.input());
		}
	}

	@Test
	void stripsExactlyOneFinalPasswordFileLineEnding() throws Exception {
		final Path passwordFile = temporaryDirectory.resolve("password.txt");
		Files.write(passwordFile, "line1\nline2\r\n".getBytes(StandardCharsets.UTF_8));

		try (
			CliArguments parsed = CliArguments.parse(
				new String[]
				{
						"-h",
						"host",
						"-u",
						"user",
						"-pf",
						passwordFile.toString(),
						"wql",
						"SELECT Name FROM Win32_Service"
				}
			)) {
			assertArrayEquals("line1\nline2".toCharArray(), parsed.password());
		}
	}

	@Test
	void rejectsInvalidArguments() {
		final String[] base = { "-h", "host", "-u", "user", "-p", "secret" };
		final Object[][] invalidArguments = {
				{
						"--password and --password-file are mutually exclusive",
						concat(base, "-pf", "other.txt", "wql", "SELECT Name FROM Win32_Service")
				},
				{
						"--ntlm and --kerberos are mutually exclusive",
						concat(base, "--https", "--ntlm", "--kerberos", "command", "whoami")
				},
				{
						"--ntlm and --basic are mutually exclusive",
						concat(base, "--ntlm", "--basic", "command", "whoami")
				},
				{
						"--kerberos and --basic are mutually exclusive",
						concat(base, "--https", "--kerberos", "--basic", "command", "whoami")
				},
				{ "--kerberos requires --https", concat(base, "--kerberos", "command", "whoami") },
				{
						"--kerberos-kdc and --kerberos-realm require --kerberos",
						concat(base, "--kerberos-kdc", "kdc.example.net", "command", "whoami")
				},
				{
						"--kerberos-realm requires --kerberos-kdc",
						concat(
							base,
							"--https",
							"--kerberos",
							"--kerberos-realm",
							"EXAMPLE.NET",
							"command",
							"whoami"
						)
				},
				{
						"cannot infer a realm from --kerberos-kdc; specify --kerberos-realm",
						concat(
							base,
							"--https",
							"--kerberos",
							"--kerberos-kdc",
							"localhost",
							"command",
							"whoami"
						)
				},
				{
						"--https-permissive requires --https",
						concat(base, "--https-permissive", "command", "whoami")
				},
				{ "-d requires a value", concat(base, "-d") },
				{ "--directory requires a value", concat(base, "--directory=", "command", "whoami") },
				{ "--directory requires a value", concat(base, "--directory", "  ", "command", "whoami") },
				{
						"--directory requires the command or shell subcommand",
						concat(base, "-d", "C:\\build", "wql", "SELECT Name FROM Win32_Service")
				},
				{ "--env requires a value", concat(base, "--env") },
				{ "--env requires NAME=VALUE", concat(base, "--env", "NOEQUALS", "command", "whoami") },
				{ "--env requires NAME=VALUE", concat(base, "--env", "=value", "command", "whoami") },
				{ "--env requires NAME=VALUE", concat(base, "--env", " =value", "command", "whoami") },
				{
						"--env requires the command or shell subcommand",
						concat(base, "--env", "A=b", "wql", "SELECT Name FROM Win32_Service")
				},
				{ "-P must be between 1 and 65535", concat(base, "-P", "65536", "command", "whoami") },
				{ "-t must be greater than zero", concat(base, "-t", "0", "command", "whoami") },
				{ "missing subcommand (wql, command, shell, ls, stat, cat, or get)", base },
				{ "ls requires one remote path", concat(base, "ls") },
				{ "ls requires one remote path", concat(base, "ls", "C:\\a", "C:\\b") },
				{ "stat requires one remote path", concat(base, "stat", " ") },
				{ "cat requires one remote path", concat(base, "cat", "--offset", "1") },
				{ "get requires a remote path and an optional local path", concat(base, "get", "C:\\a", "a", "b") },
				{ "get: invalid local path", concat(base, "get", "C:\\a", "a" + (char) 0 + "b") },
				{ "--depth must be greater than zero", concat(base, "ls", "C:\\a", "--depth", "0") },
				{ "--depth must be a number", concat(base, "ls", "C:\\a", "--depth", "three") },
				{
						"--modified-after must be an ISO-8601 date or date-time with an offset, e.g. 2026-01-31 or 2026-01-31T12:00:00Z",
						concat(base, "ls", "C:\\a", "--modified-after", "2026-01-31T12:00:00")
				},
				{
						"--modified-after must be an ISO-8601 date or date-time with an offset, e.g. 2026-01-31 or 2026-01-31T12:00:00Z",
						concat(base, "ls", "C:\\a", "--modified-after", "yesterday")
				},
				{ "--min-size must be a number", concat(base, "ls", "C:\\a", "--min-size", "1M") },
				{
						"--files-only and --directories-only are mutually exclusive",
						concat(base, "ls", "C:\\a", "--files-only", "--directories-only")
				},
				{ "--glob requires a value", concat(base, "ls", "C:\\a", "--glob") },
				{ "--glob requires a value", concat(base, "ls", "C:\\a", "--glob", " ") },
				{ "--glob requires the ls subcommand", concat(base, "cat", "C:\\a", "--glob", "*.log") },
				{ "--json requires the ls or stat subcommand", concat(base, "cat", "C:\\a", "--json") },
				{ "--offset requires the cat subcommand", concat(base, "get", "C:\\a", "--offset", "1") },
				{ "--offset must be a number", concat(base, "cat", "C:\\a", "--offset", "end") },
				{ "--length must be greater than zero", concat(base, "cat", "C:\\a", "--length", "0") },
				{
						"--charset must name a charset known to Java, e.g. UTF-8 or windows-1252",
						concat(base, "cat", "C:\\a", "--charset", "klingon")
				},
				{ "unknown option '-r'", concat(base, "ls", "C:\\a", "-r") },
				{ "--directory requires the command or shell subcommand", concat(base, "-d", "C:\\build", "ls", "C:\\a") },
				{ "--env requires the command or shell subcommand", concat(base, "--env", "A=b", "cat", "C:\\a") },
				{ "wql requires a query", concat(base, "wql", "") },
				{
						"missing required option --hostname",
						new String[]
						{ "-u", "user", "-p", "secret", "command", "whoami" }
				},
		};
		for (final Object[] invalid : invalidArguments) {
			final String expectedMessage = (String) invalid[0];
			final String[] arguments = (String[]) invalid[1];
			final CliUsageException exception = assertThrows(
				CliUsageException.class,
				() -> CliArguments.parse(arguments)
			);
			assertEquals(expectedMessage, exception.getMessage());
		}
	}

	private static String[] concat(final String[] prefix, final String... suffix) {
		final String[] result = new String[prefix.length + suffix.length];
		System.arraycopy(prefix, 0, result, 0, prefix.length);
		System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
		return result;
	}
}
