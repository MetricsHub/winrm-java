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
import java.util.regex.Pattern;
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
	 * Maximum length of a content-addressed remote file name: leaves ample room for the
	 * ".&lt;unique&gt;.part" and ".b64" staging suffixes under the NTFS 255-character
	 * path-component limit.
	 */
	private static final int MAX_REMOTE_NAME_LENGTH = 180;

	/** Maximum extension length preserved when a remote file name must be truncated. */
	private static final int MAX_EXTENSION_LENGTH = 30;

	/** The traditional Windows MAX_PATH limit (260 including the terminator) still enforced on old hosts. */
	private static final int MAX_WINDOWS_PATH_LENGTH = 259;

	/** Space reserved for the ".&lt;unique&gt;.part" and ".b64" staging suffixes (at most 30 characters). */
	private static final int STAGING_SUFFIX_BUDGET = 30;

	/**
	 * Transfer-directory entries not modified for this many days are purged before a transfer:
	 * content-addressing means every revision of a changing file gets a new remote name, and
	 * without a lifecycle the directory would grow without bound. Also reclaims staging or
	 * base64 files orphaned by an interrupted transfer. The rare downside: a cached script
	 * used unmodified for that long is re-uploaded once after the purge.
	 */
	private static final int CLEANUP_AGE_DAYS = 30;

	/**
	 * Digest algorithms in order of preference, as certutil spells them. SHA1 is only a
	 * fallback for old certutil versions without SHA256 support; the digest is a transfer
	 * integrity check on an already-encrypted channel, not a security control.
	 */
	private static final String[] CERTUTIL_ALGORITHMS = { "SHA256", "SHA1" };

	/** Characters that are forbidden in Windows file names (a non-Windows client may produce them locally). */
	private static final String WINDOWS_FORBIDDEN_CHARACTERS = "<>:\"/\\|?*";

	/** Windows reserved device names, with or without an extension (e.g. {@code CON}, {@code CON.ps1}). */
	private static final Pattern RESERVED_DEVICE_NAME = Pattern
		.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

	/** WSManFault code for "the maximum number of concurrent operations for this user has been exceeded". */
	private static final String FAULT_OPERATION_QUOTA = "2150859174";

	/** How many times a transfer command is retried after an operation-quota rejection. */
	private static final int QUOTA_RETRIES = 4;

	/**
	 * Base delay before retrying after an operation-quota rejection; each retry waits one step
	 * longer. Measured on Windows 2008 R2 (quota 15 per user): the budget fully recovers within
	 * 30 seconds, so the escalating delays (5+10+15+20&nbsp;s) comfortably bridge it.
	 */
	private static final long QUOTA_RETRY_DELAY_MILLIS = 5_000L;

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

		// One leg (through the local, quota-retrying runChecked): first purge entries older than
		// CLEANUP_AGE_DAYS (best-effort, stderr suppressed — reclaims obsolete content-addressed
		// revisions and orphaned staging files), then create the directory — last command, so the
		// leg's exit code is the MKDIR's.
		runChecked(
			windowsRemoteExecutor,
			String
				.format("forfiles /P \"%s\" /D -%d /C \"cmd /c del /f /q @path\" 2>NUL & ", remoteDirectory, CLEANUP_AGE_DAYS) +
				WindowsTempShare.buildCreateRemoteDirectoryCommand(remoteDirectory),
			"create the remote temporary directory",
			timeout,
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
		// Path.getFileName() is null for a root path such as "C:\" — which has no name to stage under
		final Path fileNamePath = localPath.getFileName();
		if (fileNamePath == null) {
			throw new IllegalArgumentException(
				String.format("Path %s has no file name and cannot be transferred to a Windows host.", localPath)
			);
		}
		final String fileName = fileNamePath.toString();
		checkTransferableFileName(fileName);

		final byte[] content = Files.readAllBytes(localPath);

		// Content-addressed remote name: same-named files with different content get different
		// remote paths, so concurrent clients — including clients whose computer names collide
		// in the shared temporary directory — can never overwrite each other's payload between
		// the digest verification and the command execution. The name budget accounts for the
		// actual directory prefix, so the COMPLETE staging path stays under MAX_PATH.
		final String remoteFile = remoteDirectory + "\\"
			+ contentAddressedName(fileName, content, maxRemoteNameLength(remoteDirectory));

		// Skip the transfer if the remote host already has an identical copy. A destination that
		// exists with a DIFFERENT digest (e.g. a cached copy corrupted or modified in place) is
		// remembered: it must be repaired by replacement, not trusted.
		final Optional<RemoteDigest> existing = remoteDigest(windowsRemoteExecutor, remoteFile, timeout, start);
		if (existing.isPresent() && existing.get().matches(content)) {
			return remoteFile;
		}
		final boolean mismatchedDestination = existing.isPresent();

		if (content.length == 0) {
			// Nothing to stage: create the empty file (truncating a mismatched pre-existing copy)
			// and verify its digest in the same command leg
			final WindowsRemoteCommandResult created = run(
				windowsRemoteExecutor,
				(mismatchedDestination
					? String.format("TYPE NUL >\"%s\"", remoteFile)
					: String.format("IF NOT EXIST \"%s\" TYPE NUL >\"%s\"", remoteFile, remoteFile)) +
					" & " +
					digestProbe(remoteFile),
				"create an empty file",
				timeout,
				start
			);

			// The destination itself must carry the expected digest — never return (and let the
			// caller execute) a file whose content wasn't proven. On failure the destination is
			// left in place: the next transfer detects the mismatch and repairs it.
			final Optional<RemoteDigest> published = parseAnyDigest(created.getStdout());
			if (!published.isPresent() || !published.get().matches(content)) {
				throw integrityCheckFailure(localPath, remoteFile, windowsRemoteExecutor);
			}

			return remoteFile;
		}

		// Upload and verify in an operation-unique staging file, then publish: the shared,
		// content-addressed destination is never rewritten once it carries the right content,
		// so a concurrent operation can never invalidate a copy another operation verified.
		final String stagingFile = String.format("%s.%s.part", remoteFile, uniqueSuffix());
		try {
			upload(windowsRemoteExecutor, content, stagingFile, localPath, timeout, start);

			publish(
				windowsRemoteExecutor,
				stagingFile,
				remoteFile,
				mismatchedDestination,
				content,
				localPath,
				timeout,
				start
			);
		} catch (final TimeoutException | WindowsRemoteException | RuntimeException e) {
			bestEffortDelete(windowsRemoteExecutor, timeout, start, stagingFile);

			throw e;
		}

		return remoteFile;
	}

	private static WindowsRemoteException integrityCheckFailure(
		final Path localPath,
		final String remoteFile,
		final WindowsRemoteExecutor windowsRemoteExecutor
	) {
		return new WindowsRemoteException(
			String.format(
				"Integrity check failed after copying %s to %s on %s.",
				localPath,
				remoteFile,
				windowsRemoteExecutor.getHostname()
			)
		);
	}

	/**
	 * Publish the verified staging file as the content-addressed destination and verify the
	 * destination digest in the same command leg. When the destination was seen with a
	 * mismatched digest, it is force-replaced (repair); otherwise an existing destination is
	 * left untouched — a concurrent operation already published the identical content — and the
	 * staging copy is discarded. The exit code is deliberately ignored: in a publish race the
	 * loser's {@code MOVE} may fail, and only the destination digest decides success. Never
	 * return (and let the caller execute) a file whose content wasn't proven; on failure the
	 * destination is left in place, so the next transfer detects the mismatch and repairs it.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param stagingFile The verified, operation-unique staging file
	 * @param remoteFile The content-addressed destination
	 * @param replaceMismatched Whether the destination pre-existed with a mismatched digest and
	 *        must be replaced
	 * @param content The expected file content
	 * @param localPath The local file, for the failure message
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException When the published destination does not carry the digest
	 *         of the local file
	 */
	private static void publish(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final String stagingFile,
		final String remoteFile,
		final boolean replaceMismatched,
		final byte[] content,
		final Path localPath,
		final long timeout,
		final long start
	) throws TimeoutException, WindowsRemoteException {
		final WindowsRemoteCommandResult result = run(
			windowsRemoteExecutor,
			(replaceMismatched
				? String.format("MOVE /Y \"%s\" \"%s\"", stagingFile, remoteFile)
				: String.format(
					"IF EXIST \"%2$s\" (DEL /F /Q \"%1$s\") ELSE (MOVE /Y \"%1$s\" \"%2$s\")",
					stagingFile,
					remoteFile
				)) +
				" & " +
				digestProbe(remoteFile),
			"publish the transferred file",
			timeout,
			start
		);

		final Optional<RemoteDigest> published = parseAnyDigest(result.getStdout());
		if (!published.isPresent() || !published.get().matches(content)) {
			throw integrityCheckFailure(localPath, remoteFile, windowsRemoteExecutor);
		}
	}

	/** Process-wide counter distinguishing concurrent staging files from the same JVM. */
	private static final java.util.concurrent.atomic.AtomicLong STAGING_COUNTER = new java.util.concurrent.atomic.AtomicLong();

	/** Random source for the cross-process part of the staging suffix (thread-safe). */
	private static final java.security.SecureRandom STAGING_RANDOM = new java.security.SecureRandom();

	/**
	 * Compact operation-unique suffix for staging file names: a process-wide counter makes
	 * same-JVM collisions impossible, and 64 random bits make cross-process collisions
	 * negligible — at most 20 characters, fitting the {@link #STAGING_SUFFIX_BUDGET}.
	 */
	private static String uniqueSuffix() {
		return String.format("%x-%016x", STAGING_COUNTER.incrementAndGet() & 0xFFF, STAGING_RANDOM.nextLong());
	}

	/**
	 * Transfer the file content to the remote (staging) path: chunked base64 {@code echo} legs,
	 * then a single leg that decodes with {@code certutil -decode}, removes the intermediate
	 * base64 file, and reports the digest of the decoded file — which is verified against the
	 * local content before returning.
	 *
	 * @param windowsRemoteExecutor Executor connected to the remote host
	 * @param content The file content
	 * @param remoteFile The target path on the remote host
	 * @param localPath The local file, for the failure message
	 * @param timeout Timeout in milliseconds
	 * @param start Operation start time in milliseconds
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException For any problem encountered on the remote host
	 */
	private static void upload(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final byte[] content,
		final String remoteFile,
		final Path localPath,
		final long timeout,
		final long start
	) throws TimeoutException, WindowsRemoteException {
		// The target is already operation-unique (staging), so the base64 sidecar is too
		final String base64File = remoteFile + ".b64";

		try {
			for (final String uploadCommand : buildUploadCommands(
				Base64.getEncoder().encodeToString(content),
				base64File
			)) {
				runChecked(windowsRemoteExecutor, uploadCommand, "upload the file content", timeout, start);
			}

			final WindowsRemoteCommandResult decoded = run(
				windowsRemoteExecutor,
				String.format("certutil -f -decode \"%s\" \"%s\" && DEL /F /Q \"%s\"", base64File, remoteFile, base64File) +
					" & " +
					digestProbe(remoteFile),
				"decode the transferred file",
				timeout,
				start
			);

			final Optional<RemoteDigest> staged = parseAnyDigest(decoded.getStdout());
			if (!staged.isPresent()) {
				throw new WindowsRemoteException(
					String.format(
						"Failed to decode the transferred file %s on %s: %s",
						remoteFile,
						windowsRemoteExecutor.getHostname(),
						Utils.isNotBlank(decoded.getStderr()) ? decoded.getStderr().trim() : decoded.getStdout().trim()
					)
				);
			}
			if (!staged.get().matches(content)) {
				throw integrityCheckFailure(localPath, remoteFile, windowsRemoteExecutor);
			}
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
	 * Get the digest of a remote file: a single command leg runs {@code certutil -hashfile} for
	 * every supported algorithm (one round trip; old certutil versions without SHA256 simply
	 * fail that part), and the output is parsed by algorithm preference.
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
		final WindowsRemoteCommandResult result = run(
			windowsRemoteExecutor,
			digestProbe(remoteFile),
			"hash the remote file",
			timeout,
			start
		);

		return parseAnyDigest(result.getStdout());
	}

	/**
	 * Build the command that prints the digest of the given remote file with every supported
	 * algorithm in one go. Appending the probe (with {@code " & "}) to a transfer command saves
	 * a WinRM operation per step, which both speeds the transfer up and relieves the
	 * server-side concurrent-operation quota that old Windows versions set very low (15 per
	 * user on Windows 2008 R2).
	 *
	 * @param remoteFile The remote file to hash
	 * @return the digest-probing command
	 */
	static String digestProbe(final String remoteFile) {
		final StringBuilder probe = new StringBuilder();
		for (final String algorithm : CERTUTIL_ALGORITHMS) {
			if (probe.length() > 0) {
				probe.append(" & ");
			}
			probe.append(String.format("certutil -hashfile \"%s\" %s", remoteFile, algorithm));
		}

		return probe.toString();
	}

	/**
	 * Extract the first digest found in a {@code certutil -hashfile} output, trying each
	 * supported algorithm in preference order.
	 *
	 * @param output The command standard output
	 * @return the digest, or an empty Optional if none was found
	 */
	static Optional<RemoteDigest> parseAnyDigest(final String output) {
		for (final String algorithm : CERTUTIL_ALGORITHMS) {
			final Optional<String> digest = parseCertutilDigest(output, algorithm);
			if (digest.isPresent()) {
				return Optional.of(new RemoteDigest(algorithm, digest.get()));
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
		for (int attempt = 0;; attempt++) {
			try {
				return windowsRemoteExecutor.executeCommand(
					command,
					null,
					null,
					TimeoutHelper.getRemainingTime(timeout, start, "No time left to " + description)
				);
			} catch (final WindowsRemoteException e) {
				if (attempt >= QUOTA_RETRIES || !isRetryableQuotaRejection(e)) {
					throw e;
				}

				// The quota rejection happened while the operation was being CREATED — before the
				// command could run — so retrying cannot duplicate a side effect. Old Windows
				// versions cap concurrent operations very low (15 per user on 2008 R2) and reap
				// completed ones lazily: give the server increasingly more time to recover.
				try {
					Utils.sleep(
						Math.min(
							QUOTA_RETRY_DELAY_MILLIS * (attempt + 1),
							TimeoutHelper.getRemainingTime(timeout, start, "No time left to retry after a quota rejection")
						)
					);
				} catch (final InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	/**
	 * Whether the exception is a server-side operation-quota rejection that occurred while the
	 * operation was being created (shell creation or command start), i.e. before the command
	 * could produce any side effect — the only situation where a retry is safe. A quota fault
	 * on a later protocol step (e.g. Receive) means the command may already be running and is
	 * never retried.
	 *
	 * @param exception The exception reported by the executor
	 * @return whether the failed command can safely be retried
	 */
	static boolean isRetryableQuotaRejection(final Exception exception) {
		final String message = exception.getMessage();

		return message != null
			&&
			message.contains(FAULT_OPERATION_QUOTA)
			&&
			(message.contains("Command failed") || message.contains("Create shell failed"));
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
	 * Build the remote name of a transferred file: the local file name with a fragment of the
	 * content digest inserted before the extension (e.g. {@code script.1a2b3c4d5e6f.vbs}), so
	 * the remote path identifies both the name and the content of the file.
	 *
	 * @param fileName The local file name
	 * @param content The file content
	 * @return the content-addressed remote file name
	 */
	static String contentAddressedName(final String fileName, final byte[] content) {
		return contentAddressedName(fileName, content, MAX_REMOTE_NAME_LENGTH);
	}

	/**
	 * Same as {@link #contentAddressedName(String, byte[])} with an explicit length bound,
	 * derived by the caller from the length of the directory the file goes to, so the complete
	 * path (staging suffixes included) honors the traditional Windows MAX_PATH limit.
	 *
	 * @param fileName The local file name
	 * @param content The file content
	 * @param maxLength Maximum length of the generated name
	 * @return the content-addressed remote file name
	 */
	static String contentAddressedName(final String fileName, final byte[] content, final int maxLength) {
		final int dot = fileName.lastIndexOf('.');
		String base = dot > 0 ? fileName.substring(0, dot) : fileName;
		String extension = dot > 0 ? fileName.substring(dot) : Utils.EMPTY;

		final String digest = digestHex("SHA-256", content).substring(0, 12);

		// Bound the name so that even with the ".<unique>.part.b64" staging suffixes the remote
		// path component stays well below the NTFS 255-character limit. Truncating never causes
		// collisions: the digest fragment keeps the name unique per content.
		extension = truncateAtCodePoint(extension, MAX_EXTENSION_LENGTH);

		final int maxBaseLength = Math.max(1, maxLength - digest.length() - 1 - extension.length());
		base = truncateAtCodePoint(base, maxBaseLength);

		return base + "." + digest + extension;
	}

	/**
	 * Truncate a string on a Unicode code-point boundary: cutting between the two UTF-16 chars
	 * of a surrogate pair (e.g. in the middle of an emoji) would leave a malformed character
	 * that turns into {@code ?} — illegal, and a wildcard — when the command is UTF-8 encoded.
	 *
	 * @param value The string to truncate
	 * @param maxLength Maximum length, in UTF-16 chars
	 * @return the truncated string
	 */
	private static String truncateAtCodePoint(final String value, final int maxLength) {
		if (value.length() <= maxLength) {
			return value;
		}

		return value.substring(0, Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength);
	}

	/**
	 * Maximum length of a content-addressed name in the given remote directory: the component
	 * bound, further reduced so that the COMPLETE path of the longest transfer artifact
	 * (destination + "." + unique suffix + ".part" + ".b64") stays within the traditional
	 * Windows MAX_PATH limit that old hosts still enforce.
	 *
	 * @param remoteDirectory The directory receiving the transferred files
	 * @return the maximum name length
	 */
	static int maxRemoteNameLength(final String remoteDirectory) {
		return Math.min(
			MAX_REMOTE_NAME_LENGTH,
			MAX_WINDOWS_PATH_LENGTH - remoteDirectory.length() - 1 - STAGING_SUFFIX_BUDGET
		);
	}

	/**
	 * Compute the hexadecimal digest of the given content.
	 *
	 * @param algorithm The {@link MessageDigest} algorithm name
	 * @param content The content to hash
	 * @return the lowercase hexadecimal digest
	 */
	static String digestHex(final String algorithm, final byte[] content) {
		try {
			final StringBuilder hex = new StringBuilder();
			for (final byte b : MessageDigest.getInstance(algorithm).digest(content)) {
				hex.append(String.format("%02x", b));
			}

			return hex.toString();
		} catch (final NoSuchAlgorithmException e) {
			// Cannot happen: every JVM is required to provide SHA-1 and SHA-256
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Reject file names that cannot be transferred: names that cannot be embedded safely in a
	 * quoted cmd.exe argument ({@code %} expands as a variable reference even between quotes,
	 * {@code !} does too on hosts with delayed expansion enabled,
	 * {@code "} and control characters break the quoting), and names Windows cannot create —
	 * relevant when the client runs on an OS whose local file names may legally contain
	 * Windows-forbidden characters ({@code < > : " / \ | ? *}), end with a dot or a space, or
	 * collide with a reserved device name ({@code CON}, {@code NUL}, {@code COM1}…, with or
	 * without an extension).
	 *
	 * @param fileName The name of the file to transfer
	 */
	static void checkTransferableFileName(final String fileName) {
		if (fileName.isEmpty()
			||
			fileName.contains("%")
			||
			fileName.contains("!")
			||
			fileName.chars().anyMatch(c -> c < 0x20 || WINDOWS_FORBIDDEN_CHARACTERS.indexOf(c) >= 0)
			||
			fileName.endsWith(".")
			||
			fileName.endsWith(" ")
			||
			RESERVED_DEVICE_NAME.matcher(fileName).matches()) {
			throw new IllegalArgumentException(
				String.format("File name %s cannot be transferred to a Windows host safely.", fileName)
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
		// PMD does not resolve the call through Optional<RemoteDigest>#get() and reports this as unused.
		@SuppressWarnings("PMD.UnusedPrivateMethod")
		private boolean matches(final byte[] content) {
			return digestHex("SHA256".equals(algorithm) ? "SHA-256" : "SHA-1", content).equals(digest);
		}
	}
}
