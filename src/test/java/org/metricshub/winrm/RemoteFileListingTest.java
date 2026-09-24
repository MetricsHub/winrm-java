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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.light.FakeWsmanResponses.commandResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.done;
import static org.metricshub.winrm.light.FakeWsmanResponses.envelope;
import static org.metricshub.winrm.light.FakeWsmanResponses.receiveResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.resourceCreated;
import static org.metricshub.winrm.light.FakeWsmanResponses.signalResponse;
import static org.metricshub.winrm.light.FakeWsmanResponses.stream;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.light.FakeWsmanServer;

/**
 * Tests of the metadata side of the remote file access: the record parser, and
 * {@link RemoteFile#info()} and {@link RemoteDirectoryListing} against {@link FakeWsmanServer}
 * with scripted records. The scripts themselves run in {@link RemoteFilesScriptTest}.
 */
class RemoteFileListingTest {

	private static final String DOMAIN = "FAKE";
	private static final String USER = "user";
	private static final String PASSWORD = "s3cret-Passw0rd";
	private static final String COMMAND_ID = "CMD-1";
	private static final String DIR = "C:\\inetpub\\logs";

	/** 2026-01-02T03:04:05.6789012Z as a FileTime. */
	private static final long FILETIME = 134_117_966_456_789_012L;
	private static final Instant INSTANT = Instant.parse("2026-01-02T03:04:05.6789012Z");

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

