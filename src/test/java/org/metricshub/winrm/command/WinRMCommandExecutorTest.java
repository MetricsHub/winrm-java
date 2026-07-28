package org.metricshub.winrm.command;

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
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.command.WinRMCommandExecutor.execute;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueCommandExchange;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueEnumeration;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellCreation;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellDeletion;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.light.FakeWsmanServer;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * End-to-end tests of {@link WinRMCommandExecutor} against {@link FakeWsmanServer}: the whole
 * stack — factory, {@code LightWinRMService}, real NTLM handshake and message encryption, shell
 * lifecycle, and (for the file-copy path) the in-shell file transfer — runs for real; only the
 * scripted SOAP response bodies are canned.
 */
class WinRMCommandExecutorTest {

	private static final String COMMAND = "launch";
	private static final String HOSTNAME = "host";
	private static final String USERNAME = "FAKE\\user";
	private static final String USER = "user";
	private static final String DOMAIN = "FAKE";
	private static final String PASSWORD_STRING = "pass";
	private static final char[] PASSWORD = PASSWORD_STRING.toCharArray();
	private static final long TIMEOUT = 30 * 1000L;
	private static final Path TICKET_CACHE = Paths.get("path");
	private static final List<AuthenticationEnum> AUTHENTICATIONS = singletonList(NTLM);
	private static final String LOCALHOST = "127.0.0.1";

	private static final byte[] NO_OUTPUT = new byte[0];

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
		try (FakeWsmanServer server = new FakeWsmanServer(DOMAIN, USER, PASSWORD_STRING)) {
			// Three executions: a null file list, an empty one, and a list of blank names must all
			// take the same no-copy path. Each execution creates (and closes) its own executor.
			final List<List<String>> fileListVariants = new ArrayList<>();
			fileListVariants.add(null);
			fileListVariants.add(emptyList());
			fileListVariants.add(singletonList(" \r\t\n "));

			for (final List<String> localFileToCopyList : fileListVariants) {
				enqueueShellCreation(server);
				enqueueCommandExchange(server, "stdout".getBytes(UTF_8), "stderr".getBytes(UTF_8), 0);
				enqueueShellDeletion(server);

				final WindowsRemoteCommandResult result = execute(
					COMMAND,
					null,
					LOCALHOST,
					server.port(),
					USERNAME,
					PASSWORD,
					null,
					TIMEOUT,
					localFileToCopyList,
					null,
					null
				);

				assertEquals("stdout", result.getStdout());
				assertEquals("stderr", result.getStderr());
				assertEquals(0, result.getStatusCode());
			}

			final List<String> requests = server.decryptedRequests();
			// Each execution ran the command verbatim (no CMD.EXE /C wrapper on the no-copy path) and
			// deleted its shell on close, without probing the remote code page (#142)
			assertEquals(0, count(requests, "SELECT CodeSet FROM Win32_OperatingSystem"));
			assertEquals(3, count(requests, "<rsp:Command>" + COMMAND + "</rsp:Command>"));
			assertEquals(3, count(requests, "http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete"));
		}
	}

	@Test
	void testExecuteWithFileToCopy() throws Exception {
		final byte[] content = "WScript.Echo \"Hello é\"".getBytes(UTF_8);
		final Path localFile = tempDir.resolve("MyScript.vbs");
		Files.write(localFile, content);

		final String hashOutput = "SHA256 hash of file:\r\n" +
			sha256Hex(content) +
			"\r\nCertUtil: -hashfile command completed successfully.\r\n";

		try (FakeWsmanServer server = new FakeWsmanServer(DOMAIN, USER, PASSWORD_STRING)) {
			// The in-shell transfer sequence of ShellFileCopy, scripted response by response
			enqueueEnumeration(server, instance("Win32_OperatingSystem", "WindowsDirectory", "C:\\Windows"));
			enqueueShellCreation(server);
			// 1: purge + MKDIR of the remote temporary directory
			enqueueCommandExchange(server, NO_OUTPUT, NO_OUTPUT, 0);
			// 2: pre-transfer digest probe — the destination does not exist yet
			enqueueCommandExchange(server, NO_OUTPUT, "CertUtil: -hashfile command FAILED: 0x80070002".getBytes(UTF_8), 1);
			// 3: the single chunked-echo upload leg (the payload is small)
			enqueueCommandExchange(server, NO_OUTPUT, NO_OUTPUT, 0);
			// 4: certutil -decode + digest probe of the staging file
			enqueueCommandExchange(server, hashOutput.getBytes(UTF_8), NO_OUTPUT, 0);
			// 5: publish (MOVE) + digest probe of the destination
			enqueueCommandExchange(server, hashOutput.getBytes(UTF_8), NO_OUTPUT, 0);
			// then the actual command
			enqueueCommandExchange(server, "stdout".getBytes(UTF_8), "stderr".getBytes(UTF_8), 0);
			enqueueShellDeletion(server);

			final WindowsRemoteCommandResult result = execute(
				"CSCRIPT " + localFile,
				null,
				LOCALHOST,
				server.port(),
				USERNAME,
				PASSWORD,
				null,
				TIMEOUT,
				singletonList(localFile.toString()),
				null,
				null
			);

			assertEquals("stdout", result.getStdout());
			assertEquals("stderr", result.getStderr());
			assertEquals(0, result.getStatusCode());

			final List<String> requests = server.decryptedRequests();

			// The file content went over the wire base64-encoded in an echo leg
			final String base64Content = Base64.getEncoder().encodeToString(content);
			assertTrue(requests.stream().anyMatch(request -> request.contains(base64Content)));

			// The executed command references the remote copy, wrapped in CMD.EXE /C (...)
			final String finalCommand = requests
				.stream()
				.filter(request -> request.contains("<rsp:Command>CMD.EXE /C (CSCRIPT "))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No CMD.EXE /C command found:\n" + String.join("\n---\n", requests)));
			assertTrue(finalCommand.contains("\\Temp\\"), finalCommand);
			// The remote name is content-addressed: MyScript.<digest-fragment>.vbs
			assertTrue(finalCommand.matches("(?s).*MyScript\\.[0-9a-f]{12}\\.vbs.*"), finalCommand);

			// close() deleted the shell
			assertEquals(1, count(requests, "http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete"));
		}
	}

	private static long count(final List<String> requests, final String needle) {
		return requests.stream().filter(request -> request.contains(needle)).count();
	}

	private static String sha256Hex(final byte[] content) throws Exception {
		final StringBuilder hex = new StringBuilder();
		for (final byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}
}
