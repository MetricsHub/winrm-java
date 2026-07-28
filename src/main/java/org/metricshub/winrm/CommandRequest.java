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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;

/**
 * A command being prepared for execution, created by {@link WinRMClient#command(String)}.
 * Every option has a sensible default; {@link #execute()} runs the command and returns its
 * output and exit code, {@link #start()} returns a {@link RemoteProcess} whose output can be
 * consumed while the command is still running.
 */
public final class CommandRequest {

	/** How many bytes of pre-supplied input are read and sent at a time. */
	private static final int STDIN_BUFFER_SIZE = 64 * 1024;

	private final WinRMClient client;
	private final String commandLine;
	private String workingDirectory;
	private Duration timeout;
	private Charset charset;
	private final List<Path> uploads = new ArrayList<>();
	private Consumer<String> stdoutConsumer;
	private Consumer<String> stderrConsumer;
	private StdinSource stdinSource;
	private boolean pipeStdin;

	/** A deferred source of pre-supplied standard input, opened when the command starts. */
	@FunctionalInterface
	private interface StdinSource {
		InputStream open(Charset charset) throws IOException;
	}

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
	 * Set the timeout of this command. For {@link #execute()} it is a wall-clock deadline covering
	 * file uploads and the command itself; for {@link #start()} it is an
	 * <i>inactivity</i> timeout — the longest silence tolerated from the server between two
	 * responses, with no overall deadline. Default: the client's timeout.
	 *
	 * @param timeout the timeout (at least one millisecond)
	 * @return this request
	 */
	public CommandRequest timeout(final Duration timeout) {
		this.timeout = WinRMClient.checkPositive(timeout, "timeout");
		return this;
	}

	/**
	 * Set the charset used to decode the command output. Default:
	 * {@link WindowsRemoteExecutor#SHELL_OUTPUT_CHARSET} (UTF-8), which is what the remote shell
	 * emits — its console code page is set to 65001 when the shell is created, whatever the remote
	 * locale. Override this only for a command that changes the console code page itself (a leading
	 * {@code chcp}) or writes raw bytes in another encoding to its standard output.
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
	 * Feed the given text to the command's standard input. The text is encoded with the same
	 * charset used to decode the output (see {@link #charset(Charset)}) and delivered in full —
	 * split into protocol-sized chunks when large — right after the command starts, ending with
	 * the end-of-input mark so the remote stdin reaches EOF.
	 * <p>
	 * Supplying input switches the remote stdin to <b>pipe semantics</b>
	 * ({@code WINRS_CONSOLEMODE_STDIN=FALSE}): tools like {@code sort} or {@code findstr} consume
	 * it and terminate on EOF, exactly as with a local {@code <} redirection.
	 *
	 * <pre>{@code
	 * CommandResult result = client.command("sort").stdin("beta\nalpha\n").execute();
	 * }</pre>
	 *
	 * Works with both {@link #execute()} and {@link #start()}; with {@code start()}, the
	 * {@link RemoteProcess#stdin()} writer is closed from the start — pre-supplied and interactive
	 * input are mutually exclusive. The input is delivered before any output is read: feeding a
	 * large input to a command that floods its output in the meantime can deadlock both sides,
	 * exactly like the {@link java.lang.Process} pipes — the caller's to avoid.
	 *
	 * @param text the input text
	 * @return this request
	 */
	public CommandRequest stdin(final String text) {
		Utils.checkNonNull(text, "text");
		this.stdinSource = charset -> new ByteArrayInputStream(text.getBytes(charset));
		this.pipeStdin = true;
		return this;
	}

	/**
	 * Declare that the command will be fed standard input <b>interactively</b>, through
	 * {@link RemoteProcess#stdin()}, without pre-supplying it: the remote stdin switches to
	 * <b>pipe semantics</b> ({@code WINRS_CONSOLEMODE_STDIN=FALSE}), so closing the writer
	 * actually delivers EOF — a console-mode stdin never reaches EOF for tools like {@code sort}
	 * or {@code findstr}.
	 *
	 * <pre>{@code
	 * try (RemoteProcess p = client.command("sort").stdin().start()) {
	 * 	try (BufferedWriter in = p.stdin()) {
	 * 		in.write("beta\nalpha\n");
	 * 	} // EOF: sort now sorts and exits
	 * 	p.waitFor();
	 * }
	 * }</pre>
	 *
	 * Only meaningful with {@link #start()} — {@link #execute()} cannot take interactive input and
	 * rejects a request configured this way. Without any {@code stdin} call, the historical
	 * console semantics are kept (what a command that never reads input, and the interactive CLI
	 * shell, want).
	 *
	 * @return this request
	 */
	public CommandRequest stdin() {
		this.pipeStdin = true;
		return this;
	}

	/**
	 * Feed the content of the given local file to the command's standard input — the equivalent of
	 * a local {@code < file} redirection. Same contract as {@link #stdin(String)}, except the bytes
	 * are sent exactly as stored (no re-encoding).
	 *
	 * @param file the local file to read
	 * @return this request
	 */
	public CommandRequest stdin(final Path file) {
		Utils.checkNonNull(file, "file");
		this.stdinSource = charset -> Files.newInputStream(file);
		this.pipeStdin = true;
		return this;
	}

	/**
	 * Feed the given stream to the command's standard input, reading it to its end. Same contract
	 * as {@link #stdin(String)}, except the bytes are sent exactly as read (no re-encoding). The
	 * stream is consumed when the command starts and closed afterward; it is read on the thread
	 * driving the execution, so a stream that blocks forever stalls the command.
	 *
	 * @param stream the input stream to consume
	 * @return this request
	 */
	public CommandRequest stdin(final InputStream stream) {
		Utils.checkNonNull(stream, "stream");
		this.stdinSource = charset -> stream;
		this.pipeStdin = true;
		return this;
	}

	/**
	 * Register a callback receiving each chunk of standard output as it arrives, while
	 * {@link #execute()} is still running — a middle ground between collecting everything and
	 * managing a {@link RemoteProcess}: tail the output live, but keep the blocking terminal and
	 * its complete {@link CommandResult}.
	 *
	 * <pre>{@code
	 * client.command("longRunningThing.exe")
	 * 	.onStdout(chunk -> log.info(chunk))
	 * 	.onStderr(chunk -> log.warn(chunk))
	 * 	.execute();
	 * }</pre>
	 *
	 * The callback is invoked on an internal worker thread (never concurrently), with output
	 * decoded incrementally: a chunk is a run of characters as the server delivered them, not
	 * necessarily whole lines.
	 *
	 * @param consumer the standard output consumer
	 * @return this request
	 */
	public CommandRequest onStdout(final Consumer<String> consumer) {
		Utils.checkNonNull(consumer, "consumer");
		this.stdoutConsumer = consumer;
		return this;
	}

	/**
	 * Register a callback receiving each chunk of standard error as it arrives, while
	 * {@link #execute()} is still running. Same contract as {@link #onStdout(Consumer)}.
	 *
	 * @param consumer the standard error consumer
	 * @return this request
	 */
	public CommandRequest onStderr(final Consumer<String> consumer) {
		Utils.checkNonNull(consumer, "consumer");
		this.stderrConsumer = consumer;
		return this;
	}

	/**
	 * Execute the command and collect its complete output. When {@link #onStdout(Consumer)} or
	 * {@link #onStderr(Consumer)} callbacks are registered, they additionally receive the output
	 * chunk by chunk while the command runs; the returned result is complete either way.
	 *
	 * @return the command result: stdout, stderr, exit code, and execution time
	 * @throws org.metricshub.winrm.exceptions.WinRMTimeoutException when the timeout elapses first
	 * @throws org.metricshub.winrm.exceptions.WinRMAuthenticationException when the credentials are rejected
	 * @throws org.metricshub.winrm.exceptions.WinRMFaultException when the remote service answers with a WSMan fault
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public CommandResult execute() {
		if (pipeStdin && stdinSource == null) {
			// stdin() without content promises interactive input, which only start() can take: a
			// command waiting on its never-fed pipe would hang for a whole timeout.
			throw new IllegalStateException(
				"stdin() without content declares interactive input: use start(), or supply the input to stdin(...)."
			);
		}
		final long start = Utils.getCurrentTimeMillis();
		final long timeoutMillis = WinRMClient.toMillis(timeout);
		try {
			final Prepared prepared = prepare(timeoutMillis, start);
			final long remaining = TimeoutHelper.getRemainingTime(
				timeoutMillis,
				start,
				"No time left to execute the command"
			);

			if (stdoutConsumer == null && stderrConsumer == null && stdinSource == null) {
				final WindowsRemoteCommandResult result = client
					.executor()
					.executeCommand(prepared.command, prepared.workingDirectory, prepared.charset, remaining);

				return new CommandResult(
					result.getStdout(),
					result.getStderr(),
					result.getStatusCode(),
					Duration.ofMillis(Utils.getCurrentTimeMillis() - start)
				);
			}

			// Callback/stdin variant: drain the streaming cursor, delivering each chunk as it
			// arrives (input, when supplied, is fed right after startup). The same wall-clock
			// deadline governs, enforced the way the blocking path enforces it — a worker runs the
			// exchange and is cancelled when the deadline fires.
			return Utils.execute(() -> drainWithCallbacks(prepared, remaining, start), remaining);
		} catch (final TimeoutException e) {
			throw timeoutException(e);
		} catch (final IOException e) {
			throw new WinRMClientException(e.getMessage(), e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WinRMClientException(e.getMessage(), e);
		} catch (final ExecutionException e) {
			throw translateExecutionFailure(e);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
	}

	/**
	 * Start the command and return a {@link RemoteProcess} handle over it — the streaming
	 * counterpart of {@link #execute()}: stdout and stderr can be consumed while the command is
	 * still running, and the handle exposes the eventual exit code.
	 *
	 * <pre>{@code
	 * try (RemoteProcess process = client.command("wevtutil qe System /f:text").start()) {
	 * 	try (BufferedReader out = process.stdout()) {
	 * 		out.lines().forEach(this::process);
	 * 	}
	 * 	int exitCode = process.waitFor();
	 * }
	 * }</pre>
	 * <p>
	 * <b>The process must be closed</b> — use try-with-resources. It holds the client's serial
	 * connection until the command completes or the handle is closed; closing early terminates
	 * the remote command (WinRM terminate {@code Signal}). The timeout acts as an
	 * <i>inactivity</i> timeout — see {@link RemoteProcess}. File uploads run here, before the
	 * command starts.
	 *
	 * @return the running process handle, to use with try-with-resources
	 * @throws org.metricshub.winrm.exceptions.WinRMTimeoutException when the command startup times out
	 * @throws org.metricshub.winrm.exceptions.WinRMAuthenticationException when the credentials are rejected
	 * @throws org.metricshub.winrm.exceptions.WinRMFaultException when the remote service answers with a WSMan fault
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public RemoteProcess start() {
		final long start = Utils.getCurrentTimeMillis();
		final long timeoutMillis = WinRMClient.toMillis(timeout);
		try {
			final Prepared prepared = prepare(timeoutMillis, start);
			// The full timeout, not the remaining time: for a streaming consumer it bounds each
			// round trip (inactivity), not the overall exchange the preparation steps count against.
			final CommandCursor cursor = client
				.executor()
				.startCommand(prepared.command, prepared.workingDirectory, timeoutMillis, !pipeStdin);
			if (stdinSource != null) {
				try {
					feedStdin(cursor, prepared.charset);
				} catch (final Exception e) {
					// The input could not be delivered: terminate the command and release the client's
					// connection before reporting — a failed start must not leave an open handle behind.
					cursor.close();
					throw e;
				}
			}
			return new RemoteProcess(cursor, prepared.charset, client.hostname(), timeout, stdinSource != null);
		} catch (final TimeoutException e) {
			throw timeoutException(e);
		} catch (final IOException e) {
			throw new WinRMClientException(e.getMessage(), e);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
	}

	/** The command, working directory and charset actually sent, after the preparation steps. */
	private static final class Prepared {

		final String command;
		final String workingDirectory;
		final Charset charset;

		Prepared(final String command, final String workingDirectory, final Charset charset) {
			this.command = command;
			this.workingDirectory = workingDirectory;
			this.charset = charset;
		}
	}

	/**
	 * Run the preparation steps shared by {@link #execute()} and {@link #start()}: copy the local
	 * files to the remote host (rewriting the command line to reference the remote copies) and
	 * resolve the output charset.
	 */
	private Prepared prepare(final long timeoutMillis, final long start)
		throws IOException, TimeoutException, WindowsRemoteException {
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

		final Charset actualCharset = charset != null ? charset : WindowsRemoteExecutor.SHELL_OUTPUT_CHARSET;
		return new Prepared(actualCommand, actualWorkingDirectory, actualCharset);
	}

	/**
	 * Drain the streaming cursor on the worker thread {@link Utils#execute} provides, delivering
	 * each decoded chunk to the registered callbacks and accumulating the complete output for the
	 * final result. Incremental decoding with a carried-over decoder state yields exactly the text
	 * a whole-buffer decode would.
	 */
	private CommandResult drainWithCallbacks(final Prepared prepared, final long timeoutMillis, final long start)
		throws Exception {
		final ChunkDecoder stdoutDecoder = new ChunkDecoder(prepared.charset);
		final ChunkDecoder stderrDecoder = new ChunkDecoder(prepared.charset);
		final StringBuilder stdout = new StringBuilder();
		final StringBuilder stderr = new StringBuilder();
		try (
			CommandCursor cursor = client.executor()
				.startCommand(prepared.command, prepared.workingDirectory, timeoutMillis, !pipeStdin)) {
			if (stdinSource != null) {
				feedStdin(cursor, prepared.charset);
			}
			CommandCursor.Chunk chunk;
			while ((chunk = cursor.next()) != null) {
				deliver(stdoutDecoder.decode(chunk.stdout()), stdout, stdoutConsumer);
				deliver(stderrDecoder.decode(chunk.stderr()), stderr, stderrConsumer);
			}
			deliver(stdoutDecoder.finish(), stdout, stdoutConsumer);
			deliver(stderrDecoder.finish(), stderr, stderrConsumer);
			return new CommandResult(
				stdout.toString(),
				stderr.toString(),
				cursor.exitCode(),
				Duration.ofMillis(Utils.getCurrentTimeMillis() - start)
			);
		}
	}

	/**
	 * Deliver the pre-supplied input to the started command, buffer by buffer, marking the last
	 * one as the end of input so the remote stdin reaches EOF. The one-buffer read-ahead makes the
	 * final Send carry data AND the end mark together (no extra empty round trip), except for an
	 * empty source, whose single Send only announces the EOF.
	 */
	private void feedStdin(final CommandCursor cursor, final Charset charset)
		throws IOException, TimeoutException, WindowsRemoteException {
		try (InputStream input = stdinSource.open(charset)) {
			byte[] current = input.readNBytes(STDIN_BUFFER_SIZE);
			if (current.length == 0) {
				cursor.send(current, true);
				return;
			}
			while (current.length > 0) {
				final byte[] next = input.readNBytes(STDIN_BUFFER_SIZE);
				cursor.send(current, next.length == 0);
				current = next;
			}
		}
	}

	/** Append a decoded chunk to the accumulated output and hand it to the callback, when any. */
	private static void deliver(final String text, final StringBuilder accumulator, final Consumer<String> consumer) {
		if (text.isEmpty()) {
			return;
		}
		accumulator.append(text);
		if (consumer != null) {
			consumer.accept(text);
		}
	}

	/** Unwrap a worker failure from the callback variant into the documented unchecked hierarchy. */
	private RuntimeException translateExecutionFailure(final ExecutionException e) {
		final Throwable cause = e.getCause() != null ? e.getCause() : e;
		if (cause instanceof TimeoutException) {
			return timeoutException((TimeoutException) cause);
		}
		if (cause instanceof RuntimeException) {
			return (RuntimeException) cause;
		}
		if (cause instanceof Exception) {
			return WinRMClient.translate((Exception) cause);
		}
		return new WinRMClientException(cause.getMessage(), cause);
	}

	private WinRMTimeoutException timeoutException(final TimeoutException cause) {
		return new WinRMTimeoutException(
			String.format("Command timed out after %s on %s", timeout, client.hostname()),
			cause
		);
	}
}
