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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;

/**
 * Copies local files to the remote host through the WinRM command shell itself, without SMB.
 * <p>
 * Each file is base64-encoded locally, appended to a remote temporary file with chunked
 * {@code echo} commands, decoded with {@code certutil -decode}, and verified by comparing a
 * remote {@code certutil -hashfile} digest with the locally computed one. The transfer rides
 * the already-authenticated (and, over HTTP, encrypted) WinRM channel: no extra TCP port,
 * no separate authentication, no dependency.
 * <p>
 * A file that is already present on the remote host with an identical digest is not
 * transferred again, so repeatedly executing the same script is cheap.
 * <p>
 * This transport is intended for the small script files that
 * {@link org.metricshub.winrm.command.WinRMCommandExecutor} copies before execution; base64
 * over SOAP is not suited to bulk data.
 */
public class ShellFileCopy {

	private ShellFileCopy() {}

	/**
	 * cmd.exe rejects command lines longer than 8191 characters; stay well under it, the
	 * redirection targets count toward the limit. The transfer commands are sent bare (no
	 * {@code CMD.EXE /C (...)} wrapper): the WinRM shell already runs each command line
	 * through cmd.exe, and a second nesting level mangles quoted redirection chains.
	 */
	private static final int MAX_COMMAND_LENGTH = 8000;

	/** PEM-style base64 line length, accepted by every certutil version. */
	private static final int BASE64_LINE_LENGTH = 76;

	/**
	 * Digest algorithms in order of preference, as certutil spells them. SHA1 is only a
	 * fallback for old certutil versions without SHA256 support; the digest is a transfer
	 * integrity check on an already-encrypted channel, not a security control.
	 */
	private static final String[] CERTUTIL_ALGORITHMS = { "SHA256", "SHA1" };

	/**
	 * Copy the specified local files to a temporary directory on the remote host through the
	 * WinRM command shell, and return the command updated so that each reference to a local
	 * file path points to the corresponding remote copy.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host (mandatory)
	 * @param command The command referencing the local files (mandatory)
	 * @param localFiles The list of local files to copy (may be null or empty: no-op)
	 * @param timeout Timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @return The command updated with the remote paths of the copied files
	 * @throws IOException If a local file cannot be read
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException For any problem encountered on the remote host
	 */
	public static String copyLocalFilesToRemote(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final String command,
		final List<String> localFiles,
		final long timeout
	) throws IOException, TimeoutException, WindowsRemoteException {
		Utils.checkNonNull(windowsRemoteExecutor, "windowsRemoteExecutor");
		Utils.checkNonNull(command, "command");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		if (localFiles == null || localFiles.isEmpty()) {
			return command;
		}

		final long start = Utils.getCurrentTimeMillis();

		final String windowsDirectory = WindowsTempShare.getWindowsDirectory(
			windowsRemoteExecutor,
			TimeoutHelper.getRemainingTime(timeout, start, "No time left to locate the remote Windows directory")
		);

		final String remoteDirectory = WindowsTempShare.buildRemotePath(
			windowsDirectory,
			WindowsTempShare.buildShareName()
		);

		WindowsTempShare.createRemoteDirectory(
			windowsRemoteExecutor,
			remoteDirectory,
			TimeoutHelper.getRemainingTime(timeout, start, "No time left to create the remote temporary directory"),
			start
		);

		String updatedCommand = command;
		for (final String localFile : localFiles) {
			final String remoteFile = copyFile(windowsRemoteExecutor, Paths.get(localFile), remoteDirectory, timeout, start);

			updatedCommand = WindowsRemoteProcessUtils.caseInsensitiveReplace(updatedCommand, localFile, remoteFile);
		}

		return updatedCommand;
	}

