package org.metricshub.winrm.wql;

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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WmiHelper;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

public class WinRMWqlExecutor {

	private final long executionTime;
	private final List<String> headers;
	private final List<List<String>> rows;

	/**
	 * The WinRMWqlExecutor constructor
	 *
	 * @param executionTime The execution time in milliseconds
	 * @param headers The headers list
	 * @param rows The value rows list
	 */
	public WinRMWqlExecutor(final long executionTime, final List<String> headers, final List<List<String>> rows) {
		this.executionTime = executionTime;
		this.headers = headers;
		this.rows = rows;
	}

	/**
	 * Get the execution time of the query.
	 * @return
	 */
	public long getExecutionTime() {
		return executionTime;
	}

	/**
	 * Get the headers of the query.
	 * @return
	 */
	public List<String> getHeaders() {
		return headers;
	}

	/**
	 * Get the result rows of the query.
	 * @return
	 */
	public List<List<String>> getRows() {
		return rows;
	}

	/**
	 * Execute a WQL query and process its result.
	 *
	 * @param protocol The HTTP protocol (HTTP by default)
	 * @param hostname Host to connect to. (Mandatory)
	 * @param port The port (5985 for HTPP or 5986 for HTTPS by default)
	 * @param username The username name. (Mandatory)
	 * @param password The password
	 * @param namespace The namespace default value: {@value WmiHelper#DEFAULT_NAMESPACE}
	 * @param wqlQuery The WQL query (Mandatory)
	 * @param timeout The timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @param ticketCache The Ticket Cache path
	 * @param authentications List of authentications. only NTLM if absent
	 *
	 * @return WinRMWqlExecutor result instance with header, rows and execution time.
	 *
	 * @throws WinRMException For any problem encountered on remote
	 * @throws WqlQuerySyntaxException On WQL syntax errors
	 * @throws TimeoutException To notify userName of timeout
	 */
	public static WinRMWqlExecutor executeWql(
		final WinRMHttpProtocolEnum protocol,
		final String hostname,
		final Integer port,
		final String username,
		final char[] password,
		final String namespace,
		final String wqlQuery,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws WinRMException, WqlQuerySyntaxException, TimeoutException {
		Utils.checkNonNull(wqlQuery, "wqlQuery");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		final long start = Utils.getCurrentTimeMillis();

		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(protocol, hostname, port, username, password, namespace);

		try (
			final WinRMService winRMService = WinRMService.createInstance(
				winRMEndpoint,
				timeout,
				ticketCache,
				authentications
			)
		) {
			final List<Map<String, Object>> result = winRMService.executeWql(wqlQuery, timeout);

			// Extract the list of properties from the result, with same order as in the WQL query
			final List<String> headers = WmiHelper.extractPropertiesFromResult(result, wqlQuery);

			final List<List<String>> rows = result
				.stream()
				.map(row -> headers.stream().map(header -> (String) row.get(header)).collect(Collectors.toList()))
				.collect(Collectors.toList());

			return new WinRMWqlExecutor(Utils.getCurrentTimeMillis() - start, headers, rows);
		}
	}
}
