package org.metricshub.winrm;

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
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", hashOutput("SHA256", sha256Hex(content)))
			.expectCommand("MOVE /Y", hashOutput("SHA256", sha256Hex(content)))
			.expectCommand("certutil -hashfile", FAILURE);

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

		// The decode leg produces the operation-unique staging file and removes the base64 sidecar
		final String decodeCommand = executor
			.getExecutedCommands()
			.stream()
			.filter(command -> command.contains("certutil -f -decode"))
			.findFirst()
			.orElseThrow();
		assertTrue(decodeCommand.contains(remoteFile + "."));
		assertTrue(decodeCommand.contains(".part"));
		assertTrue(decodeCommand.contains("DEL /F /Q"));
		assertTrue(decodeCommand.contains(".b64"));

		// The verified staging file is then published as the content-addressed destination
		assertTrue(
			executor
				.getExecutedCommands()
				.stream()
				.anyMatch(command -> command.contains("MOVE /Y") && command.contains("\"" + remoteFile + "\""))
		);

		// The age-based purge rides the directory-creation leg (exit code stays MKDIR's)
		final String directoryCommand = executor
			.getExecutedCommands()
			.stream()
			.filter(command -> command.contains("MKDIR"))
			.findFirst()
			.orElseThrow();
		assertTrue(directoryCommand.startsWith("forfiles /P "));
		assertTrue(directoryCommand.contains("del /f /q @path"));
		assertTrue(directoryCommand.endsWith("\""));
	}

	@Test
	void skipsUploadWhenRemoteCopyIsIdentical() throws Exception {
		final byte[] content = "some script".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("script.bat");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand("certutil -hashfile", hashOutput("SHA256", sha256Hex(content)));

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
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", hashOutput("SHA1", sha1Hex(content)))
			.expectCommand("MOVE /Y", hashOutput("SHA1", sha1Hex(content)))
			.expectCommand("certutil -hashfile", FAILURE);

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
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", hashOutput("SHA256", sha256Hex("tampered".getBytes(UTF_8))))
			.expectCommand("certutil -hashfile", FAILURE)
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
	void repairsAMismatchedCachedDestination() throws Exception {
		final byte[] content = "good content".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("repair.vbs");
		Files.write(localFile, content);

		// The destination pre-exists with a DIFFERENT digest (e.g. a cached copy corrupted in
		// place): the transfer must replace it, never trust it.
		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", hashOutput("SHA256", sha256Hex(content)))
			.expectCommand("MOVE /Y", hashOutput("SHA256", sha256Hex(content)))
			.expectCommand("certutil -hashfile", hashOutput("SHA256", sha256Hex("corrupted".getBytes(UTF_8))));

		final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
			executor,
			localFile.toString(),
			List.of(localFile.toString()),
			TIMEOUT
		);

		assertEquals(
			expectedRemoteDirectory() + "\\" + ShellFileCopy.contentAddressedName("repair.vbs", content),
			updatedCommand
		);

		// The file was re-uploaded and force-published: a bare MOVE, not guarded by IF EXIST
		assertArrayEquals(content, echoedContent(executor.getExecutedCommands()));
		final String publishCommand = executor
			.getExecutedCommands()
			.stream()
			.filter(command -> command.contains("MOVE /Y"))
			.findFirst()
			.orElseThrow();
		assertTrue(publishCommand.startsWith("MOVE /Y"));
	}

	@Test
	void failsWhenThePublishedDestinationDigestMismatches() throws Exception {
		final byte[] content = "expected content".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("unlucky.txt");
		Files.write(localFile, content);

		// Staging verifies fine, but the published destination reports a different digest:
		// the operation must fail rather than let the caller execute unproven bytes.
		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand(" echo ", SUCCESS)
			.expectCommand("certutil -f -decode", hashOutput("SHA256", sha256Hex(content)))
			.expectCommand("MOVE /Y", hashOutput("SHA256", sha256Hex("something else".getBytes(UTF_8))))
			.expectCommand("certutil -hashfile", FAILURE)
			.expectCommand("DEL /F /Q", SUCCESS);

		final WindowsRemoteException exception = assertThrows(
			WindowsRemoteException.class,
			() -> ShellFileCopy.copyLocalFilesToRemote(executor, localFile.toString(), List.of(localFile.toString()), TIMEOUT)
		);

		assertTrue(exception.getMessage().contains("Integrity check failed"));
	}

	@Test
	void transfersEmptyFile() throws Exception {
		final byte[] content = new byte[0];
		final Path localFile = tempDir.resolve("empty.txt");
		Files.write(localFile, content);

		final ScriptedWindowsRemoteExecutor executor = executorWithTempDirectory()
			.expectCommand("TYPE NUL", hashOutput("SHA256", sha256Hex(content)))
			.expectCommand("certutil -hashfile", FAILURE);

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

		// Windows-forbidden characters, legal in file names on other client platforms
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("wild*card.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("que?ry.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("col:on.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("back\\slash.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("pi|pe.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("angle<bracket>.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("trailingdot."));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("trailing space "));

		// Windows reserved device names, with or without an extension, case-insensitive
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("CON"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("CON.ps1"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("nul.txt"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("com3.vbs"));
		assertThrows(IllegalArgumentException.class, () -> ShellFileCopy.checkTransferableFileName("Lpt9"));

		// Legal Windows file names pass, including cmd metacharacters neutralized by quoting
		ShellFileCopy.checkTransferableFileName("My Script (v2) & more!.vbs");
		ShellFileCopy.checkTransferableFileName("CONSOLE.vbs");
		ShellFileCopy.checkTransferableFileName("COM10.txt");
		ShellFileCopy.checkTransferableFileName("null.txt");
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

		// Very long names are truncated (digest keeps them unique) so that even with the
		// ".<unique>.part.b64" staging suffixes the NTFS 255-character component limit holds
		final String longName = ShellFileCopy.contentAddressedName("x".repeat(300) + ".vbs", content);
		assertTrue(longName.length() <= 180);
		assertTrue(longName.endsWith(".ba7816bf8f01.vbs"));

		final String longExtension = ShellFileCopy.contentAddressedName("f." + "e".repeat(300), content);
		assertTrue(longExtension.length() <= 180);
		assertTrue(longExtension.contains(".ba7816bf8f01."));

		// Truncation never splits a surrogate pair: a malformed half would become "?" (illegal
		// and a wildcard in Windows paths) once the command is UTF-8 encoded
		final String emojiExtension = ShellFileCopy.contentAddressedName("f." + "😀".repeat(15), content);
		assertEquals(emojiExtension, new String(emojiExtension.getBytes(UTF_8), UTF_8));
		final String emojiBase = ShellFileCopy.contentAddressedName("😀".repeat(300) + ".vbs", content);
		assertEquals(emojiBase, new String(emojiBase.getBytes(UTF_8), UTF_8));
		assertTrue(emojiBase.length() <= 180);

		// The explicit bound derived from the directory keeps the COMPLETE staging path (with
		// the ".<unique>.part.b64" suffixes) within the traditional Windows MAX_PATH limit,
		// even for the longest allowed (64-character) client computer name
		final String longDirectory = "C:\\Windows\\Temp\\SEN_ShareFor_" + "h".repeat(64) + "$";
		final int budget = ShellFileCopy.maxRemoteNameLength(longDirectory);
		final String bounded = ShellFileCopy.contentAddressedName("x".repeat(300) + ".vbs", content, budget);
		assertTrue(
			longDirectory.length() + 1 + bounded.length() + ".0123456789a-bcde.part".length() + ".b64".length() <= 259
		);
		assertTrue(bounded.endsWith(".ba7816bf8f01.vbs"));

		// Same name, different content: different remote path (no cross-client overwrite)
		assertFalse(
			ShellFileCopy
				.contentAddressedName("script.vbs", content)
				.equals(ShellFileCopy.contentAddressedName("script.vbs", "abd".getBytes(UTF_8)))
		);
	}

	@Test
	void classifiesQuotaRejectionsForRetry() {
		// Retryable: the operation was rejected at creation (shell or command), nothing ran yet
		assertTrue(
			ShellFileCopy.isRetryableQuotaRejection(
				new WindowsRemoteException(
					"Command failed: HTTP 500 (WSManFault 2150859174): The WS-Management service cannot process the request."
				)
			)
		);
		assertTrue(
			ShellFileCopy.isRetryableQuotaRejection(
				new WindowsRemoteException("Create shell failed: HTTP 500 (WSManFault 2150859174): quota exceeded")
			)
		);

		// Not retryable: the command may already be running (Receive), or it's another fault
		assertFalse(
			ShellFileCopy.isRetryableQuotaRejection(
				new WindowsRemoteException("Receive failed: HTTP 500 (WSManFault 2150859174): quota exceeded")
			)
		);
		assertFalse(
			ShellFileCopy.isRetryableQuotaRejection(
				new WindowsRemoteException("Command failed: HTTP 500 (WSManFault 2150858793): timeout")
			)
		);
		assertFalse(ShellFileCopy.isRetryableQuotaRejection(new WindowsRemoteException((String) null)));
	}

	@Test
	void retriesACommandRejectedByTheOperationQuota() throws Exception {
		final byte[] content = "quota".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("quota.bat");
		Files.write(localFile, content);

		// Delegate scripted for the cheap skip path (remote copy already identical)
		final ScriptedWindowsRemoteExecutor delegate = executorWithTempDirectory()
			.expectCommand("certutil -hashfile", hashOutput("SHA256", sha256Hex(content)));

		// The first command attempt is rejected by the server operation quota; the retry succeeds
		final java.util.concurrent.atomic.AtomicInteger rejections = new java.util.concurrent.atomic.AtomicInteger(1);
		final WindowsRemoteExecutor flaky = new WindowsRemoteExecutor() {
			@Override
			public List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout) {
				return delegate.executeWql(wqlQuery, timeout);
			}

			@Override
			public WindowsRemoteCommandResult executeCommand(
				final String command,
				final String workingDirectory,
				final java.nio.charset.Charset charset,
				final long timeout
			) throws WindowsRemoteException {
				if (command.contains("certutil -hashfile") && rejections.getAndDecrement() > 0) {
					throw new WindowsRemoteException(
						"Command failed: HTTP 500 (WSManFault 2150859174): maximum number of concurrent operations exceeded"
					);
				}
				return delegate.executeCommand(command, workingDirectory, charset, timeout);
			}

			@Override
			public String getHostname() {
				return delegate.getHostname();
			}

			@Override
			public String getUsername() {
				return delegate.getUsername();
			}

			@Override
			public char[] getPassword() {
				return delegate.getPassword();
			}

			@Override
			public void close() {
				delegate.close();
			}
		};

		final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
			flaky,
			localFile.toString(),
			List.of(localFile.toString()),
			TIMEOUT
		);

		assertEquals(
			expectedRemoteDirectory() + "\\" + ShellFileCopy.contentAddressedName("quota.bat", content),
			updatedCommand
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

		// The combined probe hashes with every supported algorithm in a single command leg
		assertEquals(
			"certutil -hashfile \"C:\\f\" SHA256 & certutil -hashfile \"C:\\f\" SHA1",
			ShellFileCopy.digestProbe("C:\\f")
		);
	}
}