	/**
	 * Copy one local file to the remote directory, skipping the transfer when an identical
	 * copy is already present.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param localPath The local file to copy
	 * @param remoteDirectory The existing remote directory receiving the file
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @return the path of the file on the remote host
	 * @throws IOException If the local file cannot be read
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException For any problem encountered on the remote host
	 */
	static String copyFile(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final Path localPath,
		final String remoteDirectory,
		final long timeout,
		final long start
	) throws IOException, TimeoutException, WindowsRemoteException {
		final String fileName = localPath.getFileName().toString();
		checkTransferableFileName(fileName);

		final String remoteFile = remoteDirectory + "\\" + fileName;
		final byte[] content = Files.readAllBytes(localPath);

		// Skip the transfer if the remote host already has an identical copy
		final Optional<RemoteDigest> existing = remoteDigest(windowsRemoteExecutor, remoteFile, timeout, start);
		if (existing.isPresent() && existing.get().matches(content)) {
			return remoteFile;
		}

		upload(windowsRemoteExecutor, content, remoteFile, timeout, start);

		final Optional<RemoteDigest> uploaded = remoteDigest(windowsRemoteExecutor, remoteFile, timeout, start);
		if (!uploaded.isPresent() || !uploaded.get().matches(content)) {
			bestEffortDelete(windowsRemoteExecutor, timeout, start, remoteFile);

			throw new WindowsRemoteException(
				String.format(
					"Integrity check failed after copying %s to %s on %s.",
					localPath,
					remoteFile,
					windowsRemoteExecutor.getHostname()
				)
			);
		}

		return remoteFile;
	}

	/**
	 * Transfer the file content to the remote path: chunked base64 {@code echo} legs, then a
	 * single {@code certutil -decode} that also removes the intermediate base64 file.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param content The file content
	 * @param remoteFile The target path on the remote host
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException For any problem encountered on the remote host
	 */
	private static void upload(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final byte[] content,
		final String remoteFile,
		final long timeout,
		final long start
	) throws TimeoutException, WindowsRemoteException {
		if (content.length == 0) {
			runChecked(
				windowsRemoteExecutor,
				String.format("TYPE NUL >\"%s\"", remoteFile),
				"create an empty file",
				timeout,
				start
			);

			return;
		}

		final String base64File = String.format(
			"%s.%s.b64",
			remoteFile,
			WindowsRemoteProcessUtils.buildNewOutputFileName()
		);

		try {
			for (final String uploadCommand : buildUploadCommands(
				Base64.getEncoder().encodeToString(content),
				base64File
			)) {
				runChecked(windowsRemoteExecutor, uploadCommand, "upload the file content", timeout, start);
			}

			runChecked(
				windowsRemoteExecutor,
				String.format("certutil -f -decode \"%s\" \"%s\" && DEL /F /Q \"%s\"", base64File, remoteFile, base64File),
				"decode the transferred file",
				timeout,
				start
			);
		} catch (final TimeoutException | WindowsRemoteException | RuntimeException e) {
			bestEffortDelete(windowsRemoteExecutor, timeout, start, base64File, remoteFile);

			throw e;
		}
	}

	/**
	 * Split the base64 payload into PEM-length lines and group them into as few
	 * {@code CMD.EXE /C} command legs as possible, each below the cmd.exe line-length limit.
	 * The first {@code echo} of the first leg truncates the target file, all others append.
	 *
	 * @param base64 The base64-encoded file content (non-empty)
	 * @param base64File The remote path of the intermediate base64 file
	 * @return the list of commands to execute in order
	 */
	static List<String> buildUploadCommands(final String base64, final String base64File) {
		final List<String> commands = new ArrayList<>();

		StringBuilder leg = null;
		for (int position = 0; position < base64.length(); position += BASE64_LINE_LENGTH) {
			final String line = base64.substring(position, Math.min(position + BASE64_LINE_LENGTH, base64.length()));

			// ">" (truncate) for the very first line of the file, ">>" (append) afterward
			final String piece = String.format("%s\"%s\" echo %s", position == 0 ? ">" : ">>", base64File, line);

			if (leg == null) {
				leg = new StringBuilder(piece);
			} else if (leg.length() + piece.length() + 2 <= MAX_COMMAND_LENGTH) {
				leg.append("& ").append(piece);
			} else {
				commands.add(leg.toString());
				leg = new StringBuilder(piece);
			}
		}
		commands.add(leg.toString());

		return commands;
	}

