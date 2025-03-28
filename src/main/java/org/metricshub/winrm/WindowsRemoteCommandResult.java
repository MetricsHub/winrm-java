package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2024 Metricshub
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

public class WindowsRemoteCommandResult {

	private final String stdout;
	private final String stderr;
	private final float executionTime;
	private final int statusCode;

	/**
	 * Command Result constructor
	 *
	 * @param stdout The stdout of the command
	 * @param stderr The stderr of the command
	 * @param executionTime The execution time of the command in milliseconds
	 * @param statusCode The command return status code
	 */
	public WindowsRemoteCommandResult(
		final String stdout,
		final String stderr,
		final float executionTime,
		final int statusCode
	) {
		this.stdout = stdout;
		this.stderr = stderr;
		this.executionTime = executionTime;
		this.statusCode = statusCode;
	}

	/**
	 * Get the stdout of the command.
	 * @return
	 */
	public String getStdout() {
		return stdout;
	}

	/**
	 * Get the stderr of the command.
	 * @return
	 */
	public String getStderr() {
		return stderr;
	}

	/**
	 * Get the execution time of the command in seconds.
	 * @return
	 */
	public float getExecutionTime() {
		return executionTime;
	}

	/**
	 * Get the return status code of the command
	 * @return
	 */
	public int getStatusCode() {
		return statusCode;
	}

	@Override
	public String toString() {
		return new StringBuilder()
			.append("WindowsRemoteCommandResult:\nstdout:\n")
			.append(stdout)
			.append("\nstderr:\n")
			.append(stderr)
			.append("\nexecutionTime = ")
			.append(executionTime)
			.append("\nstatusCode = ")
			.append(statusCode)
			.toString();
	}
}
