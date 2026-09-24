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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueCommandExchange;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellCreation;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueShellDeletion;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * The UTF-8 byte order mark PowerShell 2.0 writes ahead of its redirected output (console code
 * page 65001) is dropped when it starts a stream — on the blocking {@code execute()}, the
 * callback variant and the streaming {@code start()} readers — while a U+FEFF later in the output
 * is kept.
 */
class OutputBomTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";

	private static final String COMMAND_ID = "CMD-1";

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

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

	private static byte[] concat(final byte[]... parts) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (final byte[] part : parts) {
			out.write(part, 0, part.length);
		}
		return out.toByteArray();
	}

	private static byte[] utf8(final String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Script a command whose stdout arrives in two Receive responses — the mark split between
	 * them — with a second U+FEFF inside the output, which must survive.
	 */
	private void enqueueSplitBomExchange() {
		final byte[] stdout = concat(BOM, utf8("x\r\n"), BOM, utf8("y\r\n"));
		enqueueShellCreation(server);
		server
			.enqueue(200, envelope(commandResponse(COMMAND_ID)))
			.enqueue(200, envelope(receiveResponse(stream("stdout", COMMAND_ID, Arrays.copyOfRange(stdout, 0, 2)), null)))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", COMMAND_ID, Arrays.copyOfRange(stdout, 2, stdout.length)),
						done(COMMAND_ID, 0)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));
		enqueueShellDeletion(server);
	}

	@Test
	void executeDropsTheLeadingBomOfEachStreamOnly() {
		// As measured on PowerShell 2.0: stdout starts with the mark; on stderr, a direct
		// [Console]::Error write comes first and the mark lands mid-stream, where it is kept.
		enqueueShellCreation(server);
		enqueueCommandExchange(server, concat(BOM, utf8("2.0\r\n")), concat(utf8("e\r\n"), BOM, utf8("#< CLIXML")), 0);
		enqueueShellDeletion(server);

		try (WinRMClient client = client()) {
			final CommandResult result = client.powerShell("$PSVersionTable.PSVersion.ToString()").execute();

			assertEquals("2.0\r\n", result.stdout());
			assertEquals("e\r\n\uFEFF#< CLIXML", result.stderr());
		}
	}

	@Test
	void executeWithCallbackDropsABomSplitAcrossReceives() {
		enqueueSplitBomExchange();
		final StringBuilder delivered = new StringBuilder();

		try (WinRMClient client = client()) {
			final CommandResult result = client.powerShell("'x'").onStdout(delivered::append).execute();

			assertEquals("x\r\n\uFEFFy\r\n", result.stdout());
			assertEquals("x\r\n\uFEFFy\r\n", delivered.toString());
		}
	}

	@Test
	void streamingReaderDropsABomSplitAcrossReceives() throws Exception {
		enqueueSplitBomExchange();

		try (WinRMClient client = client(); RemoteProcess process = client.powerShell("'x'").start()) {
			final StringWriter stdout = new StringWriter();
			try (BufferedReader reader = process.stdout()) {
				reader.transferTo(stdout);
			}
			assertEquals("x\r\n\uFEFFy\r\n", stdout.toString());
			assertEquals(0, process.waitFor());
		}
	}

	@Test
	void aNonUtf8CharsetKeepsTheBytes() {
		enqueueShellCreation(server);
		enqueueCommandExchange(server, concat(BOM, utf8("x")), new byte[0], 0);
		enqueueShellDeletion(server);

		try (WinRMClient client = client()) {
			final CommandResult result = client.command("type x.txt").charset(StandardCharsets.ISO_8859_1).execute();

			assertEquals("\u00EF\u00BB\u00BFx", result.stdout());
		}
	}
}
