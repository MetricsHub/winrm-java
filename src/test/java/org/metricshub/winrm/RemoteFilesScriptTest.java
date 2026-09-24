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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the PowerShell scripts of {@link RemoteFiles} in the <b>local</b> {@code powershell.exe},
 * exactly as the remote shell would ({@code -EncodedCommand}), and decodes their output the way
 * the client does: the actual script semantics (seek, negative offsets, share mode, exit codes)
 * are only testable against a real PowerShell. Windows only.
 */
@EnabledOnOs(OS.WINDOWS)
class RemoteFilesScriptTest {

	/** Every byte value, then enough random bytes to span several transfer blocks. */
	private static byte[] content;

	@TempDir
	static Path directory;

	private static Path file;

	@BeforeAll
	static void createFile() throws Exception {
		content = new byte[256 + RemoteFiles.BLOCK_SIZE * 2 + 1234];
		for (int i = 0; i < 256; i++) {
			content[i] = (byte) i;
		}
		final byte[] random = new byte[content.length - 256];
		new Random(42).nextBytes(random);
		System.arraycopy(random, 0, content, 256, random.length);
		file = directory.resolve("données-漢字.bin");
		Files.write(file, content);
	}

	/** The outcome of a script: the decoded bytes and the exit code. */
	private static final class Outcome {

		final byte[] bytes;
		final int exitCode;

		Outcome(final byte[] bytes, final int exitCode) {
			this.bytes = bytes;
			this.exitCode = exitCode;
		}
	}

	private static Outcome run(final String script) throws Exception {
		final Listing raw = exec(script);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (final String line : raw.lines) {
			bytes.write(Base64.getDecoder().decode(line));
		}
		return new Outcome(bytes.toByteArray(), raw.exitCode);
	}

	/** The outcome of a metadata script: its non-blank stdout lines and the exit code. */
	private static final class Listing {

		final List<String> lines;
		final int exitCode;

		Listing(final List<String> lines, final int exitCode) {
			this.lines = lines;
			this.exitCode = exitCode;
		}

		/** The entries, parsed like the client does, by path relative to the test directory. */
		Map<String, RemoteFileInfo> entries() {
			return lines
				.stream()
				.filter(l -> l.startsWith("F "))
				.map(RemoteFiles::parseEntry)
				.collect(Collectors.toMap(i -> relative(i.path()), i -> i));
		}

		List<String> inaccessible() {
			return lines
				.stream()
				.filter(l -> l.startsWith("! "))
				.map(RemoteFiles::parseInaccessible)
				.map(RemoteFilesScriptTest::relative)
				.collect(Collectors.toList());
		}
	}

	private static String relative(final String path) {
		final String root = directory.toString() + "\\";
		assertTrue(path.startsWith(root), path);
		return path.substring(root.length());
	}

