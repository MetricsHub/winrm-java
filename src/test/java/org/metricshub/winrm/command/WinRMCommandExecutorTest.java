package org.metricshub.winrm.command;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.command.WinRMCommandExecutor.execute;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.ScriptedWindowsRemoteExecutor;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMExecutorFactory;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.mockito.MockedStatic;

class WinRMCommandExecutorTest {

	private static final String COMMAND = "launch";
	private static final String HOSTNAME = "host";
	private static final String USERNAME = "domain\\user";
	private static final char[] PASSWORD = "pass".toCharArray();
	private static final long TIMEOUT = 30 * 1000L;
	private static final Path TICKET_CACHE = Paths.get("path");
	private static final List<AuthenticationEnum> AUTHENTICATIONS = singletonList(NTLM);

	@TempDir
	Path tempDir;

	@Test
	void testExecuteArgumentChecks() {
		final String workingDirectory = " \t\r\n dir \t\r\n ";
		final List<String> localFileToCopyList = singletonList(" \r\t\n localFile \t\r\n ");

		assertThrows(
			IllegalArgumentException.class,
			() -> execute(
				null,
				HTTPS,
				HOSTNAME,
				5986,
				USERNAME,
				PASSWORD,
				workingDirectory,
				TIMEOUT,
				localFileToCopyList,
				TICKET_CACHE,
				AUTHENTICATIONS
			)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> execute(
				COMMAND,
				HTTPS,
				null,
				5986,
				USERNAME,
				PASSWORD,
				workingDirectory,
				TIMEOUT,
				localFileToCopyList,
				TICKET_CACHE,
				AUTHENTICATIONS
			)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> execute(
				COMMAND,
				HTTPS,
				HOSTNAME,
				5986,
				null,
				PASSWORD,
				workingDirectory,
				TIMEOUT,
				localFileToCopyList,
				TICKET_CACHE,
				AUTHENTICATIONS
			)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> execute(
				COMMAND,
				HTTPS,
				HOSTNAME,
				5986,
				USERNAME,
				null,
				workingDirectory,
				TIMEOUT,
				localFileToCopyList,
				TICKET_CACHE,
				AUTHENTICATIONS
			)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> execute(
				COMMAND,
				HTTPS,
				HOSTNAME,
				5986,
				USERNAME,
				PASSWORD,
				workingDirectory,
				-1L,
				localFileToCopyList,
				TICKET_CACHE,
				AUTHENTICATIONS
			)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> execute(
				COMMAND,
				HTTPS,
				HOSTNAME,
				5986,
				USERNAME,
				PASSWORD,
				workingDirectory,
				0L,
				localFileToCopyList,
				TICKET_CACHE,
				AUTHENTICATIONS
			)
		);
	}

	@Test
	void testExecuteWithoutFilesToCopy() throws Exception {
		final WindowsRemoteCommandResult expected = new WindowsRemoteCommandResult("stdout", "stderr", 1.0f, 0);

		final ScriptedWindowsRemoteExecutor executor = new ScriptedWindowsRemoteExecutor()
			.expectWql("CodeSet", List.of(Map.of("CodeSet", "65001")))
			.expectCommand(COMMAND, expected);

		try (final MockedStatic<WinRMExecutorFactory> mockedFactory = mockStatic(WinRMExecutorFactory.class)) {
			mockedFactory
				.when(() -> WinRMExecutorFactory.createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
				.thenReturn(executor);

			assertEquals(
				expected,
				execute(COMMAND, null, HOSTNAME, null, USERNAME, PASSWORD, null, TIMEOUT, null, null, null)
			);

			assertEquals(
				expected,
				execute(COMMAND, null, HOSTNAME, null, USERNAME, PASSWORD, null, TIMEOUT, emptyList(), null, null)
			);

			// A list of blank names behaves like no list at all
			assertEquals(
				expected,
				execute(COMMAND, null, HOSTNAME, null, USERNAME, PASSWORD, null, TIMEOUT, singletonList(" \r\t\n "), null, null)
			);

			assertEquals(List.of(COMMAND, COMMAND, COMMAND), executor.getExecutedCommands());
			assertTrue(executor.isClosed());
		}
	}

	@Test
	void testExecuteWithFileToCopy() throws Exception {
		final byte[] content = "WScript.Echo \"Hello é\"".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("MyScript.vbs");
		Files.write(localFile, content);

		final WindowsRemoteCommandResult expected = new WindowsRemoteCommandResult("stdout", "stderr", 1.0f, 0);

		final StringBuilder hex = new StringBuilder();
		for (final byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
			hex.append(String.format("%02x", b));
		}
		final WindowsRemoteCommandResult remoteHash = new WindowsRemoteCommandResult(
			"SHA256 hash of file:\r\n" + hex + "\r\nCertUtil: -hashfile command completed successfully.\r\n",
			"",
			0.1f,
			0
		);
		final WindowsRemoteCommandResult failure = new WindowsRemoteCommandResult("", "not found", 0.1f, 1);
		final WindowsRemoteCommandResult success = new WindowsRemoteCommandResult("", "", 0.1f, 0);

		final ScriptedWindowsRemoteExecutor executor = new ScriptedWindowsRemoteExecutor()
			.expectWql("WindowsDirectory", List.of(Map.of("WindowsDirectory", "C:\\Windows")))
			.expectWql("CodeSet", List.of(Map.of("CodeSet", "65001")))
			.expectCommand("MKDIR", success)
			.expectCommand(" echo ", success)
			.expectCommand("certutil -f -decode", remoteHash)
			.expectCommand("MOVE /Y", remoteHash)
			.expectCommand("certutil -hashfile", failure)
			.expectCommand("CSCRIPT", expected);

		try (final MockedStatic<WinRMExecutorFactory> mockedFactory = mockStatic(WinRMExecutorFactory.class)) {
			mockedFactory
				.when(() -> WinRMExecutorFactory.createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
				.thenReturn(executor);

			final WindowsRemoteCommandResult actual = execute(
				"CSCRIPT " + localFile,
				null,
				HOSTNAME,
				null,
				USERNAME,
				PASSWORD,
				null,
				TIMEOUT,
				singletonList(localFile.toString()),
				null,
				null
			);

			assertEquals(expected, actual);

			// The executed command references the remote copy, wrapped in CMD.EXE /C (...)
			final String finalCommand = executor.getExecutedCommands().get(executor.getExecutedCommands().size() - 1);
			assertTrue(finalCommand.startsWith("CMD.EXE /C (CSCRIPT "));
			assertTrue(finalCommand.contains("\\Temp\\"));
			// The remote name is content-addressed: MyScript.<digest-fragment>.vbs
			assertTrue(finalCommand.matches("(?s).*MyScript\\.[0-9a-f]{12}\\.vbs.*"));
			assertTrue(executor.isClosed());
		}
	}
}
