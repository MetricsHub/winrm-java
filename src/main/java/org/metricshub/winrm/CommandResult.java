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

import java.time.Duration;

/**
 * The result of a command executed on the remote host: its output streams, its process exit
 * code, and the time it took.
 */
public final class CommandResult {

	private final String stdout;
	private final String stderr;
	private final int exitCode;
	private final Duration elapsed;

	/**
	 * Create the result.
	 *
	 * @param stdout the standard output of the command
	 * @param stderr the standard error of the command
	 * @param exitCode the process exit code
	 * @param elapsed the execution time
	 */
	CommandResult(final String stdout, final String stderr, final int exitCode, final Duration elapsed) {
		this.stdout = stdout;
		this.stderr = stderr;
		this.exitCode = exitCode;
		this.elapsed = elapsed;
	}

	/**
	 * Get the standard output of the command.
	 *
	 * @return the stdout content
	 */
	public String stdout() {
		return stdout;
	}

	/**
	 * Get the standard error of the command.
	 *
	 * @return the stderr content
	 */
	public String stderr() {
		return stderr;
	}

	/**
	 * Get the process exit code of the command. Windows may report HRESULT codes as unsigned
	 * 32-bit values; they are narrowed to the equivalent signed {@code int}.
	 *
	 * @return the exit code
	 */
	public int exitCode() {
		return exitCode;
	}

	/**
	 * Get the time the command took, from request to completion.
	 *
	 * @return the elapsed time
	 */
	public Duration elapsed() {
		return elapsed;
	}

	@Override
	public String toString() {
		return String
			.format("CommandResult[exitCode=%d, elapsed=%s]%nstdout:%n%s%nstderr:%n%s", exitCode, elapsed, stdout, stderr);
	}
}