	private static Listing exec(final String script) throws Exception {
		final String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
		final Process process = new ProcessBuilder(
			"powershell.exe",
			"-NoProfile",
			"-NonInteractive",
			"-EncodedCommand",
			encoded
		)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		process.getOutputStream().close();
		final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
		if (!process.waitFor(60, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new AssertionError("powershell.exe did not complete");
		}
		final List<String> lines = Arrays
			.stream(stdout.split("\r?\n"))
			.filter(l -> !l.isBlank())
			.map(String::strip)
			.collect(Collectors.toList());
		return new Listing(lines, process.exitValue());
	}

	private static byte[] read(final long offset, final long length) throws Exception {
		final Outcome outcome = run(RemoteFiles.readScript(file.toString(), offset, length));
		assertEquals(0, outcome.exitCode);
		return outcome.bytes;
	}

	private static byte[] slice(final int from, final int to) {
		return Arrays.copyOfRange(content, from, to);
	}

	@Test
	void wholeFileIsByteExactAcrossBlocksAndWithANonAsciiName() throws Exception {
		assertArrayEquals(content, read(0, -1));
	}

	@Test
	void rangesSeekAndClampAtTheEnd() throws Exception {
		assertArrayEquals(slice(100, 200), read(100, 100));
		// A range spanning a block boundary.
		assertArrayEquals(
			slice(RemoteFiles.BLOCK_SIZE - 10, RemoteFiles.BLOCK_SIZE + 10),
			read(RemoteFiles.BLOCK_SIZE - 10, 20)
		);
		// Length beyond the end: what exists.
		assertArrayEquals(slice(content.length - 5, content.length), read(content.length - 5, 1000));
		// Past the end, and a zero length: empty, not an error.
		assertEquals(0, read(content.length + 10, -1).length);
		assertEquals(0, read(10, 0).length);
	}

	@Test
	void negativeOffsetsCountFromTheEnd() throws Exception {
		assertArrayEquals(slice(content.length - 8192, content.length), read(-8192, -1));
		assertArrayEquals(slice(content.length - 8192, content.length - 8192 + 1024), read(-8192, 1024));
		// Equal to, and larger than, the size: the whole file.
		assertArrayEquals(content, read(-content.length, -1));
		assertArrayEquals(content, read(-content.length - 1000L, -1));
	}

	@Test
	void emptyFile() throws Exception {
		final Path empty = Files.write(directory.resolve("empty.txt"), new byte[0]);
		final Outcome outcome = run(RemoteFiles.readScript(empty.toString(), 0, -1));
		assertEquals(0, outcome.exitCode);
		assertEquals(0, outcome.bytes.length);
		assertArrayEquals(probe(new byte[0]), run(RemoteFiles.probeScript(empty.toString())).bytes);
	}

	@Test
	void aFileOpenForWritingByAnotherProcessCanBeRead() throws Exception {
		final Path log = directory.resolve("service.log");
		try (FileOutputStream writer = new FileOutputStream(log.toFile())) {
			writer.write("line 1\r\n".getBytes(StandardCharsets.US_ASCII));
			writer.flush();
			assertArrayEquals(
				"line 1\r\n".getBytes(StandardCharsets.US_ASCII),
				run(RemoteFiles.readScript(log.toString(), 0, -1)).bytes
			);
			// The download probe too — certutil -hashfile fails on such a file with a sharing violation.
			assertArrayEquals(
				probe("line 1\r\n".getBytes(StandardCharsets.US_ASCII)),
				run(RemoteFiles.probeScript(log.toString())).bytes
			);
		}
	}

	/** The expected probe output: the size (8 bytes, little-endian), then the SHA-256 digest. */
	private static byte[] probe(final byte[] bytes) throws Exception {
		return ByteBuffer
			.allocate(RemoteFiles.PROBE_LENGTH)
			.order(ByteOrder.LITTLE_ENDIAN)
			.putLong(bytes.length)
			.put(MessageDigest.getInstance("SHA-256").digest(bytes))
			.array();
	}

	@Test
	void probeReportsTheSizeAndTheDigest() throws Exception {
		final Outcome outcome = run(RemoteFiles.probeScript(file.toString()));
		assertEquals(0, outcome.exitCode);
		assertArrayEquals(probe(content), outcome.bytes);
	}

	@Test
	void failuresMapToTheDocumentedExitCodes() throws Exception {
		assertEquals(
			RemoteFiles.EXIT_NOT_FOUND,
			run(RemoteFiles.readScript(directory.resolve("missing.txt").toString(), 0, -1)).exitCode
		);
		assertEquals(
			RemoteFiles.EXIT_NOT_FOUND,
			run(RemoteFiles.readScript(directory.resolve("no\\such\\dir.txt").toString(), 0, -1)).exitCode
		);
		assertEquals(RemoteFiles.EXIT_IS_DIRECTORY, run(RemoteFiles.readScript(directory.toString(), 0, -1)).exitCode);

		final Path locked = Files.write(directory.resolve("locked.bin"), new byte[] { 1, 2, 3 });
		try (RandomAccessFile raf = new RandomAccessFile(locked.toFile(), "rw"); FileLock lock = raf.getChannel().lock()) {
			assertEquals(RemoteFiles.EXIT_SHARING_VIOLATION, run(RemoteFiles.readScript(locked.toString(), 0, -1)).exitCode);
		}
	}

	@Test
	void digestMatchesTheLocalDigest() throws Exception {
		final Outcome outcome = run(RemoteFiles.digestScript(file.toString(), "SHA256"));
		assertEquals(0, outcome.exitCode);
		assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(content), outcome.bytes);
	}

	private static final Instant OLD = Instant.parse("2020-01-01T00:00:00.1234567Z");

	/**
	 * {@code tree\a.log} (10 bytes, 2020), {@code tree\b.txt} (2000 bytes),
	 * {@code tree\données-漢字 [x].log}, {@code tree\sub\c.log}, {@code tree\sub\deep\d.log},
	 * {@code tree\sub\deep\deeper\e.log}.
	 */
	private static Path tree() throws Exception {
		final Path tree = directory.resolve("tree");
		if (!Files.exists(tree)) {
			Files.createDirectories(tree.resolve("sub\\deep\\deeper"));
			Files.setLastModifiedTime(Files.write(tree.resolve("a.log"), new byte[10]), FileTime.from(OLD));
			Files.write(tree.resolve("b.txt"), new byte[2000]);
			Files.write(tree.resolve("données-漢字 [x].log"), new byte[1]);
			Files.write(tree.resolve("sub\\c.log"), new byte[5]);
			Files.write(tree.resolve("sub\\deep\\d.log"), new byte[5]);
			Files.write(tree.resolve("sub\\deep\\deeper\\e.log"), new byte[5]);
		}
		return tree;
	}

