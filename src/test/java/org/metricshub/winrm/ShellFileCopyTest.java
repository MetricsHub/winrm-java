package org.metricshub.winrm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.exceptions.WindowsRemoteException;

class ShellFileCopyTest {

	private static final long TIMEOUT = 30 * 1000L;
	private static final String WINDOWS_DIRECTORY = "C:\\Windows";
	private static final Pattern ECHO_PAYLOAD = Pattern.compile("echo ([A-Za-z0-9+/=]+)");

	@TempDir
	Path tempDir;

	private static final WindowsRemoteCommandResult SUCCESS = new WindowsRemoteCommandResult("", "", 0.1f, 0);
	private static final WindowsRemoteCommandResult FAILURE = new WindowsRemoteCommandResult(
		"",
		"CertUtil: -hashfile command FAILED: 0x80070002",
		0.1f,
		1
	);

	private static String expectedRemoteDirectory() {
		return WINDOWS_DIRECTORY + "\\Temp\\" + WindowsTempShare.buildShareName();
	}

	private static String sha256Hex(final byte[] content) throws Exception {
		final StringBuilder hex = new StringBuilder();
		for (final byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private static String sha1Hex(final byte[] content) throws Exception {
		final StringBuilder hex = new StringBuilder();
		for (final byte b : MessageDigest.getInstance("SHA-1").digest(content)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private static WindowsRemoteCommandResult hashOutput(final String algorithm, final String hex) {
		return new WindowsRemoteCommandResult(
			String.format(
				"%s hash of file C:\\whatever:\r\n%s\r\nCertUtil: -hashfile command completed successfully.\r\n",
				algorithm,
				hex
			),
			"",
			0.1f,
			0
		);
	}

	private static ScriptedWindowsRemoteExecutor executorWithTempDirectory() {
		return new ScriptedWindowsRemoteExecutor()
			.expectWql("WindowsDirectory", List.of(Map.of("WindowsDirectory", WINDOWS_DIRECTORY)))
			.expectCommand("MKDIR", SUCCESS);
	}

	/** Reassemble the file bytes from the base64 payloads echoed by the recorded upload legs. */
	private static byte[] echoedContent(final List<String> commands) {
		final String base64 = commands
			.stream()
			.filter(command -> command.contains(" echo "))
			.flatMap(command -> {
				final Matcher matcher = ECHO_PAYLOAD.matcher(command);
				final StringBuilder payload = new StringBuilder();
				while (matcher.find()) {
					payload.append(matcher.group(1));
				}
				return payload.length() > 0 ? java.util.stream.Stream.of(payload.toString()) : java.util.stream.Stream.empty();
			})
			.collect(Collectors.joining());

		return Base64.getDecoder().decode(base64);
	}

	@Test
	void uploadsNewFileAndRewritesCommand() throws Exception {
		final byte[] content = "Résultat: héllo wörld\r\nsecond line\n".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("My Script.vbs");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" SHA256", FAILURE, hashOutput("SHA256", sha256Hex(content)))
			.expectCommand(" SHA1", FAILURE)
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", SUCCESS);

		final String remoteFile = expectedRemoteDirectory() + "\\"
			+ ShellFileCopy.contentAddressedName("My Script.vbs", content);

		// The command references the local file with a different case: the replacement is case-insensitive
		final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
			executor,
			"CSCRIPT " + localFile.toString().toUpperCase(),
			List.of(localFile.toString()),
			TIMEOUT
		);

		assertEquals("CSCRIPT " + remoteFile, updatedCommand);

		// The echoed base64, reassembled, is exactly the file content (multibyte UTF-8 intact)
		assertArrayEquals(content, echoedContent(executor.getExecutedCommands()));

		// The decode leg produces the target file and removes the intermediate base64 file
		final String decodeCommand = executor
			.getExecutedCommands()
			.stream()
			.filter(command -> command.contains("certutil -f -decode"))
			.findFirst()
			.orElseThrow();
		assertTrue(decodeCommand.contains("\"" + remoteFile + "\""));
		assertTrue(decodeCommand.contains("DEL /F /Q"));
		assertTrue(decodeCommand.contains(".b64"));
	}

	@Test
	void skipsUploadWhenRemoteCopyIsIdentical() throws Exception {
		final byte[] content = "some script".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("script.bat");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" SHA256", hashOutput("SHA256", sha256Hex(content)));

		final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
			executor,
			"CMD /C " + localFile,
			List.of(localFile.toString()),
			TIMEOUT
		);

		assertEquals(
			"CMD /C " + expectedRemoteDirectory() + "\\" + ShellFileCopy.contentAddressedName("script.bat", content),
			updatedCommand
		);
		assertFalse(executor.getExecutedCommands().stream().anyMatch(command -> command.contains(" echo ")));
		assertFalse(executor.getExecutedCommands().stream().anyMatch(command -> command.contains("-decode")));
	}

	@Test
	void fallsBackToSha1WhenSha256IsUnavailable() throws Exception {
		final byte[] content = "legacy host".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("legacy.vbs");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" SHA256", FAILURE)
			.expectCommand(" SHA1", FAILURE, hashOutput("SHA1", sha1Hex(content)))
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", SUCCESS);