	private static String b64(final String text) {
		return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	private static String file(final String path, final long size) {
		return "F 32 " + size + " " + FILETIME + " " + FILETIME + " " + FILETIME + " " + b64(path) + "\n";
	}

	private static String dir(final String path) {
		return "F 16 0 " + FILETIME + " " + FILETIME + " " + FILETIME + " " + b64(path) + "\n";
	}

	private static String denied(final String path) {
		return "! " + b64(path) + " " + b64("Access to the path '" + path + "' is denied.") + "\n";
	}

	/** Script a whole command: shell creation, command, the given stdout chunks (the last one completes), Signal. */
	private void enqueue(final int exitCode, final String... chunks) {
		server.enqueue(200, envelope(resourceCreated("SHELL-1"))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
		for (int i = 0; i < chunks.length; i++) {
			final boolean last = i == chunks.length - 1;
			server.enqueue(
				200,
				envelope(
					receiveResponse(
						stream("stdout", COMMAND_ID, chunks[i].getBytes(StandardCharsets.US_ASCII)),
						last ? done(COMMAND_ID, exitCode) : null
					)
				)
			);
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
	void parseEntryReadsEveryField() {
		final RemoteFileInfo info = RemoteFiles.parseEntry(
			"F 1063 5368709120 " + FILETIME + " 116444736000000000 116444736000000001 " + b64("D:\\données\\漢字.bin")
		);
		assertEquals("D:\\données\\漢字.bin", info.path());
		assertEquals("漢字.bin", info.name());
		// 0x427: read-only, hidden, system, archive, reparse point.
		assertEquals(0x427, info.attributes());
		assertTrue(info.isReadOnly() && info.isHidden() && info.isSystem() && info.isArchive() && info.isReparsePoint());
		assertFalse(info.isDirectory());
		// Larger than 4 GiB.
		assertEquals(5_368_709_120L, info.size());
		assertEquals(INSTANT, info.lastModified());
		assertEquals(Instant.EPOCH, info.created());
		assertEquals(Instant.ofEpochSecond(0, 100), info.lastAccessed());
		assertEquals(
			info,
			RemoteFiles.parseEntry(
				"F 1063 5368709120 " + FILETIME + " 116444736000000000 116444736000000001 " + b64("D:\\données\\漢字.bin")
			)
		);
		assertTrue(info.toString().endsWith("D:\\données\\漢字.bin"), info.toString());

		final RemoteFileInfo junction = RemoteFiles
			.parseEntry(dir("C:\\Documents and Settings").strip().replace("F 16 ", "F 1046 "));
		assertTrue(junction.isDirectory() && junction.isReparsePoint() && junction.isHidden() && junction.isSystem());
		assertEquals("Documents and Settings", junction.name());
		assertEquals("C:", RemoteFiles.parseEntry(dir("C:\\").strip()).name());
		assertEquals("share", RemoteFiles.parseEntry(dir("\\\\server\\share").strip()).name());
	}

	@Test
	void fileTimesConvertBothWays() {
		assertEquals(FILETIME, RemoteFiles.toFileTime(INSTANT));
		assertEquals(INSTANT, RemoteFiles.fromFileTime(FILETIME));
		assertEquals(Instant.parse("1601-01-01T00:00:00Z"), RemoteFiles.fromFileTime(0));
	}

	@Test
	void parseInaccessibleReturnsThePath() {
		assertEquals("C:\\Windows\\CSC", RemoteFiles.parseInaccessible(denied("C:\\Windows\\CSC").strip()));
	}

	@Test
	void malformedOrTruncatedRecordsFailClearly() {
		final String good = file("C:\\a.log", 1).strip();
		for (final String bad : List.of(
			good.substring(0, good.lastIndexOf(' ')), // truncated: no path
			good + " extra", // the path field is not base64
			good.replace("F 32 1", "F 32 x"), // non-numeric size
			good.replace("F 32", "X 32"), // unknown record type
			good.substring(0, good.length() - 3) + "!!!", // corrupted base64
			"! " + b64("C:\\x"), // ! record without message
			"garbage"
		)) {
			final WinRMClientException e = assertThrows(
				WinRMClientException.class,
				() -> {
					if (bad.startsWith("!")) {
						RemoteFiles.parseInaccessible(bad);
					} else {
						RemoteFiles.parseEntry(bad);
					}
				},
				bad
			);
			assertTrue(e.getMessage().startsWith("Malformed remote file record: "), e.getMessage());
		}
	}

	@Test
	void executeCollectsEntriesAndInaccessibleDirectories() {
		// Records cut across Receive chunks, keepalive blank lines in between.
		final String records = file(DIR + "\\a.log", 10) + "\n" + dir(DIR + "\\W3SVC1") + denied(DIR + "\\W3SVC1\\private")
			+
			file(DIR + "\\W3SVC1\\u_ex260101.log", 5_000_000_000L) + "\n";
		enqueue(0, records.substring(0, 30), records.substring(30, 100), records.substring(100));

		final List<String> reported = new ArrayList<>();
		try (WinRMClient client = client()) {
			final RemoteFileList list = client.file(DIR).list().recursive().onInaccessible(reported::add).execute();
			assertEquals(
				List.of(DIR + "\\a.log", DIR + "\\W3SVC1", DIR + "\\W3SVC1\\u_ex260101.log"),
				list.entries().stream().map(RemoteFileInfo::path).collect(Collectors.toList())
			);
			assertEquals(5_000_000_000L, list.entries().get(2).size());
			assertEquals(List.of(DIR + "\\W3SVC1\\private"), list.inaccessible());
			assertEquals(list.inaccessible(), reported);
			assertEquals("3 entries, 1 inaccessible", list.toString());
		}
		final String script = sentScript();
		assertTrue(script.contains(b64(DIR)), script);
		// Recursive, unlimited, no filter.
		assertTrue(
			script.contains(
				"FromBase64String(''));$k=0;$mn=0;$mx=" + Long.MAX_VALUE + ";$ta=-1;$tb=" + Long.MAX_VALUE + ";$md=" +
					Integer.MAX_VALUE + ";"
			),
			script
		);
	}

	@Test
	void anEmptyDirectoryIsAnEmptyList() {
		enqueue(0, "\n");
		try (WinRMClient client = client()) {
			final RemoteFileList list = client.file(DIR).list().execute();
			assertTrue(list.entries().isEmpty());
			assertTrue(list.inaccessible().isEmpty());
		}
		// Not recursive: depth 1.
		assertTrue(sentScript().contains(";$md=1;"), sentScript());
	}

	@Test
	void filtersReachTheScript() {
		enqueue(0, "\n");
		final Instant after = Instant.parse("2026-01-01T00:00:00Z");
		final Instant before = Instant.parse("2026-02-01T00:00:00Z");
		try (WinRMClient client = client()) {
			client
				.file(DIR)
				.list()
				.glob("u_ex*.log")
				.maxDepth(3)
				.directoriesOnly()
				.filesOnly()
				.minSize(1024)
				.maxSize(1 << 20)
				.modifiedAfter(after)
				.modifiedBefore(before)
				.execute();
		}
		assertTrue(
			sentScript()
				.contains(
					"FromBase64String('" + b64("^u_ex.*\\.log$") + "'));$k=1;$mn=1024;$mx=1048576;$ta=" +
						RemoteFiles.toFileTime(after) + ";$tb=" + RemoteFiles.toFileTime(before) + ";$md=3;"
				),
			sentScript()
		);
	}

	@Test
	void streamYieldsEntriesAsTheyArriveAndReportsInaccessibleDirectories() {
		enqueue(0, dir(DIR + "\\a") + denied(DIR + "\\a\\b"), file(DIR + "\\a\\c.log", 1));
		final List<String> reported = new ArrayList<>();
		try (
			WinRMClient client = client();
			Stream<RemoteFileInfo> entries = client.file(DIR).list().recursive().onInaccessible(reported::add).stream()) {
			assertEquals(
				List.of("a", "c.log"),
				entries.map(RemoteFileInfo::name).collect(Collectors.toList())
			);
			assertEquals(List.of(DIR + "\\a\\b"), reported);
		}
	}

	@Test
	void aFailureIsThrownFromTheStreamAndByExecute() {
		enqueue(RemoteFiles.EXIT_NOT_FOUND, "");
		try (WinRMClient client = client(); Stream<RemoteFileInfo> entries = client.file(DIR).list().stream()) {
			final WinRMClientException e = assertThrows(WinRMClientException.class, entries::count);
			assertTrue(e.getMessage().contains("not found") && e.getMessage().contains(DIR), e.getMessage());
		}
		enqueue(RemoteFiles.EXIT_NOT_DIRECTORY, "");
		try (WinRMClient client = client()) {
			final RemoteDirectoryListing listing = client.file(DIR).list();
			final WinRMClientException e = assertThrows(WinRMClientException.class, listing::execute);
			assertTrue(e.getMessage().contains("not a directory"), e.getMessage());
		}
	}

	@Test
	void infoReturnsThePropertiesOrEmptyForAMissingPath() throws Exception {
		enqueue(0, file(DIR + "\\a.log", 10) + "\n");
		try (WinRMClient client = client()) {
			final RemoteFileInfo info = client.file(DIR + "\\a.log").info().orElseThrow();
			assertEquals(10, info.size());
			assertEquals(INSTANT, info.lastModified());
		}
		assertTrue(sentScript().contains(b64(DIR + "\\a.log")), sentScript());

		server.close();
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
		enqueue(RemoteFiles.EXIT_NOT_FOUND, "");
		try (WinRMClient client = client()) {
			assertTrue(client.file(DIR + "\\missing.log").info().isEmpty());
		}

		server.close();
		server = new FakeWsmanServer(DOMAIN, USER, PASSWORD);
		enqueue(RemoteFiles.EXIT_NOT_FOUND, "");
		try (WinRMClient client = client()) {
			assertFalse(client.file(DIR + "\\missing.log").exists());
		}
	}

	@Test
	void progressRecordsAreLeftOutOfTheErrorMessage() {
		server.enqueue(200, envelope(resourceCreated("SHELL-1"))).enqueue(200, envelope(commandResponse(COMMAND_ID)));
		final String stderr = "#< CLIXML\r\nThe path is too long.\r\n<Objs Version=\"1.1.0.1\"><Obj S=\"progress\"/></Objs>\r\n";
		server.enqueue(
			200,
			envelope(
				receiveResponse(stream("stderr", COMMAND_ID, stderr.getBytes(StandardCharsets.UTF_8)), done(COMMAND_ID, 1))
			)
		);
		server.enqueue(200, envelope(signalResponse()));
		try (WinRMClient client = client()) {
			final RemoteFile file = client.file(DIR);
			final WinRMClientException e = assertThrows(WinRMClientException.class, file::info);
			assertTrue(e.getMessage().endsWith("(exit code 1): The path is too long."), e.getMessage());
		}
	}

	@Test
	void infoFailsOnAccessDenied() {
		enqueue(RemoteFiles.EXIT_ACCESS_DENIED, "");
		try (WinRMClient client = client()) {
			final RemoteFile file = client.file("C:\\System Volume Information\\x");
			final WinRMClientException e = assertThrows(WinRMClientException.class, file::info);
			assertTrue(e.getMessage().contains("Access denied"), e.getMessage());
		}
	}

	@Test
	void globsBecomeAnchoredRegexesWithOnlyTwoWildcards() {
		assertEquals("^u_ex.*\\.log$", RemoteFiles.globRegex("u_ex*.log"));
		assertEquals("^a.\\ \\[1\\]\\(é\\)\\$\\^\\+漢$", RemoteFiles.globRegex("a? [1](é)$^+漢"));
	}

	@Test
	void theScriptsFitTheCommandLineWithLongPaths() {
		final String path = "C:\\" + "a".repeat(400);
		assertNotNull(CommandRequest.encodePowerShell(RemoteFiles.infoScript(path)));
		assertNotNull(
			CommandRequest
				.encodePowerShell(RemoteFiles.listScript(path, "*.log", 1, 0, Long.MAX_VALUE, -1, Long.MAX_VALUE, 3))
		);
	}

	@Test
	void invalidSettingsAreRejectedLocally() {
		try (WinRMClient client = client()) {
			final RemoteDirectoryListing listing = client.file(DIR).list();
			assertThrows(IllegalArgumentException.class, () -> listing.glob(" "));
			assertThrows(IllegalArgumentException.class, () -> listing.maxDepth(0));
			assertThrows(IllegalArgumentException.class, () -> listing.modifiedAfter(null));
			assertThrows(IllegalArgumentException.class, () -> listing.onInaccessible(null));
			assertThrows(IllegalArgumentException.class, () -> listing.timeout(Duration.ZERO));
		}
		assertEquals(0, server.decryptedRequests().size());
	}
}