	private static Listing list(final Path path, final String glob, final int type, final int maxDepth) throws Exception {
		return exec(RemoteFiles.listScript(path.toString(), glob, type, 0, Long.MAX_VALUE, -1, Long.MAX_VALUE, maxDepth));
	}

	private static void cmd(final String... command) throws Exception {
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), output);
	}

	@Test
	void listingReportsEveryFieldAndOnlyTheDirectEntriesByDefault() throws Exception {
		final Listing listing = list(tree(), null, 0, 1);
		assertEquals(0, listing.exitCode);
		final Map<String, RemoteFileInfo> entries = listing.entries();
		assertEquals(Set.of("tree\\a.log", "tree\\b.txt", "tree\\données-漢字 [x].log", "tree\\sub"), entries.keySet());

		final RemoteFileInfo a = entries.get("tree\\a.log");
		assertEquals(10, a.size());
		assertEquals("a.log", a.name());
		assertFalse(a.isDirectory());
		// FileTime precision: 100 ns, exactly what was set.
		assertEquals(OLD, a.lastModified());
		assertTrue(a.created().isAfter(OLD));

		final RemoteFileInfo sub = entries.get("tree\\sub");
		assertTrue(sub.isDirectory());
		assertEquals(0, sub.size());
		assertEquals("données-漢字 [x].log", entries.get("tree\\données-漢字 [x].log").name());
		assertTrue(listing.inaccessible().isEmpty());
	}

	@Test
	void recursionHonorsTheMaximumDepth() throws Exception {
		final Set<String> depth2 = list(tree(), null, 0, 2).entries().keySet();
		assertTrue(depth2.contains("tree\\sub\\c.log") && depth2.contains("tree\\sub\\deep"), depth2.toString());
		assertFalse(depth2.contains("tree\\sub\\deep\\d.log"), depth2.toString());
		assertEquals(9, list(tree(), null, 0, Integer.MAX_VALUE).entries().size());
	}

	@Test
	void filtersAreEvaluatedOnTheHost() throws Exception {
		// Case-insensitive, whole-name glob; [ and ] are literal; directories are traversed anyway.
		assertEquals(
			Set.of(
				"tree\\a.log",
				"tree\\données-漢字 [x].log",
				"tree\\sub\\c.log",
				"tree\\sub\\deep\\d.log",
				"tree\\sub\\deep\\deeper\\e.log"
			),
			list(tree(), "*.LOG", 1, Integer.MAX_VALUE).entries().keySet()
		);
		assertEquals(Set.of("tree\\données-漢字 [x].log"), list(tree(), "*[x].log", 0, 1).entries().keySet());
		assertEquals(Set.of("tree\\a.log"), list(tree(), "?.log", 0, 1).entries().keySet());
		assertTrue(list(tree(), "*.lo", 0, 1).entries().isEmpty());
		assertEquals(
			Set.of("tree\\sub", "tree\\sub\\deep", "tree\\sub\\deep\\deeper"),
			list(tree(), null, 2, Integer.MAX_VALUE).entries().keySet()
		);

		final String path = tree().toString();
		// Size bounds: files only, directories pass.
		assertEquals(
			Set.of("tree\\b.txt", "tree\\sub"),
			exec(RemoteFiles.listScript(path, null, 0, 1000, Long.MAX_VALUE, -1, Long.MAX_VALUE, 1)).entries().keySet()
		);
		assertEquals(
			Set.of("tree\\données-漢字 [x].log", "tree\\sub"),
			exec(RemoteFiles.listScript(path, null, 0, 0, 5, -1, Long.MAX_VALUE, 1)).entries().keySet()
		);
		// Time bounds are exclusive, at the 100 ns FileTime precision.
		final long old = RemoteFiles.toFileTime(OLD);
		assertEquals(
			Set.of("tree\\a.log"),
			exec(RemoteFiles.listScript(path, null, 1, 0, Long.MAX_VALUE, old - 1, old + 1, 1)).entries().keySet()
		);
		assertTrue(
			exec(RemoteFiles.listScript(path, "a.log", 1, 0, Long.MAX_VALUE, old, Long.MAX_VALUE, 1)).entries().isEmpty()
		);
		assertTrue(exec(RemoteFiles.listScript(path, "a.log", 1, 0, Long.MAX_VALUE, -1, old, 1)).entries().isEmpty());
	}

	@Test
	void aJunctionLoopIsReportedButNotFollowed() throws Exception {
		final Path loop = Files.createDirectories(directory.resolve("loop"));
		Files.write(loop.resolve("f.txt"), new byte[1]);
		final Path junction = loop.resolve("back");
		cmd("cmd.exe", "/c", "mklink", "/J", junction.toString(), loop.toString());
		try {
			final Map<String, RemoteFileInfo> entries = list(loop, null, 0, Integer.MAX_VALUE).entries();
			assertEquals(Set.of("loop\\f.txt", "loop\\back"), entries.keySet());
			assertTrue(entries.get("loop\\back").isReparsePoint());
			assertTrue(entries.get("loop\\back").isDirectory());
			// Listing the junction itself, explicitly, lists its target's entries.
			assertEquals(2, list(junction, null, 0, Integer.MAX_VALUE).entries().size());
		} finally {
			// rmdir removes the junction, not its target (JUnit would otherwise walk the loop).
			cmd("cmd.exe", "/c", "rmdir", junction.toString());
		}
	}

	@Test
	void pathsLongerThanMaxPathAreNotTruncated() throws Exception {
		final Path deep = directory.resolve("long").resolve("a".repeat(100)).resolve("b".repeat(100));
		final Path longFile = Files.write(Files.createDirectories(deep).resolve("c".repeat(80) + ".txt"), new byte[42]);
		assertTrue(longFile.toString().length() > 300);

		final Listing listing = list(directory.resolve("long"), "c*", 1, Integer.MAX_VALUE);
		assertEquals(0, listing.exitCode, listing.lines::toString);
		final RemoteFileInfo entry = listing.entries().get(relative(longFile.toString()));
		assertEquals(42, entry.size());
		assertEquals(longFile.toString(), entry.path());

		final Listing info = exec(RemoteFiles.infoScript(longFile.toString()));
		assertEquals(longFile.toString(), RemoteFiles.parseEntry(info.lines.get(0)).path());
	}

	@Test
	void anInaccessibleSubdirectoryIsReportedAndTheWalkGoesOn() throws Exception {
		final Path root = Files.createDirectories(directory.resolve("partial"));
		final Path secret = Files.createDirectories(root.resolve("secret"));
		Files.write(secret.resolve("hidden.txt"), new byte[1]);
		Files.write(Files.createDirectories(root.resolve("zpublic")).resolve("open.txt"), new byte[1]);
		// Deny "list folder" to Everyone.
		cmd("icacls", secret.toString(), "/deny", "*S-1-1-0:(RD)");
		try {
			final Listing listing = list(root, null, 0, Integer.MAX_VALUE);
			assertEquals(0, listing.exitCode);
			assertEquals(
				Set.of("partial\\secret", "partial\\zpublic", "partial\\zpublic\\open.txt"),
				listing.entries().keySet()
			);
			assertEquals(List.of("partial\\secret"), listing.inaccessible());

			// Listing the inaccessible directory itself fails.
			assertEquals(RemoteFiles.EXIT_ACCESS_DENIED, list(secret, null, 0, 1).exitCode);
		} finally {
			cmd("icacls", secret.toString(), "/remove:d", "*S-1-1-0");
		}
	}

	@Test
	void infoReportsAFileOrADirectoryAndMissingPathsExitWithNotFound() throws Exception {
		final Listing fileInfo = exec(RemoteFiles.infoScript(file.toString()));
		assertEquals(0, fileInfo.exitCode);
		assertEquals(1, fileInfo.lines.size());
		final RemoteFileInfo info = RemoteFiles.parseEntry(fileInfo.lines.get(0));
		assertEquals(file.toString(), info.path());
		assertEquals(content.length, info.size());

		final RemoteFileInfo dir = RemoteFiles.parseEntry(exec(RemoteFiles.infoScript(directory.toString())).lines.get(0));
		assertTrue(dir.isDirectory());

		final Listing missing = exec(RemoteFiles.infoScript(directory.resolve("missing.txt").toString()));
		assertEquals(RemoteFiles.EXIT_NOT_FOUND, missing.exitCode);
		assertTrue(missing.lines.isEmpty());
		assertEquals(
			RemoteFiles.EXIT_NOT_FOUND,
			exec(RemoteFiles.infoScript(directory.resolve("no\\such\\dir").toString())).exitCode
		);

		assertEquals(RemoteFiles.EXIT_NOT_DIRECTORY, list(file, null, 0, 1).exitCode);
		assertEquals(RemoteFiles.EXIT_NOT_FOUND, list(directory.resolve("missing"), null, 0, 1).exitCode);
	}
}