	/**
	 * Get the digest of a remote file with {@code certutil -hashfile}, trying each supported
	 * algorithm in order.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param remoteFile The remote file to hash
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @return the digest of the remote file, or an empty Optional if it couldn't be computed
	 *         (typically because the file doesn't exist)
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException For any problem encountered on the remote host
	 */
	private static Optional<RemoteDigest> remoteDigest(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final String remoteFile,
		final long timeout,
		final long start
	) throws TimeoutException, WindowsRemoteException {
		for (final String algorithm : CERTUTIL_ALGORITHMS) {
			final WindowsRemoteCommandResult result = run(
				windowsRemoteExecutor,
				String.format("certutil -hashfile \"%s\" %s", remoteFile, algorithm),
				"hash the remote file",
				timeout,
				start
			);

			if (result.getStatusCode() == 0) {
				final Optional<String> digest = parseCertutilDigest(result.getStdout(), algorithm);
				if (digest.isPresent()) {
					return Optional.of(new RemoteDigest(algorithm, digest.get()));
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * Extract the digest from a {@code certutil -hashfile} output: the line that is nothing
	 * but hexadecimal digits of the expected length, ignoring the spaces older certutil
	 * versions insert between bytes.
	 *
	 * @param output The certutil standard output
	 * @param algorithm The certutil algorithm name the output was produced with
	 * @return the lowercase digest, or an empty Optional if none was found
	 */
	static Optional<String> parseCertutilDigest(final String output, final String algorithm) {
		final int expectedLength = "SHA256".equals(algorithm) ? 64 : 40;

		return output == null
			? Optional.empty()
			: output
				.lines()
				.map(line -> line.replaceAll("\\s", Utils.EMPTY).toLowerCase(Locale.ROOT))
				.filter(line -> line.length() == expectedLength && line.matches("[0-9a-f]+"))
				.findFirst();
	}

	/**
	 * Execute a transfer command and fail if its exit code is not zero.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param command The command to execute
	 * @param description What the command does, for the timeout and failure messages
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException When the command fails or reports a non-zero exit code
	 */
	private static void runChecked(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final String command,
		final String description,
		final long timeout,
		final long start
	) throws TimeoutException, WindowsRemoteException {
		final WindowsRemoteCommandResult result = run(windowsRemoteExecutor, command, description, timeout, start);

		if (result.getStatusCode() != 0) {
			throw new WindowsRemoteException(
				String.format(
					"Failed to %s on %s (exit code %d): %s",
					description,
					windowsRemoteExecutor.getHostname(),
					result.getStatusCode(),
					Utils.isNotBlank(result.getStderr()) ? result.getStderr().trim() : result.getStdout().trim()
				)
			);
		}
	}

	private static WindowsRemoteCommandResult run(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final String command,
		final String description,
		final long timeout,
		final long start
	) throws TimeoutException, WindowsRemoteException {
		return windowsRemoteExecutor.executeCommand(
			command,
			null,
			null,
			TimeoutHelper.getRemainingTime(timeout, start, "No time left to " + description)
		);
	}

	/**
	 * Delete remote files, ignoring any failure: used to clean up after a failed transfer,
	 * where the original exception must not be masked.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @param remoteFiles The remote files to delete
	 */
	private static void bestEffortDelete(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final long timeout,
		final long start,
		final String... remoteFiles
	) {
		final StringBuilder files = new StringBuilder();
		for (final String remoteFile : remoteFiles) {
			files.append(String.format(" \"%s\"", remoteFile));
		}

		try {
			run(windowsRemoteExecutor, "DEL /F /Q" + files, "clean up", timeout, start);
		} catch (final Exception ignored) {
			// Cleanup is best-effort: the exception that triggered it matters more
		}
	}

	/**
	 * Reject file names that cannot be embedded safely in a quoted cmd.exe argument.
	 * Windows already forbids most cmd metacharacters in file names; the remaining dangerous
	 * one is {@code %}, which cmd.exe expands as a variable reference even between quotes.
	 *
	 * @param fileName The name of the file to transfer
	 */
	static void checkTransferableFileName(final String fileName) {
		if (fileName.contains("%") || fileName.contains("\"") || fileName.chars().anyMatch(c -> c < 0x20)) {
			throw new IllegalArgumentException(
				String.format("File name %s contains characters that cannot be transferred safely.", fileName)
			);
		}
	}

	/** The digest of a remote file, with the certutil algorithm that produced it. */
	private static final class RemoteDigest {

		private final String algorithm;
		private final String digest;

		private RemoteDigest(final String algorithm, final String digest) {
			this.algorithm = algorithm;
			this.digest = digest;
		}

		/** Whether this remote digest matches the digest of the given local content. */
		private boolean matches(final byte[] content) {
			try {
				final MessageDigest messageDigest = MessageDigest.getInstance(
					"SHA256".equals(algorithm) ? "SHA-256" : "SHA-1"
				);

				final StringBuilder hex = new StringBuilder();
				for (final byte b : messageDigest.digest(content)) {
					hex.append(String.format("%02x", b));
				}

				return hex.toString().equals(digest);
			} catch (final NoSuchAlgorithmException e) {
				// Cannot happen: every JVM is required to provide SHA-1 and SHA-256
				throw new IllegalStateException(e);
			}
		}
	}
}