		final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
			executor,
			localFile.toString(),
			List.of(localFile.toString()),
			TIMEOUT
		);

		assertEquals(
			expectedRemoteDirectory() + "\\" + ShellFileCopy.contentAddressedName("legacy.vbs", content),
			updatedCommand
		);
		assertArrayEquals(content, echoedContent(executor.getExecutedCommands()));
	}

	@Test
	void throwsAndCleansUpOnIntegrityMismatch() throws Exception {
		final byte[] content = "expected content".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("corrupted.txt");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" SHA256", FAILURE, hashOutput("SHA256", sha256Hex("tampered".getBytes(UTF_8))))
			.expectCommand(" SHA1", FAILURE)
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", SUCCESS)
			.expectCommand("DEL /F /Q", SUCCESS);

		final WindowsRemoteException exception = assertThrows(
			WindowsRemoteException.class,
			() -> ShellFileCopy.copyLocalFilesToRemote(executor, localFile.toString(), List.of(localFile.toString()), TIMEOUT)
		);

		assertTrue(exception.getMessage().contains("Integrity check failed"));

		final String remoteFile = expectedRemoteDirectory() + "\\"
			+ ShellFileCopy.contentAddressedName("corrupted.txt", content);
		assertTrue(
			executor
				.getExecutedCommands()
				.stream()
				.anyMatch(command -> command.startsWith("DEL /F /Q") && command.contains(remoteFile))
		);
	}

	@Test
	void transfersEmptyFile() throws Exception {
		final byte[] content = new byte[0];
		final Path localFile = tempDir.resolve("empty.txt");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" SHA256", FAILURE, hashOutput("SHA256", sha256Hex(content)))
			.expectCommand(" SHA1", FAILURE)
			.expectCommand("TYPE NUL", SUCCESS);

		final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
			executor,
			localFile.toString(),
			List.of(localFile.toString()),
			TIMEOUT
		);

		assertEquals(
			expectedRemoteDirectory() + "\\" + ShellFileCopy.contentAddressedName("empty.txt", content),
			updatedCommand
		);
		assertFalse(executor.getExecutedCommands().stream().anyMatch(command -> command.contains(" echo ")));
	}

	@Test
	void returnsCommandUnchangedWithoutFiles() throws Exception {
		// No handler registered: any remote interaction would fail the test
		final ScriptedWindowsRemoteExecutor executor = new ScriptedWindowsRemoteExecutor();

		assertEquals("dir", ShellFileCopy.copyLocalFilesToRemote(executor, "dir", null, TIMEOUT));
		assertEquals("dir", ShellFileCopy.copyLocalFilesToRemote(executor, "dir", List.of(), TIMEOUT));
		assertTrue(executor.getExecutedCommands().isEmpty());
	}

	@Test
	void rejectsFileNamesUnsafeForTheCommandShell() {
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("we%ird.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("quo\"te.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("ctrl\u0001.txt"));

		// Legal Windows file names pass, including cmd metacharacters neutralized by quoting
		ShellFileCopy.checkTransferableFileName("My Script (v2) & more!.vbs");
	}

	@Test
	void buildUploadCommandsChunksBelowTheCommandLineLimit() {
		final String base64File = expectedRemoteDirectory() + "\\big.bin.SEN_X_1_2.b64";
		final byte[] content = new byte[15000];
		for (int i = 0; i < content.length; i++) {
			content[i] = (byte) i;
		}
		final String base64 = Base64.getEncoder().encodeToString(content);

		final List<String> commands = ShellFileCopy.buildUploadCommands(base64, base64File);

		assertTrue(commands.size() > 1, "A 15 kB file must not fit in a single command leg");

		// Only the very first echo truncates; every other one appends
		assertTrue(commands.get(0).startsWith(">\"" + base64File + "\" echo "));
		for (final String command : commands) {
			assertTrue(command.length() <= 8000, () -> "Command leg exceeds the cmd.exe limit: " + command.length());
		}
		for (int i = 1; i < commands.size(); i++) {
			assertTrue(commands.get(i).startsWith(">>\"" + base64File + "\" echo "));
		}

		// Reassembling every echoed payload yields the original base64, in order
		assertArrayEquals(content, echoedContent(commands));
	}

	@Test
	void buildsContentAddressedRemoteNames() {
		// SHA-256("abc") = ba7816bf8f01cfea...: the first 12 hex chars go into the remote name
		final byte[] content = "abc".getBytes(UTF_8);

		assertEquals("script.ba7816bf8f01.vbs", ShellFileCopy.contentAddressedName("script.vbs", content));
		assertEquals("no-extension.ba7816bf8f01", ShellFileCopy.contentAddressedName("no-extension", content));
		assertEquals(".hidden.ba7816bf8f01", ShellFileCopy.contentAddressedName(".hidden", content));

		// Same name, different content: different remote path (no cross-client overwrite)
		assertFalse(
			ShellFileCopy
				.contentAddressedName("script.vbs", content)
				.equals(ShellFileCopy.contentAddressedName("script.vbs", "abd".getBytes(UTF_8)))
		);
	}

	@Test
	void parsesCertutilDigestOutputs() {
		final String modern = "SHA256 hash of file C:\\x:\r\nAB12cd34AB12cd34AB12cd34AB12cd34AB12cd34AB12cd34AB12cd34AB12cd34\r\n"
			+
			"CertUtil: -hashfile command completed successfully.\r\n";
		assertEquals(
			Optional.of("ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34"),
			ShellFileCopy.parseCertutilDigest(modern, "SHA256")
		);

		// Older certutil versions separate every byte with a space
		final String legacy = "SHA1 hash of file C:\\x:\r\nab 12 cd 34 ab 12 cd 34 ab 12 cd 34 ab 12 cd 34 ab 12 cd 34\r\n"
			+
			"CertUtil: -hashfile command completed successfully.\r\n";
		assertEquals(
			Optional.of("ab12cd34ab12cd34ab12cd34ab12cd34ab12cd34"),
			ShellFileCopy.parseCertutilDigest(legacy, "SHA1")
		);

		assertEquals(Optional.empty(), ShellFileCopy.parseCertutilDigest("no digest here", "SHA256"));
		assertEquals(Optional.empty(), ShellFileCopy.parseCertutilDigest(null, "SHA256"));
	}
}
