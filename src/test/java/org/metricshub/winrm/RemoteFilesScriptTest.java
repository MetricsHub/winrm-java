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

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (final String line : stdout.split("\r?\n")) {
			if (!line.isBlank()) {
				bytes.write(Base64.getDecoder().decode(line.strip()));
			}
		}
		return new Outcome(bytes.toByteArray(), process.exitValue());
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
		}
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
}
