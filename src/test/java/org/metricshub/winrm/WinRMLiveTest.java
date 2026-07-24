package org.metricshub.winrm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.wql.WinRMWqlExecutor;

/**
 * Live smoke test against a REAL WinRM host — the successor of the pre-2.0.0 CXF-vs-light
 * differential harness (the CXF baseline was removed with the backend; result parity was
 * proven and gated before removal). Disabled unless {@code winrm.live.host} is set, so it
 * never runs in CI.
 * <p>
 * One-command run against a lab host:
 *
 * <pre>
 * mvn test -Dtest=WinRMLiveTest \
 *   -Dwinrm.live.host=myhost.example.com \
 *   -Dwinrm.live.protocol=https \
 *   -Dwinrm.live.username='MYDOMAIN\myuser' \
 *   -Dwinrm.live.password-file=/path/to/password.txt
 * </pre>
 * <p>
 * Optional properties: {@code winrm.live.port} (defaults to 5985/5986 by protocol),
 * {@code winrm.live.password} (inline, instead of the file), {@code winrm.live.namespace},
 * {@code winrm.live.wql}, {@code winrm.live.command}, and {@code winrm.live.tls.insecure=true}
 * to skip TLS validation for hosts with self-signed certificates.
 */
@EnabledIfSystemProperty(named = "winrm.live.host", matches = ".+")
class WinRMLiveTest {

	private static String host;
	private static WinRMHttpProtocolEnum protocol;
	private static Integer port;
	private static String username;
	private static char[] password;
	private static String namespace;
	private static String wql;
	private static String command;

	@BeforeAll
	static void readConfiguration() throws Exception {
		host = System.getProperty("winrm.live.host");
		protocol = "https".equalsIgnoreCase(System.getProperty("winrm.live.protocol", "http"))
			? WinRMHttpProtocolEnum.HTTPS
			: WinRMHttpProtocolEnum.HTTP;
		final String portProperty = System.getProperty("winrm.live.port");
		port = portProperty == null ? null : Integer.valueOf(portProperty);
		username = System.getProperty("winrm.live.username");
		namespace = System.getProperty("winrm.live.namespace");
		wql = System.getProperty("winrm.live.wql", "SELECT Caption FROM Win32_OperatingSystem");
		command = System.getProperty("winrm.live.command", "echo winrm-live");

		final String inline = System.getProperty("winrm.live.password");
		if (inline != null) {
			password = inline.toCharArray();
		} else {
			final String file = System.getProperty("winrm.live.password-file");
			if (file == null) {
				throw new IllegalArgumentException("Set winrm.live.password or winrm.live.password-file");
			}
			password = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8).trim().toCharArray();
		}

		if ("true".equalsIgnoreCase(System.getProperty("winrm.live.tls.insecure"))) {
			System.setProperty("org.metricshub.winrm.tls.insecure", "true");
		}
	}

	@Test
	void wqlReturnsRows() throws Exception {
		final WinRMWqlExecutor result = WinRMWqlExecutor.executeWql(
			protocol,
			host,
			port,
			username,
			password,
			namespace,
			wql,
			30_000L,
			null,
			List.of(AuthenticationEnum.NTLM)
		);
		assertFalse(result.getHeaders().isEmpty(), "WQL result must have headers");
		assertFalse(result.getRows().isEmpty(), "WQL result must have rows");
	}

	@Test
	void commandSucceeds() throws Exception {
		final WindowsRemoteCommandResult result = org.metricshub.winrm.command.WinRMCommandExecutor.execute(
			command,
			protocol,
			host,
			port,
			username,
			password,
			null,
			30_000L,
			null,
			null,
			List.of(AuthenticationEnum.NTLM)
		);
		assertEquals(0, result.getStatusCode(), () -> "stderr: " + result.getStderr());
		assertTrue(result.getStdout().length() > 0, "command must produce stdout");
	}
}
