package org.metricshub.winrm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
				{ "-P must be between 1 and 65535", concat(base, "-P", "65536", "command", "whoami") },
				{ "-t must be greater than zero", concat(base, "-t", "0", "command", "whoami") },
				{ "missing subcommand (wql, command, or shell)", base },
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
