package org.metricshub.winrm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
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
		return builder().build();
	}

	private static WinRMClient.Builder builder() {
		final WinRMClient.Builder builder = WinRMClient.builder(host).credentials(username, password)
			.timeout(Duration.ofSeconds(60));
		if (protocol == WinRMHttpProtocolEnum.HTTPS) {
			builder.https();
		}
		if (port != null) {
			builder.port(port);
		}
		return builder;
	}

	/**
	 * Kerberos credential delegation, the second hop: with {@code -Dwinrm.live.delegation.unc} set
	 * to a UNC path the account can read on a third machine (over HTTPS, with a {@code krb5.conf}
	 * saying {@code forwardable = true}), the host reaches it with delegation and is denied without.
	 */
	@Test
	@EnabledIfSystemProperty(named = "winrm.live.delegation.unc", matches = ".+")
	void kerberosDelegationReachesTheSecondHop() {
		final String unc = System.getProperty("winrm.live.delegation.unc");
		try (WinRMClient client = builder().authentication(AuthScheme.KERBEROS).allowDelegation().build()) {
			final CommandResult result = client.command("dir " + unc).execute();
			assertEquals(0, result.exitCode(), result::stderr);
		}
		try (WinRMClient client = builder().authentication(AuthScheme.KERBEROS).build()) {
			assertNotEquals(0, client.command("dir " + unc).execute().exitCode());
		}
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

	@Test
	void remoteDirectoryListing() throws Exception {
		final String base = "C:\\Windows\\Temp\\winrm-java-live-list";
		final String nonAscii = base + "\\donn\u00e9es-\u6f22\u5b57.txt";
		// 3 x 100 characters below the base: over 330 characters in all.
		final String longDir = base + "\\long\\" + "a".repeat(100) + "\\" + "b".repeat(100);
		final String longFile = longDir + "\\" + "c".repeat(100) + ".txt";

		try (WinRMClient client = client()) {
			// .NET 4.6.2+ accepts \\?\ paths (long paths); PowerShell 2.0 on .NET 2.0 does not.
			final boolean longPaths = client
				.powerShell("try{[void][IO.Path]::GetFullPath('\\\\?\\C:\\x');'yes'}catch{'no'}")
				.execute()
				.stdout()
				.contains("yes");
			client
				.powerShell(
					"$b='" + base + "';" +
						"foreach($d in 'sub\\deep','secret'){[void][IO.Directory]::CreateDirectory(\"$b\\$d\")};" +
						"foreach($f in 'a.log','sub\\b.log','sub\\deep\\c.log','secret\\hidden.txt'){" +
						"[IO.File]::WriteAllText(\"$b\\$f\",'x')};" +
						"[IO.File]::WriteAllText('" + nonAscii + "','x');" +
						"cmd /c mklink /J \"$b\\loop\" \"$b\" | Out-Null;" +
						"icacls \"$b\\secret\" /deny '*S-1-1-0:(RD)' | Out-Null;" +
						(longPaths
							? "[void][IO.Directory]::CreateDirectory('\\\\?\\" + longDir + "');" +
								"[IO.File]::WriteAllText('\\\\?\\" + longFile + "','0123456789')"
							: "")
				)
				.execute();
			try {
				// A plain listing of C:\Windows\Temp.
				assertTrue(
					client
						.file("C:\\Windows\\Temp")
						.list()
						.directoriesOnly()
						.execute()
						.entries()
						.stream()
						.anyMatch(e -> e.path().equalsIgnoreCase(base))
				);

				// Depth-limited.
				final List<String> depth2 = paths(client.file(base).list().maxDepth(2).execute().entries());
				assertTrue(depth2.contains(base + "\\sub\\b.log"), depth2::toString);
				assertTrue(depth2.contains(base + "\\sub\\deep"), depth2::toString);
				assertFalse(depth2.contains(base + "\\sub\\deep\\c.log"), depth2::toString);

				// The whole tree: the junction loop terminates, the denied directory is reported.
				final long start = System.nanoTime();
				final RemoteFileList all = client.file(base).list().recursive().execute();
				System.out.printf("Listed %s in %d ms%n", all, (System.nanoTime() - start) / 1_000_000L);
				final List<String> paths = paths(all.entries());
				assertTrue(paths.contains(nonAscii), paths::toString);
				assertTrue(paths.contains(base + "\\secret"), paths::toString);
				// An administrator's WinRM session has SeBackupPrivilege enabled, and directory
				// enumeration uses backup semantics: the deny ACE then does not apply.
				if (paths.contains(base + "\\secret\\hidden.txt")) {
					assertTrue(all.inaccessible().isEmpty(), all.inaccessible()::toString);
					System.out.println("The deny ACE was bypassed (backup privilege): inaccessible directories not tested");
				} else {
					assertEquals(List.of(base + "\\secret"), all.inaccessible());
				}
				final RemoteFileInfo loop = all
					.entries()
					.stream()
					.filter(e -> e.path().equals(base + "\\loop"))
					.findFirst()
					.orElseThrow();
				assertTrue(loop.isReparsePoint() && loop.isDirectory());
				assertTrue(paths.stream().noneMatch(p -> p.startsWith(base + "\\loop\\")), paths::toString);

				// Streamed, with filters.
				try (Stream<RemoteFileInfo> logs = client.file(base).list().recursive().glob("*.LOG").filesOnly().stream()) {
					assertEquals(
						List.of("a.log", "b.log", "c.log"),
						logs.map(RemoteFileInfo::name).sorted().collect(Collectors.toList())
					);
				}

				// Properties.
				assertEquals(1, client.file(base + "\\a.log").info().orElseThrow().size());
				assertTrue(client.file(nonAscii).exists());
				assertFalse(client.file(base + "\\missing.log").exists());

				if (longPaths) {
					assertEquals(10, client.file(longFile).info().orElseThrow().size());
					assertTrue(
						paths(client.file(base + "\\long").list().recursive().filesOnly().execute().entries()).contains(longFile)
					);
				} else {
					System.out.println("Long paths not supported by this host's .NET: skipped");
				}
			} finally {
				client
					.powerShell(
						"$b='" + base + "';icacls \"$b\\secret\" /remove:d '*S-1-1-0' | Out-Null;" +
							"cmd /c rmdir \"$b\\loop\";cmd /c rmdir /s /q \"\\\\?\\$b\""
					)
					.execute();
			}
		}
	}

	private static List<String> paths(final List<RemoteFileInfo> entries) {
		return entries.stream().map(RemoteFileInfo::path).collect(Collectors.toList());
	}

	@Test
	void downloadRoundTripsAnUpload(@TempDir final Path directory) throws Exception {
		// Every byte value, under a non-ASCII name. Small: an upload costs one command leg per ~8 KB.
		final byte[] content = new byte[1024];
		for (int i = 0; i < content.length; i++) {
			content[i] = (byte) i;
		}
		final String name = "winrm-java-live-round-trip-donn\u00e9es-\u6f22\u5b57.bin";
		final Path local = Files.write(directory.resolve(name), content);
		final String remote = "C:\\Windows\\Temp\\" + name;
		final Path downloads = Files.createDirectory(directory.resolve("downloads"));

		try (WinRMClient client = client()) {
			client.uploadFile(local, remote);
			try {
				// Into a directory: the file keeps its remote name.
				final long start = System.nanoTime();
				assertEquals(content.length, client.downloadFile(remote, downloads));
				final long downloaded = System.nanoTime();
				assertArrayEquals(content, Files.readAllBytes(downloads.resolve(name)));
				// Identical: nothing transferred.
				assertEquals(0, client.downloadFile(remote, downloads.resolve(name)));
				System.out.printf(
					"Downloaded 1 KiB in %d ms, skipped the identical copy in %d ms%n",
					(downloaded - start) / 1_000_000L,
					(System.nanoTime() - downloaded) / 1_000_000L
				);
			} finally {
				client.powerShell("Remove-Item -LiteralPath '" + remote + "' -ErrorAction SilentlyContinue").execute();
			}
		}
	}

	@Test
	void downloadIsDigestVerifiedWithBoundedMemory(@TempDir final Path directory) throws Exception {
		// Larger than the heap when run with a small one, e.g. -DargLine=-Xmx32m
		// -Dwinrm.live.download.mib=64. Created on the host by one command.
		final int mebibytes = Integer.getInteger("winrm.live.download.mib", 20);
		final String remote = "C:\\Windows\\Temp\\winrm-java-live-download.bin";
		try (WinRMClient client = client()) {
			client
				.powerShell(
					"$r=New-Object Random 42;$b=New-Object byte[] 1048576;$f=[IO.File]::Create('" + remote + "');" +
						"for($i=0;$i -lt " + mebibytes + ";$i++){$r.NextBytes($b);$f.Write($b,0,$b.Length)};$f.Close()"
				)
				.execute();
			try {
				final Path local = directory.resolve("download.bin");
				final long start = System.nanoTime();
				assertEquals(
					mebibytes * 1048576L,
					client.file(remote).timeout(Duration.ofMinutes(15)).downloadTo(local)
				);
				final long millis = (System.nanoTime() - start) / 1_000_000L;
				System.out.printf(
					"Downloaded %d MiB in %d ms (%.2f MB/s) with a %d MiB heap%n",
					mebibytes,
					millis,
					mebibytes * 1048.576 / millis,
					Runtime.getRuntime().maxMemory() >> 20
				);
				final MessageDigest digest = MessageDigest.getInstance("SHA-256");
				try (InputStream in = new DigestInputStream(Files.newInputStream(local), digest)) {
					in.transferTo(OutputStream.nullOutputStream());
				}
				final StringBuilder sha256 = new StringBuilder();
				for (final byte b : digest.digest()) {
					sha256.append(String.format("%02x", b));
				}
				assertEquals(client.file(remote).digest("SHA256"), sha256.toString());
			} finally {
				client.command("del /q \"" + remote + "\"").execute();
			}
		}
	}
}
