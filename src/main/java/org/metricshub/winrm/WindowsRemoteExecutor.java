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

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;

public interface WindowsRemoteExecutor extends AutoCloseable {
	/**
	 * Default WS-Enumeration {@code MaxElements} batch size for WQL queries: how many rows the
	 * server may return per Enumerate/Pull response.
	 */
	int DEFAULT_WQL_MAX_ELEMENTS = 32000;

	/**
	 * <p>
	 * Execute a WQL query and process its result.
	 * </p>
	 *
	 * @param wqlQuery the WQL query (required)
	 * @param timeout Timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @return a list of result rows. A result row is a Map(LinkedHashMap to preserve the query order) of
	 *         properties/values.
	 * @throws TimeoutException to notify userName of timeout.
	 * @throws WqlQuerySyntaxException if WQL query syntax is invalid
	 * @throws WindowsRemoteException For any problem encountered
	 */
	List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout)
		throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException;

	/**
	 * <p>
	 * Execute a WQL query with explicit enumeration parameters: the WMI namespace, the
	 * WS-Enumeration {@code MaxElements} batch size, and the per-Pull {@code MaxTime}.
	 * </p>
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}: only executors that
	 * can honor the namespace and enumeration parameters (such as the built-in lightweight backend)
	 * implement this method, and silently ignoring a namespace would query the wrong resource.
	 * </p>
	 *
	 * @param namespace the WMI namespace to query, e.g. {@code ROOT\CIMV2} (required)
	 * @param wqlQuery the WQL query (required)
	 * @param timeout Timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @param maxElements maximum number of rows per Enumerate/Pull response (throws an
	 *        IllegalArgumentException if negative or zero); see {@link #DEFAULT_WQL_MAX_ELEMENTS}
	 * @param pullTimeout maximum time in milliseconds the server may hold a single Pull open before
	 *        answering with the rows it has ({@code MaxTime}); 0 leaves it to the server default
	 * @return a list of result rows. A result row is a Map(LinkedHashMap to preserve the query order) of
	 *         properties/values.
	 * @throws TimeoutException to notify userName of timeout.
	 * @throws WqlQuerySyntaxException if WQL query syntax is invalid
	 * @throws WindowsRemoteException For any problem encountered
	 */
	default List<Map<String, Object>> executeWql(
		final String namespace,
		final String wqlQuery,
		final long timeout,
		final int maxElements,
		final long pullTimeout
	) throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		throw new UnsupportedOperationException(
			getClass().getName() + " does not support WQL enumeration parameters."
		);
	}

	/**
	 * <p>
	 * Start a WQL enumeration and return a lazy {@link WqlCursor} over its rows: rows can be
	 * consumed as the WS-Enumeration pages arrive, and memory stays bounded by one page.
	 * </p>
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}: only executors that
	 * support streaming (such as the built-in lightweight backend) implement this method.
	 * </p>
	 *
	 * @param namespace the WMI namespace to query, e.g. {@code ROOT\CIMV2} (required)
	 * @param wqlQuery the WQL query (required)
	 * @param timeout timeout in milliseconds of each WSMan round trip — the inactivity timeout of
	 *        the stream, not an overall deadline (throws an IllegalArgumentException if negative
	 *        or zero)
	 * @param maxElements maximum number of rows per Enumerate/Pull response (throws an
	 *        IllegalArgumentException if negative or zero); see {@link #DEFAULT_WQL_MAX_ELEMENTS}
	 * @param pullTimeout maximum time in milliseconds the server may hold a single Pull open before
	 *        answering with the rows it has ({@code MaxTime}); 0 leaves it to the server default
	 * @return a cursor over the result rows, owning the executor's connection until exhausted or
	 *         closed — always close it (try-with-resources)
	 * @throws TimeoutException when the server does not answer the initial Enumerate in time
	 * @throws WqlQuerySyntaxException if WQL query syntax is invalid
	 * @throws WindowsRemoteException For any problem encountered
	 */
	default WqlCursor streamWql(
		final String namespace,
		final String wqlQuery,
		final long timeout,
		final int maxElements,
		final long pullTimeout
	) throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		throw new UnsupportedOperationException(getClass().getName() + " does not support streaming WQL enumeration.");
	}

	/**
	 * <p>
	 * Start a command on the remote host and return a {@link CommandCursor} over its raw output:
	 * chunks can be consumed as the WSMan Receive responses arrive, before the command exits.
	 * </p>
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}: only executors that
	 * support streaming (such as the built-in lightweight backend) implement this method.
	 * </p>
	 *
	 * @param command The command to execute
	 * @param workingDirectory Path of the directory for the spawned process on the remote system (can be null)
	 * @param timeout timeout in milliseconds of each WSMan round trip — the inactivity timeout of
	 *        the stream, not an overall deadline (throws an IllegalArgumentException if negative
	 *        or zero)
	 * @return a cursor over the command output, owning the executor's connection until the command
	 *         completes or the cursor is closed — always close it (try-with-resources)
	 * @throws TimeoutException when the server does not answer the command startup in time
	 * @throws WindowsRemoteException For any problem encountered
	 */
	default CommandCursor startCommand(final String command, final String workingDirectory, final long timeout)
		throws TimeoutException, WindowsRemoteException {
		throw new UnsupportedOperationException(getClass().getName() + " does not support streaming command execution.");
	}

	/**
	 * Execute the command on the remote
	 *
	 * @param command The command to execute
	 * @param workingDirectory Path of the directory for the spawned process on the remote system (can be null)
	 * @param charset The charset
	 * @param timeout Timeout in milliseconds
	 * @return The command result
	 * @throws WindowsRemoteException For any problem encountered
	 * @throws TimeoutException To notify userName of timeout.
	 */
	WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Charset charset,
		final long timeout
	) throws WindowsRemoteException, TimeoutException;

	/**
	 * Get the hostname.
	 *
	 * @return
	 */
	String getHostname();

	/**
	 * Get the username.
	 *
	 * @return
	 */
	String getUsername();

	/**
	 * Get the password.
	 *
	 * @return
	 */
	char[] getPassword();

	/**
	 * Close the executor and release its resources. Narrows {@link AutoCloseable#close()} so it does
	 * not declare a checked exception, letting callers use try-with-resources without catching
	 * {@link Exception}.
	 */
	@Override
	void close();
}
