package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2026 MetricsHub
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * Client-side tests of {@link RemoteFile} against {@link FakeWsmanServer}: the scripted
 * base64-line output is decoded incrementally, exit codes become the documented exceptions, and
 * the request settings reach the script. The scripts themselves run in
 * {@link RemoteFilesScriptTest}.
 */
class RemoteFileTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";
	private static final String COMMAND_ID = "CMD-1";
	private static final String PATH = "C:\\Windows\\Temp\\collect.bin";

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private WinRMClient client() {
		return WinRMClient
			.builder("127.0.0.1")
			.port(server.port())
			.credentials(DOMAIN + "\\" + USER, PASSWORD.toCharArray())
			.timeout(Duration.ofSeconds(10))
			.build();
	}

	private static String b64(final byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static String stdout(final String text) {
		return stream("stdout", COMMAND_ID, text.getBytes(StandardCharsets.US_ASCII));
	}

	/** Script a whole read: shell creation, command, the given Receive chunks (the last one completes), Signal. */
	private void enqueueRead(final int exitCode, final String stderr, final String... chunks) {
		server.enqueue(200, envelope(resourceCreated("SHELL-1"))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
		for (int i = 0; i < chunks.length; i++) {
			final boolean last = i == chunks.length - 1;
			final String streams = stdout(chunks[i]) +
				(last && stderr != null ? stream("stderr", COMMAND_ID, stderr.getBytes(StandardCharsets.UTF_8)) : "");
			server.enqueue(200, envelope(receiveResponse(streams, last ? done(COMMAND_ID, exitCode) : null)));
		}
		server.enqueue(200, envelope(signalResponse()));
	}

	/** The PowerShell script the client sent, decoded from its -EncodedCommand. */
	private String sentScript() {
		final String command = server
			.decryptedRequests()
			.stream()
			.filter(r -> r.contains("-EncodedCommand"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no PowerShell command sent"));
		final Matcher matcher = Pattern.compile("-EncodedCommand ([A-Za-z0-9+/=]+)").matcher(command);
		assertTrue(matcher.find(), command);
		return new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_16LE);
	}

	@Test
	void readBytesDecodesLinesSplitAcrossReceiveChunks() {
		final byte[] content = new byte[300];
		for (int i = 0; i < content.length; i++) {
			content[i] = (byte) i;
		}
		final String line1 = b64(java.util.Arrays.copyOfRange(content, 0, 150));
		final String line2 = b64(java.util.Arrays.copyOfRange(content, 150, 300));
		// The first line is cut in the middle by the protocol chunking.
		enqueueRead(0, null, line1.substring(0, 17), line1.substring(17) + "\r\n" + line2 + "\r\n");

		try (WinRMClient client = client()) {
			assertArrayEquals(content, client.file(PATH).readBytes());
		}
		final String script = sentScript();
		// The path travels base64-encoded: no quoting of caller input, whatever it contains.
		assertTrue(script.contains(b64(PATH.getBytes(StandardCharsets.UTF_8))), script);
		// Whole file from the start, capped one byte past the default maxBytes.
		assertTrue(script.contains("$n=[long]0;$l=[long]" + (RemoteFile.DEFAULT_MAX_BYTES + 1)), script);
	}

	@Test
	void rangeSettingsReachTheScript() {
		enqueueRead(0, null, b64("tail".getBytes(StandardCharsets.US_ASCII)) + "\r\n");
		try (WinRMClient client = client()) {
			assertEquals("tail", client.file(PATH).offset(-8192).length(1024).readText(StandardCharsets.US_ASCII));
		}
		assertTrue(sentScript().contains("$n=[long]-8192;$l=[long]1024"), sentScript());
	}

	@Test
	void openStreamHasNoCapAndReadsToTheEnd() throws Exception {
		enqueueRead(0, null, b64(new byte[] { 1, 2, 3 }) + "\r\n", b64(new byte[] { 4, 5 }) + "\r\n");
		try (WinRMClient client = client(); InputStream in = client.file(PATH).openStream()) {
			assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, in.readAllBytes());
		}
		assertTrue(sentScript().contains("$n=[long]0;$l=[long]-1"), sentScript());
	}

	@Test
	void readTextKeepsTheBomAndOpenReaderDecodesACharacterSplitAcrossLines() throws Exception {
		final byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		enqueueRead(0, null, b64(bom) + "\r\n" + b64("é".getBytes(StandardCharsets.UTF_8)) + "\r\n");
		try (WinRMClient client = client()) {
			assertEquals("\uFEFFé", client.file(PATH).readText(StandardCharsets.UTF_8));
		}

		// "é" is C3 A9: its two bytes arrive on two separate lines (two transfer blocks).
		server.close();
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
		enqueueRead(0, null, b64(new byte[] { 'a', (byte) 0xC3 }) + "\r\n", b64(new byte[] { (byte) 0xA9, 'b' }) + "\r\n");
		try (WinRMClient client = client(); BufferedReader reader = client.file(PATH).openReader(StandardCharsets.UTF_8)) {
			assertEquals("aéb", reader.readLine());
		}
	}

	@Test
	void theSizeCapIsSentToTheHostAndEnforced() {
		enqueueRead(0, null, b64(new byte[] { 1, 2, 3, 4, 5 }) + "\r\n");
		try (WinRMClient client = client()) {
			final WinRMClientException e = assertThrows(
				WinRMClientException.class,
				() -> client.file(PATH).maxBytes(4).readBytes()
			);
			assertTrue(e.getMessage().contains("4-byte cap"), e.getMessage());
		}
		assertTrue(sentScript().contains("$l=[long]5"), sentScript());
	}

	@Test
	void aMissingFileFailsWhenTheStreamIsOpened() {
		enqueueRead(RemoteFiles.EXIT_NOT_FOUND, "Could not find file", "");
		try (WinRMClient client = client()) {
			final RemoteFile file = client.file(PATH);
			final WinRMClientException e = assertThrows(WinRMClientException.class, file::openStream);
			assertTrue(e.getMessage().contains("not found") && e.getMessage().contains(PATH), e.getMessage());
		}
	}

	@Test
	void exitCodesBecomeExplicitFailures() {
		assertTrue(RemoteFiles.failure(3, PATH, "h", "").getMessage().contains("sharing violation"));
		assertTrue(RemoteFiles.failure(4, PATH, "h", "").getMessage().contains("Access denied"));
		assertTrue(RemoteFiles.failure(5, PATH, "h", "").getMessage().contains("Constrained Language Mode"));
		assertTrue(RemoteFiles.failure(6, PATH, "h", "").getMessage().contains("is a directory"));
		assertTrue(RemoteFiles.failure(9009, PATH, "h", "").getMessage().contains("PowerShell is not available"));
		final String other = RemoteFiles.failure(1, PATH, "h", "  I/O device error  \n").getMessage();
		assertTrue(other.contains("exit code 1") && other.endsWith(": I/O device error"), other);
	}

	@Test
	void aFailureMidwayIsNotASilentlyShortRead() throws Exception {
		enqueueRead(1, "The device is not ready", b64(new byte[] { 1, 2, 3 }) + "\r\n");
		try (WinRMClient client = client(); InputStream in = client.file(PATH).openStream()) {
			assertEquals(1, in.read());
			final WinRMClientException e = assertThrows(WinRMClientException.class, in::readAllBytes);
			assertTrue(e.getMessage().contains("device is not ready"), e.getMessage());
		}
	}

	@Test
	void unexpectedOutputIsReported() {
		enqueueRead(0, null, "WARNING: not base64!\r\n");
		try (WinRMClient client = client()) {
			final WinRMClientException e = assertThrows(WinRMClientException.class, () -> client.file(PATH).readBytes());
			assertTrue(e.getMessage().contains("Unexpected output"), e.getMessage());
		}
	}

	@Test
	void digestIsLowercaseHex() {
		enqueueRead(0, null, b64(new byte[] { (byte) 0xAB, 0x01, (byte) 0xFF }) + "\r\n");
		try (WinRMClient client = client()) {
			assertEquals("ab01ff", client.file(PATH).digest("SHA256"));
		}
		assertTrue(sentScript().contains("HashAlgorithm]::Create('SHA256')"), sentScript());
	}

	@Test
	void invalidSettingsAreRejectedLocally() {
		try (WinRMClient client = client()) {
			final RemoteFile file = client.file(PATH);
			assertThrows(IllegalArgumentException.class, () -> file.length(-1));
			assertThrows(IllegalArgumentException.class, () -> file.maxBytes(-1));
			assertThrows(IllegalArgumentException.class, () -> file.maxBytes(Integer.MAX_VALUE));
			assertThrows(IllegalArgumentException.class, () -> file.digest("sha256; rm"));
			assertThrows(IllegalArgumentException.class, () -> client.file(" "));
		}
		assertEquals(0, server.decryptedRequests().size());
	}

	@Test
	void theBlockingTerminalsHonorTheWallClockTimeout() {
		server.enqueue(200, envelope(resourceCreated("SHELL-1"))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
		server.enqueueDelayed(200, envelope(receiveResponse("", done(COMMAND_ID, 0))), 3_000);
		server.enqueue(200, envelope(signalResponse()));
		try (WinRMClient client = client()) {
			final RemoteFile file = client.file(PATH).timeout(Duration.ofMillis(500));
			assertThrows(WinRMTimeoutException.class, file::readBytes);
		}
	}
}
