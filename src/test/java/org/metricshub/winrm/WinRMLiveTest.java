package org.metricshub.winrm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
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

	private static WinRMClient client() {
		final WinRMClient.Builder builder = WinRMClient.builder(host).credentials(username, password)
			.timeout(Duration.ofSeconds(60));
		if (protocol == WinRMHttpProtocolEnum.HTTPS) {
			builder.https();
		}
		if (port != null) {
			builder.port(port);
		}
		return builder.build();
	}

	@Test
	void remoteFileReadsAreByteExact() throws Exception {
		// Every byte value, over several transfer blocks. Created on the host by one command: an
		// uploadFile of that size needs more command legs than old hosts' operation quota allows.
		final byte[] content = new byte[200_000];
		for (int i = 0; i < content.length; i++) {
			content[i] = (byte) (i % 251);
		}
		final String remote = "C:\\Windows\\Temp\\winrm-java-live-read-\u00e9.bin";
		final StringBuilder sha256 = new StringBuilder();
		for (final byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
			sha256.append(String.format("%02x", b));
		}

		try (WinRMClient client = client()) {
			client
				.powerShell(
					"$b=New-Object byte[] " + content.length + ";for($i=0;$i -lt $b.Length;$i++){$b[$i]=$i % 251}" +
						";[IO.File]::WriteAllBytes('" + remote + "',$b)"
				)
				.execute();
			try {
				final long start = System.nanoTime();
				assertArrayEquals(content, client.file(remote).readBytes());
				System.out.printf("Read %d bytes in %d ms%n", content.length, (System.nanoTime() - start) / 1_000_000L);
				assertArrayEquals(
					java.util.Arrays.copyOfRange(content, content.length - 8192, content.length - 8192 + 1024),
					client.file(remote).offset(-8192).length(1024).readBytes()
				);
				assertEquals(sha256.toString(), client.file(remote).digest("SHA256"));
			} finally {
				client.powerShell("Remove-Item -LiteralPath '" + remote + "' -ErrorAction SilentlyContinue").execute();
			}
		}
	}

	@Test
	void remoteFileRangeIsServedBySeekingAndAFileOpenForWritingCanBeRead() throws Exception {
		final String big = "C:\\Windows\\Temp\\winrm-java-live-big.bin";
		final String log = "C:\\Windows\\Temp\\winrm-java-live-open.log";
		try (WinRMClient client = client(); WinRMClient writer = client()) {
			// 1 GiB of zeros: reading it whole through base64 would take many minutes.
			client
				.powerShell(
					"$f=[IO.File]::Create('" + big + "');$f.SetLength(1073741824);$f.Close();" +
						"[IO.File]::WriteAllText('" + log + "','first line')"
				)
				.execute();
			final long start = System.nanoTime();
			assertEquals(4096, client.file(big).offset(-4096).readBytes().length);
			final long seconds = (System.nanoTime() - start) / 1_000_000_000L;
			assertTrue(seconds < 20, "the tail of a 1 GiB file must be read by a seek, took " + seconds + " s");

			// Another session holds the log open for writing (sharing reads only, like most services).
			try (
				RemoteProcess holder = writer
					.powerShell("$f=[IO.File]::Open('" + log + "','Open','Write','Read');Start-Sleep 60")
					.start()) {
				Thread.sleep(3_000);
				assertEquals("first line", client.file(log).readText(StandardCharsets.US_ASCII));
			}
			client.command("del /q \"" + big + "\" \"" + log + "\"").execute();
		}
	}
}
