package org.metricshub.winrm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.service.WinRMExecutorFactory;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.wql.WinRMWqlExecutor;

/**
 * Differential harness (issue #107): runs the same operations through the legacy CXF backend and
 * the light backend against a REAL WinRM host and asserts the results match — the go/no-go gate
 * for removing CXF. Disabled unless {@code winrm.diff.host} is set, so it never runs in CI.
 *
 * <p>One-command run against a lab host:
 *
 * <pre>
 * mvn test -Dtest=BackendDifferentialTest -Dmaven.javadoc.skip=true \
 *   -Dwinrm.diff.host=myhost.example.com \
 *   -Dwinrm.diff.protocol=https \
 *   -Dwinrm.diff.username='MYDOMAIN\myuser' \
 *   -Dwinrm.diff.password-file=/path/to/password.txt
 * </pre>
 *
 * <p>Optional properties: {@code winrm.diff.port} (defaults to 5985/5986 by protocol),
 * {@code winrm.diff.password} (inline, instead of the file), {@code winrm.diff.namespace},
 * {@code winrm.diff.wql}, {@code winrm.diff.command}, {@code winrm.diff.badcreds=true} to also
 * exercise the wrong-password parity check (off by default — it triggers failed logons on the
 * host), and {@code winrm.diff.tls.insecure=false} to validate TLS on the light backend instead
 * of matching the CXF backend's trust-all behavior.
 */
@EnabledIfSystemProperty(named = "winrm.diff.host", matches = ".+")
class BackendDifferentialTest {

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
		host = System.getProperty("winrm.diff.host");
		protocol =
			"https".equalsIgnoreCase(System.getProperty("winrm.diff.protocol", "http"))
				? WinRMHttpProtocolEnum.HTTPS
				: WinRMHttpProtocolEnum.HTTP;
		final String portProperty = System.getProperty("winrm.diff.port");
		port = portProperty == null ? null : Integer.valueOf(portProperty);
		username = System.getProperty("winrm.diff.username");
		namespace = System.getProperty("winrm.diff.namespace");
		wql = System.getProperty("winrm.diff.wql", "SELECT Caption FROM Win32_OperatingSystem");
		command = System.getProperty("winrm.diff.command", "echo winrm-diff");

		final String inline = System.getProperty("winrm.diff.password");
		if (inline != null) {
			password = inline.toCharArray();
		} else {
			final String file = System.getProperty("winrm.diff.password-file");
			if (file == null) {
				throw new IllegalArgumentException("Set winrm.diff.password or winrm.diff.password-file");
			}
			password = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8).trim().toCharArray();
		}

		// The CXF backend trusts every certificate; give the light backend the same behavior by
		// default so the differential compares the protocol, not the trust policy.
		if (!"false".equalsIgnoreCase(System.getProperty("winrm.diff.tls.insecure"))) {
			System.setProperty("org.metricshub.winrm.tls.insecure", "true");
		}
	}

	@AfterEach
	void clearBackend() {
		System.clearProperty(WinRMExecutorFactory.BACKEND_PROPERTY);
	}

	private static <T> T withBackend(final String backend, final Operation<T> operation) throws Exception {
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, backend);
		try {
			return operation.run();
		} finally {
			System.clearProperty(WinRMExecutorFactory.BACKEND_PROPERTY);
		}
	}

	private interface Operation<T> {
		T run() throws Exception;
	}

	private static WinRMWqlExecutor wql(final String query, final char[] pwd) throws Exception {
		return WinRMWqlExecutor.executeWql(
			protocol,
			host,
			port,
			username,
			pwd,
			namespace,
			query,
			30_000L,
			null,
			List.of(AuthenticationEnum.NTLM)
		);
	}

	@Test
	void wqlResultsMatch() throws Exception {
		final WinRMWqlExecutor cxf = withBackend("cxf", () -> wql(wql, password));
		final WinRMWqlExecutor light = withBackend("light", () -> wql(wql, password));

		assertEquals(cxf.getHeaders(), light.getHeaders());
		assertEquals(cxf.getRows(), light.getRows());
	}

	@Test
	void commandResultsMatch() throws Exception {
		final WindowsRemoteCommandResult cxf = withBackend(
			"cxf",
			() ->
				org.metricshub.winrm.command.WinRMCommandExecutor.execute(
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
				)
		);
		final WindowsRemoteCommandResult light = withBackend(
			"light",
			() ->
				org.metricshub.winrm.command.WinRMCommandExecutor.execute(
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
				)
		);

		assertEquals(cxf.getStatusCode(), light.getStatusCode());
		assertEquals(cxf.getStdout(), light.getStdout());
		assertEquals(cxf.getStderr(), light.getStderr());
	}

	@Test
	void serverFaultTextMatches() throws Exception {
		// Both backends must surface the same server fault text for a bad class (the light backend
		// adds an informative prefix and the WSManFault detail on top — a contains()-compatible superset).
		final String badClass = "SELECT Name FROM No_Such_Class_Diff_42";
		final WinRMException cxf = assertThrows(
			WinRMException.class,
			() -> withBackend("cxf", () -> wql(badClass, password))
		);
		final WinRMException light = assertThrows(
			WinRMException.class,
			() -> withBackend("light", () -> wql(badClass, password))
		);
		assertTrue(
			light.getMessage().contains(cxf.getMessage().trim()),
			() ->
				"light message does not contain the CXF fault text\nCXF:   " +
				cxf.getMessage() +
				"\nlight: " +
				light.getMessage()
		);
	}

	@Test
	@EnabledIfSystemProperty(named = "winrm.diff.badcreds", matches = "true")
	void authenticationErrorMessagesAreIdentical() throws Exception {
		final char[] wrong = "definitely-wrong-password".toCharArray();
		final WinRMException cxf = assertThrows(WinRMException.class, () -> withBackend("cxf", () -> wql(wql, wrong)));
		final WinRMException light = assertThrows(WinRMException.class, () -> withBackend("light", () -> wql(wql, wrong)));
		assertEquals(cxf.getMessage(), light.getMessage());
	}
}
