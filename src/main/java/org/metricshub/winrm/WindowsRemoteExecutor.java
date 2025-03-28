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

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;

public interface WindowsRemoteExecutor extends AutoCloseable {
	/**
	 * <p>Execute a WQL query and process its result.</p>
	 *
	 * @param wqlQuery the WQL query (required)
	 * @param timeout Timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 *
	 * @return a list of result rows. A result row is a Map(LinkedHashMap to preserve the query order) of
	 * properties/values.
	 *
	 * @throws TimeoutException to notify userName of timeout.
	 * @throws WqlQuerySyntaxException if WQL query syntax is invalid
	 * @throws WindowsRemoteException For any problem encountered
	 */
	public List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout)
		throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException;

	/**
	 * Execute the command on the remote
	 *
	 * @param command The command to execute
	 * @param workingDirectory Path of the directory for the spawned process on the remote system (can be null)
	 * @param charset The charset
	 * @param timeout Timeout in milliseconds
	 *
	 * @return The command result
	 *
	 * @throws WindowsRemoteException For any problem encountered
	 * @throws TimeoutException To notify userName of timeout.
	 */
	public WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Charset charset,
		final long timeout
	) throws WindowsRemoteException, TimeoutException;

	/**
	 * Get the hostname.
	 * @return
	 */
	public String getHostname();

	/**
	 * Get the username.
	 * @return
	 */
	public String getUsername();

	/**
	 * Get the password.
	 * @return
	 */
	public char[] getPassword();
}
