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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.enumerationDone;
import static org.metricshub.winrm.light.FakeWsmanResponses.fault;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.metricshub.winrm.exceptions.WinRMAuthenticationException;
import org.metricshub.winrm.exceptions.WinRMFaultException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WqlSyntaxException;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * End-to-end tests of the fluent {@link WinRMClient} API against {@link FakeWsmanServer}: the
 * full NTLM handshake and message encryption, the typed results, the wire effect of the
 * builder options, and the unchecked exception mapping — all in-process, no Windows host.
 */
class WinRMClientTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";

	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	private FakeWsmanServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private WinRMClient.Builder builder(final String password) {
		return WinRMClient
			.builder("127.0.0.1")
			.port(server.port())
			.credentials(DOMAIN + "\\" + USER, password.toCharArray());
	}

	private static String service(final String name, final String state) {
		return instance("Win32_Service", "Name", name, "State", state);
	}

	@Test
	void wqlReturnsTypedRowsAndColumnsOverEncryptedNtlm() throws Exception {
		server
			.enqueue(
				200,
				envelope(
					"<wsen:EnumerateResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsen:EnumerationContext>uuid:CTX-1</wsen:EnumerationContext>" +
						"<wsman:Items>" +
						service("Spooler", "Running") +
						"</wsman:Items>" +
						"</wsen:EnumerateResponse>"
				)
			)
			.enqueue(
				200,
				envelope(
					"<wsen:PullResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsen:Items>" +
						service("WinRM", "Stopped") +
						"</wsen:Items>" +
						"<wsman:EndOfSequence/>" +
						"</wsen:PullResponse>"
				)
			);

		try (WinRMClient client = builder(PASSWORD).build()) {
			final WqlResult result = client.wql("SELECT Name, State FROM Win32_Service").execute();

			assertEquals(List.of("Name", "State"), result.columns());
			assertEquals(2, result.size());
			assertFalse(result.isEmpty());
			assertEquals("Spooler", result.rows().get(0).string("Name"));
			// Property lookup is case-insensitive, like WMI itself.
			assertEquals("Running", result.rows().get(0).string("state"));
			assertNotNull(result.elapsed());

			// The result is directly iterable.
			int count = 0;
			for (final WqlRow row : result) {
				assertNotNull(row.string("Name"));
				count++;
			}
			assertEquals(2, count);
		}

		// Defaults pinned on the wire: MaxElements 32000, no MaxTime, ROOT/CIMV2 namespace.
		final List<String> requests = server.decryptedRequests();
		assertEquals(2, requests.size(), () -> String.join("\n---\n", requests));
		final String enumerate = requests.get(0);
		assertTrue(enumerate.contains("<wsman:MaxElements>32000</wsman:MaxElements>"), enumerate);
		assertTrue(enumerate.contains("http://schemas.microsoft.com/wbem/wsman/1/wmi/ROOT/CIMV2/*"), enumerate);
		final String pull = requests.get(1);
		assertTrue(pull.contains("<wsen:MaxElements>32000</wsen:MaxElements>"), pull);
		assertFalse(pull.contains("MaxTime"), pull);
	}

	@Test
	void wqlOptionsReachTheWire() throws Exception {
		server
			.enqueue(
				200,
				envelope(
					"<wsen:EnumerateResponse xmlns:wsen=\"" +
						WSEN +
						"\">" +
						"<wsen:EnumerationContext>uuid:CTX-1</wsen:EnumerationContext>" +
						"</wsen:EnumerateResponse>"
				)
			)
			.enqueue(
				200,
				envelope(
					"<wsen:PullResponse xmlns:wsen=\"" +
						WSEN +
						"\" xmlns:wsman=\"" +
						WSMAN +
						"\">" +
						"<wsen:Items>" +
						service("Spooler", "Running") +
						"</wsen:Items>" +
						"<wsman:EndOfSequence/>" +
						"</wsen:PullResponse>"
				)
			);

		try (WinRMClient client = builder(PASSWORD).build()) {
			final WqlResult result = client
				.wql("SELECT Name, State FROM Win32_Service")
				.namespace("root\\custom")
				.pageSize(100)
				.pullTimeout(Duration.ofSeconds(5))
				.timeout(Duration.ofSeconds(10))
				.execute();

			assertEquals(1, result.size());
		}

		final List<String> requests = server.decryptedRequests();
		assertEquals(2, requests.size(), () -> String.join("\n---\n", requests));
		final String enumerate = requests.get(0);
		assertTrue(enumerate.contains("<wsman:MaxElements>100</wsman:MaxElements>"), enumerate);
		assertTrue(enumerate.contains("http://schemas.microsoft.com/wbem/wsman/1/wmi/root/custom/*"), enumerate);
		assertTrue(enumerate.contains("<wsman:OperationTimeout>PT10S</wsman:OperationTimeout>"), enumerate);
		final String pull = requests.get(1);
		assertTrue(pull.contains("<wsen:MaxTime>PT5S</wsen:MaxTime>"), pull);
		assertTrue(pull.contains("<wsen:MaxElements>100</wsen:MaxElements>"), pull);
	}

	@Test
	void commandReturnsTypedResult() throws Exception {
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", "CMD-1", "output".getBytes(StandardCharsets.UTF_8)) +
							stream("stderr", "CMD-1", "warn".getBytes(StandardCharsets.UTF_8)),
						done("CMD-1", 3)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			final CommandResult result = client
				.command("mycommand.exe")
				.workingDirectory("C:\\Temp")
				.charset(StandardCharsets.UTF_8)
				.execute();

			assertEquals("output", result.stdout());
			assertEquals("warn", result.stderr());
			assertEquals(3, result.exitCode());
			assertNotNull(result.elapsed());
		}

		final List<String> requests = server.decryptedRequests();
		final String create = requests.get(0);
		assertTrue(create.contains("<rsp:WorkingDirectory>C:\\Temp</rsp:WorkingDirectory>"), create);
		assertTrue(requests.get(1).contains("mycommand.exe"), requests.get(1));
	}

	@Test
	void environmentVariablesAreSentInTheCreateShellRequest() throws Exception {
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "42".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0)))
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			final CommandResult result = client
				.command("echo %BUILD_NUMBER%")
				.environment("BUILD_NUMBER", "42")
				.environment("CONFIG", "a<b&\"c\"")
				.workingDirectory("C:\\build")
				.execute();

			assertEquals("42", result.stdout());
		}

		final String create = server.decryptedRequests().get(0);
		// Insertion order preserved, values XML-escaped.
		assertTrue(
			create.contains(
				"<rsp:Environment>" +
					"<rsp:Variable Name=\"BUILD_NUMBER\">42</rsp:Variable>" +
					"<rsp:Variable Name=\"CONFIG\">a&lt;b&amp;&quot;c&quot;</rsp:Variable>" +
					"</rsp:Environment>"
			),
			create
		);
		// The MS-WSMV Shell_Type schema sequence: Environment, then WorkingDirectory, then the
		// stream declarations.
		final int environment = create.indexOf("<rsp:Environment>");
		final int workingDirectory = create.indexOf("<rsp:WorkingDirectory>");
		final int inputStreams = create.indexOf("<rsp:InputStreams>");
		assertTrue(environment < workingDirectory && workingDirectory < inputStreams, create);
	}

	@Test
	void uploadsCarryTheEnvironmentIntoTheShellTheyCreate(@TempDir final java.nio.file.Path tempDir) throws Exception {
		// .environment(...) combined with .upload(...): the transfer commands run FIRST and are
		// what actually creates the shell, so they must carry the environment — the real command
		// then inherits it. Without that, the variables would be silently dropped.
		final byte[] content = "collect".getBytes(StandardCharsets.UTF_8);
		final java.nio.file.Path localFile = tempDir.resolve("collect.bat");
		java.nio.file.Files.write(localFile, content);
		final StringBuilder digest = new StringBuilder();
		for (final byte b : java.security.MessageDigest.getInstance("SHA-256").digest(content)) {
			digest.append(String.format("%02x", b));
		}
		final String certutil = "SHA256 hash of file x:\r\n" +
			digest +
			"\r\nCertUtil: -hashfile command completed successfully.\r\n";

		server
			// ShellFileCopy locates the Windows directory with a WQL query (no shell involved)...
			.enqueue(200, envelope(enumerationDone(instance("Win32_OperatingSystem", "WindowsDirectory", "C:\\Windows"))))
			// ...then its first command leg (cleanup + MKDIR) creates the shell...
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(200, envelope(receiveResponse("", done("CMD-1", 0))))
			.enqueue(200, envelope(signalResponse()))
			// ...the digest probe reports an identical remote copy (transfer skipped)...
			.enqueue(200, envelope(commandResponse("CMD-2")))
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-2", certutil.getBytes(StandardCharsets.UTF_8)), done("CMD-2", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()))
			// ...and the real command runs in the SAME shell.
			.enqueue(200, envelope(commandResponse("CMD-3")))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-3", "done".getBytes(StandardCharsets.UTF_8)), done("CMD-3", 0)))
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			final CommandResult result = client
				.command(localFile.toString())
				.upload(localFile)
				.environment("BUILD_NUMBER", "42")
				.execute();

			assertEquals("done", result.stdout());
		}

		final List<String> requests = server.decryptedRequests();
		final List<String> creates = requests
			.stream()
			.filter(r -> r.contains("<rsp:InputStreams>"))
			.collect(java.util.stream.Collectors.toList());
		assertEquals(1, creates.size(), () -> String.join("\n---\n", requests));
		assertTrue(
			creates.get(0)
				.contains("<rsp:Environment><rsp:Variable Name=\"BUILD_NUMBER\">42</rsp:Variable></rsp:Environment>"),
			creates.get(0)
		);
	}

	@Test
	void commandDecodesOutputAsUtf8WithoutProbingTheRemoteCodeSet() throws Exception {
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "first".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0)))
			)
			.enqueue(200, envelope(signalResponse()))
			// Second command: the shell is reused, and still no WQL round trip.
			.enqueue(200, envelope(commandResponse("CMD-2")))
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-2", "second".getBytes(StandardCharsets.UTF_8)), done("CMD-2", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			assertEquals("first", client.command("first.exe").execute().stdout());
			assertEquals("second", client.command("second.exe").execute().stdout());
		}

		// The shell is created with code page 65001, so its output charset is known up front: no
		// SELECT CodeSet FROM Win32_OperatingSystem probe before the first command (#142).
		final List<String> requests = server.decryptedRequests();
		assertEquals(
			0,
			requests.stream().filter(r -> r.contains("Win32_OperatingSystem")).count(),
			() -> String.join("\n---\n", requests)
		);
		final long shellCreations = requests.stream().filter(r -> r.contains("<rsp:InputStreams>")).count();
		assertEquals(1, shellCreations, "the second command must reuse the shell");
	}

	@Test
	void commandOutputKeepsNonAsciiCharactersOfEveryLocale() throws Exception {
		// What a French or Japanese host actually sends back through a 65001 shell. Neither line
		// survives a single-byte OEM code page: CP437 has no 番, and decoding its bytes as the ANSI
		// code page turned "numéro" into "num‚ro" (#142).
		final String output = "Le numéro de série du volume est E6B6-D774\r\nボリューム シリアル番号\r\n";
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", "CMD-1", output.getBytes(StandardCharsets.UTF_8)) +
							stream("stderr", "CMD-1", "Accès refusé".getBytes(StandardCharsets.UTF_8)),
						done("CMD-1", 0)
					)
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			final CommandResult result = client.command("dir /A").execute();
			assertEquals(output, result.stdout());
			assertEquals("Accès refusé", result.stderr());
		}
	}

	@Test
	void wsmanFaultSurfacesAsTypedException() throws Exception {
		server.enqueue(
			500,
			fault(
				"2150858778",
				"The WS-Management service cannot process the request.",
				"The WMI service or the WMI provider returned an unknown error: WBEM_E_INVALID_CLASS"
			)
		);

		try (WinRMClient client = builder(PASSWORD).build()) {
			final WinRMFaultException e = assertThrows(
				WinRMFaultException.class,
				() -> client.wql("SELECT Name FROM No_Such_Class").execute()
			);
			// The structured fields carry what callers previously had to extract with contains().
			assertEquals(500, e.getHttpStatus());
			assertEquals("2150858778", e.getFaultCode());
			assertEquals("The WS-Management service cannot process the request.", e.getFaultReason());
			assertTrue(e.getFaultDetail().contains("WBEM_E_INVALID_CLASS"), e.getFaultDetail());
			// And the message keeps the legacy format.
			assertTrue(e.getMessage().contains("Enumerate failed"), e.getMessage());
			assertTrue(e.getMessage().contains("WSManFault 2150858778"), e.getMessage());
		}
	}

	@Test
	void wrongPasswordSurfacesAsTypedAuthenticationException() {
		try (WinRMClient client = builder("wrong-password").build()) {
			final WinRMAuthenticationException e = assertThrows(
				WinRMAuthenticationException.class,
				() -> client.wql("SELECT Name FROM Win32_Service").execute()
			);
			assertEquals(
				"Authentication error on http://127.0.0.1:" + server.port() + "/wsman with user name \"FAKE\\user\"",
				e.getMessage()
			);
		}
	}

	@Test
	void invalidWqlIsRejectedBeforeAnythingIsSent() {
		try (WinRMClient client = builder(PASSWORD).build()) {
			assertThrows(WqlSyntaxException.class, () -> client.wql("HELLO WORLD").execute());
		}
		assertTrue(server.decryptedRequests().isEmpty());
	}

	@Test
	void slowServerSurfacesAsTypedTimeoutException() {
		// The response is scripted to arrive after the client's whole-operation deadline.
		server.enqueueDelayed(200, envelope(enumerationDone(service("Spooler", "Running"))), 5_000);

		try (WinRMClient client = builder(PASSWORD).timeout(Duration.ofMillis(500)).build()) {
			assertThrows(WinRMTimeoutException.class, () -> client.wql("SELECT Name FROM Win32_Service").execute());
		}
	}

	@Test
	void queuedOperationThatTimesOutIsNeverSent() throws Exception {
		// Thread A holds the serial connection with a slow command; thread B's command times out
		// while QUEUED behind it. B's worker must abort instead of executing the command "later" —
		// side effects must never run after the caller was already told the operation timed out.
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueueDelayed(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "slow".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0))),
				2_500
			)
			.enqueue(200, envelope(signalResponse()));

		final java.util.concurrent.atomic.AtomicReference<Object> slowOutcome = new java.util.concurrent.atomic.AtomicReference<>();
		try (WinRMClient client = builder(PASSWORD).build()) {
			final Thread slow = new Thread(() -> {
				try {
					slowOutcome.set(client.command("slow.exe").charset(StandardCharsets.UTF_8).execute().stdout());
				} catch (final RuntimeException e) {
					slowOutcome.set(e);
				}
			});
			slow.start();
			Thread.sleep(500); // let the slow command acquire the connection

			assertThrows(
				WinRMTimeoutException.class,
				() -> client.command("never.exe").charset(StandardCharsets.UTF_8).timeout(Duration.ofMillis(300)).execute()
			);

			slow.join(30_000);
		}

		// The slow command was unaffected by the abandoned one...
		assertEquals("slow", slowOutcome.get());
		// ...and the timed-out command never reached the wire.
		assertTrue(
			server.decryptedRequests().stream().noneMatch(r -> r.contains("never.exe")),
			() -> String.join("\n---\n", server.decryptedRequests())
		);
	}

	@Test
	void commandIsNeverStartedWhenTheTimeoutFiresDuringShellCreation() throws Exception {
		// The Create-shell response arrives AFTER the caller's timeout. A socket read does not
		// observe the cancellation interrupt, so the worker outlives the timeout — but it must
		// abort before STARTING the command, not run it after the caller was told it timed out.
		server
			.enqueueDelayed(200, envelope(resourceCreated("SHELL-1")), 2_000)
			// Responses for the follow-up command, proving the client stays usable (shell reused).
			.enqueue(200, envelope(commandResponse("CMD-2")))
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-2", "second".getBytes(StandardCharsets.UTF_8)), done("CMD-2", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			assertThrows(
				WinRMTimeoutException.class,
				() -> client.command("first.exe").charset(StandardCharsets.UTF_8).timeout(Duration.ofMillis(500)).execute()
			);

			// Blocks until the abandoned worker receives the late Create response and aborts.
			assertEquals("second", client.command("second.exe").charset(StandardCharsets.UTF_8).execute().stdout());
		}

		// The timed-out command was never started on the remote host.
		assertTrue(
			server.decryptedRequests().stream().noneMatch(r -> r.contains(">first.exe<")),
			() -> String.join("\n---\n", server.decryptedRequests())
		);
	}

	@Test
	void receivePollingStopsWhenTheTimeoutFiresMidCommand() throws Exception {
		// The command times out while a Receive read is blocked; the late response carries only
		// PARTIAL output (no Done state). The abandoned worker must not re-issue Receive until the
		// remote command eventually ends — it must stop, terminate the command, and release the
		// serial connection for the next operation.
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueueDelayed(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "partial".getBytes(StandardCharsets.UTF_8)), null)),
				2_000
			)
			// The abandoned worker's terminate Signal for CMD-1...
			.enqueue(200, envelope(signalResponse()))
			// ...then the follow-up command, proving the connection was released and stays usable.
			.enqueue(200, envelope(commandResponse("CMD-2")))
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-2", "second".getBytes(StandardCharsets.UTF_8)), done("CMD-2", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			assertThrows(
				WinRMTimeoutException.class,
				() -> client.command("first.exe").charset(StandardCharsets.UTF_8).timeout(Duration.ofMillis(500)).execute()
			);

			// Blocks until the abandoned worker sees the late partial response, aborts, and unlocks.
			assertEquals("second", client.command("second.exe").charset(StandardCharsets.UTF_8).execute().stdout());
		}

		final List<String> requests = server.decryptedRequests();
		// Exactly one Receive was issued for the abandoned command — no polling after the timeout —
		// and its terminate Signal was still sent, so the remote command does not keep running.
		assertEquals(
			1,
			requests.stream().filter(r -> r.contains("CommandId=\"CMD-1\">stdout stderr</rsp:DesiredStream>")).count(),
			() -> String.join("\n---\n", requests)
		);
		assertEquals(
			1,
			requests.stream().filter(r -> r.contains("Signal CommandId=\"CMD-1\"")).count(),
			() -> String.join("\n---\n", requests)
		);
	}

	@Test
	void expiredCachedShellIsRecreatedAndTheCommandRetried() throws Exception {
		server
			// First command: normal lifecycle in a fresh shell.
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(
				200,
				envelope(receiveResponse(stream("stdout", "CMD-1", "first".getBytes(StandardCharsets.UTF_8)), done("CMD-1", 0)))
			)
			.enqueue(200, envelope(signalResponse()))
			// Second command: the server reaped SHELL-1 in the meantime (IdleTimeout) and rejects the
			// Command with shell-not-found; the client must recreate the shell and retry once.
			.enqueue(
				500,
				fault("2150858843", "The WS-Management service cannot process the request because the resource is offline.")
			)
			.enqueue(200, envelope(resourceCreated("SHELL-2")))
			.enqueue(200, envelope(commandResponse("CMD-2")))
			.enqueue(
				200,
				envelope(
					receiveResponse(stream("stdout", "CMD-2", "second".getBytes(StandardCharsets.UTF_8)), done("CMD-2", 0))
				)
			)
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			assertEquals(
				"first",
				client
					.command("first.exe")
					.workingDirectory("C:\\Work")
					.environment("BUILD_NUMBER", "42")
					.charset(StandardCharsets.UTF_8)
					.execute()
					.stdout()
			);
			assertEquals("second", client.command("second.exe").charset(StandardCharsets.UTF_8).execute().stdout());
		}

		final List<String> requests = server.decryptedRequests();
		// The retried Command rides the NEW shell.
		assertTrue(
			requests.stream().anyMatch(r -> r.contains(">second.exe<") && r.contains("Selector Name=\"ShellId\">SHELL-2<")),
			() -> String.join("\n---\n", requests)
		);
		// Exactly two shells were created, and second.exe was sent twice (rejected, then retried).
		final List<String> creates = requests
			.stream()
			.filter(r -> r.contains("<rsp:InputStreams>"))
			.collect(java.util.stream.Collectors.toList());
		assertEquals(2, creates.size());
		assertEquals(2, requests.stream().filter(r -> r.contains(">second.exe<")).count());
		// The recreated shell keeps the working directory AND the environment pinned by the FIRST
		// command, even though the retried command did not set them.
		assertTrue(creates.get(1).contains("<rsp:WorkingDirectory>C:\\Work</rsp:WorkingDirectory>"), creates.get(1));
		assertTrue(
			creates.get(1)
				.contains("<rsp:Environment><rsp:Variable Name=\"BUILD_NUMBER\">42</rsp:Variable></rsp:Environment>"),
			creates.get(1)
		);
	}

	@Test
	void explicitCharsetOverridesTheShellDefault() throws Exception {
		// charset() is the escape hatch for the handful of legacy tools that write pre-converted OEM
		// bytes instead of honoring the console code page — net.exe is the notorious one, and this is
		// exactly what it sends on a French host (0x82 is "é" in CP850, invalid as UTF-8).
		final Charset oem = Charset.forName("IBM850");
		final byte[] output = "Code du pays ou de la région".getBytes(oem);
		server
			.enqueue(200, envelope(resourceCreated("SHELL-1")))
			.enqueue(200, envelope(commandResponse("CMD-1")))
			.enqueue(200, envelope(receiveResponse(stream("stdout", "CMD-1", output), done("CMD-1", 0))))
			.enqueue(200, envelope(signalResponse()));

		try (WinRMClient client = builder(PASSWORD).build()) {
			final CommandResult result = client.command("net user Administrateur").charset(oem).execute();

			assertEquals("Code du pays ou de la région", result.stdout());
		}
	}

	@Test
	void closedClientRejectsOperationsAndCloseIsIdempotent() {
		final WinRMClient client = builder(PASSWORD).build();
		client.close();
		client.close();
		assertThrows(IllegalStateException.class, () -> client.wql("SELECT Name FROM Win32_Service").execute());
	}
}
