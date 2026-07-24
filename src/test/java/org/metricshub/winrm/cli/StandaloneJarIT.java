package org.metricshub.winrm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class StandaloneJarIT {

	private static final long PROCESS_TIMEOUT_SECONDS = 30L;

	@Test
	void packagedJarHasManifestAndLaunchesInSeparateJvm() throws Exception {
		final Path regularJar = Path.of(System.getProperty("regularJar"));
		final Path standaloneJar = Path.of(System.getProperty("standaloneJar"));
		final String projectVersion = System.getProperty("projectVersion");

		assertTrue(Files.isRegularFile(regularJar), () -> "Missing regular JAR: " + regularJar);
		assertTrue(Files.isRegularFile(standaloneJar), () -> "Missing standalone JAR: " + standaloneJar);
		try (JarFile jar = new JarFile(standaloneJar.toFile())) {
			final Attributes attributes = jar.getManifest().getMainAttributes();
			assertEquals("org.metricshub.winrm.cli.WinRmCli", attributes.getValue(Attributes.Name.MAIN_CLASS));
			assertEquals(projectVersion, attributes.getValue("Implementation-Version"));
			assertNotNull(jar.getEntry("com/hierynomus/smbj/SMBClient.class"));
		}

		final ProcessResult help = launch(standaloneJar, "--help");
		assertEquals(0, help.exitCode);
		assertTrue(help.stdout.contains("Usage:"));
		assertEquals("", help.stderr);

		final ProcessResult version = launch(standaloneJar, "--version");
		assertEquals(0, version.exitCode);
		assertEquals("winrm-java " + projectVersion + System.lineSeparator(), version.stdout);
		assertEquals("", version.stderr);
	}

	private static ProcessResult launch(final Path jar, final String argument) throws Exception {
		final String javaExecutable = Path
			.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
			.toString();
		final Process process = new ProcessBuilder(javaExecutable, "-jar", jar.toString(), argument).start();
		final boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IOException("CLI process did not finish");
		}
		return new ProcessResult(
			process.exitValue(),
			new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
			new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
		);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static final class ProcessResult {

		private final int exitCode;
		private final String stdout;
		private final String stderr;

		private ProcessResult(final int exitCode, final String stdout, final String stderr) {
			this.exitCode = exitCode;
			this.stdout = stdout;
			this.stderr = stderr;
		}
	}
}
