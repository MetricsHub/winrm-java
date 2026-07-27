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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;

/**
 * A command being prepared for execution, created by {@link WinRMClient#command(String)}.
 * Every option has a sensible default; {@link #execute()} runs the command and returns its
 * output and exit code.
 */
public final class CommandRequest {

	private final WinRMClient client;
	private final String commandLine;
	private String workingDirectory;
	private Duration timeout;
	private Charset charset;
	private final List<Path> uploads = new ArrayList<>();

	/**
	 * Create the request.
	 *
	 * @param client the client the command runs on
	 * @param commandLine the command line to execute
	 */
	CommandRequest(final WinRMClient client, final String commandLine) {
		Utils.checkNonBlank(commandLine, "commandLine");
		this.client = client;
		this.commandLine = commandLine;
		this.timeout = client.defaultTimeout();
	}

	/**
	 * Set the working directory of the remote process. The remote command shell is created on
	 * the first command a client executes and is reused afterward, so this setting takes effect
	 * only when this is the client's first command.
	 *
	 * @param workingDirectory the working directory path on the remote host
	 * @return this request
	 */
	public CommandRequest workingDirectory(final String workingDirectory) {
		Utils.checkNonBlank(workingDirectory, "workingDirectory");
		this.workingDirectory = workingDirectory;
		return this;
	}

	/**
	 * Set the timeout of this command — a wall-clock deadline covering file uploads, encoding
	 * detection, and the command itself. Default: the client's timeout.
	 *
	 * @param timeout the timeout (at least one millisecond)
	 * @return this request
	 */
	public CommandRequest timeout(final Duration timeout) {
		this.timeout = WinRMClient.checkPositive(timeout, "timeout");
		return this;
	}

	/**
	 * Set the charset used to decode the command output. Default: detected from the remote
	 * operating system's code set (one extra WQL query, cached on the client).
	 *
	 * @param charset the output charset
	 * @return this request
	 */
	public CommandRequest charset(final Charset charset) {
		Utils.checkNonNull(charset, "charset");
		this.charset = charset;
		return this;
	}

	/**
	 * Copy local files to the remote host (through the WinRM connection itself) before running
	 * the command. Each reference to a local file path inside the command line is rewritten to
	 * the remote copy, exactly like the legacy
	 * {@link org.metricshub.winrm.command.WinRMCommandExecutor}:
	 *
	 * <pre>{@code
	 * client.command("CSCRIPT c:\\scripts\\collect.vbs")
	 * 	.upload(Path.of("c:\\scripts\\collect.vbs"))
	 * 	.execute();
	 * }</pre>
	 *
	 * transfers the script and executes {@code CSCRIPT <remote copy>}.
	 *
	 * @param files the local files to copy
	 * @return this request
	 */
	public CommandRequest upload(final Path... files) {
		Utils.checkNonNull(files, "files");
		for (final Path file : files) {
			Utils.checkNonNull(file, "files");
			uploads.add(file);
		}
		return this;
	}

	/**
	 * Execute the command and collect its complete output.
	 *
	 * @return the command result: stdout, stderr, exit code, and execution time
	 * @throws org.metricshub.winrm.exceptions.WinRMTimeoutException when the timeout elapses first
	 * @throws org.metricshub.winrm.exceptions.WinRMAuthenticationException when the credentials are rejected
	 * @throws org.metricshub.winrm.exceptions.WinRMFaultException when the remote service answers with a WSMan fault
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public CommandResult execute() {
		final long start = Utils.getCurrentTimeMillis();
		final long timeoutMillis = WinRMClient.toMillis(timeout);
		try {
			String actualCommand = commandLine;
			String actualWorkingDirectory = workingDirectory;

			if (!uploads.isEmpty()) {
				// Copy the files through the command shell and rewrite the command to reference the
				// remote copies; the transfer commands create the shell, so the working directory no
				// longer applies (the shell already exists when the real command runs).
				final List<String> localFiles = uploads.stream().map(Path::toString).collect(Collectors.toList());
				final String updatedCommand = ShellFileCopy.copyLocalFilesToRemote(
					client.executor(),
					commandLine,
					localFiles,
					TimeoutHelper.getRemainingTime(timeoutMillis, start, "No time left to copy the local files")
				);
				actualCommand = String.format("CMD.EXE /C (%s)", updatedCommand);
				actualWorkingDirectory = null;
			}

			final Charset actualCharset = charset != null ? charset : client.detectCharset(timeoutMillis, start);

			final WindowsRemoteCommandResult result = client
				.executor()
				.executeCommand(
					actualCommand,
					actualWorkingDirectory,
					actualCharset,
					TimeoutHelper.getRemainingTime(timeoutMillis, start, "No time left to execute the command")
				);

			return new CommandResult(
				result.getStdout(),
				result.getStderr(),
				result.getStatusCode(),
				Duration.ofMillis(Utils.getCurrentTimeMillis() - start)
			);
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("Command timed out after %s on %s", timeout, client.hostname()),
				e
			);
		} catch (final IOException | WqlQuerySyntaxException e) {
			throw new WinRMClientException(e.getMessage(), e);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
	}
}
