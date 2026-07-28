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
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.exceptions.WqlSyntaxException;

/**
 * A WQL query being prepared for execution, created by {@link WinRMClient#wql(String)}.
 * Every option has a sensible default; {@link #execute()} runs the query and returns the
 * complete result, {@link #stream()} yields the rows lazily as they arrive.
 */
public final class WqlRequest {

	private final WinRMClient client;
	private final String query;
	private String namespace;
	private Duration timeout;
	private int pageSize = WindowsRemoteExecutor.DEFAULT_WQL_MAX_ELEMENTS;
	private Duration pullTimeout;

	/**
	 * Create the request.
	 *
	 * @param client the client the query runs on
	 * @param query the WQL query
	 */
	WqlRequest(final WinRMClient client, final String query) {
		Utils.checkNonBlank(query, "query");
		this.client = client;
		this.query = query;
		this.namespace = client.defaultNamespace();
		this.timeout = client.defaultTimeout();
	}

	/**
	 * Set the WMI namespace to query. Default: the client's namespace
	 * ({@code ROOT\CIMV2} unless configured on the builder).
	 *
	 * @param namespace the WMI namespace, e.g. {@code root\cimv2}
	 * @return this request
	 */
	public WqlRequest namespace(final String namespace) {
		Utils.checkNonBlank(namespace, "namespace");
		this.namespace = namespace;
		return this;
	}

	/**
	 * Set the timeout of this query. For {@link #execute()} it is a wall-clock deadline covering
	 * every WSMan round trip and result collection; for {@link #stream()} it is an
	 * <i>inactivity</i> timeout — the longest silence tolerated from the server between two
	 * responses, with no overall deadline. Default: the client's timeout.
	 *
	 * @param timeout the timeout (at least one millisecond)
	 * @return this request
	 */
	public WqlRequest timeout(final Duration timeout) {
		this.timeout = WinRMClient.checkPositive(timeout, "timeout");
		return this;
	}

	/**
	 * Set the enumeration batch size: how many rows the server may return per WSMan
	 * Enumerate/Pull response ({@code MaxElements}). Default:
	 * {@value WindowsRemoteExecutor#DEFAULT_WQL_MAX_ELEMENTS}.
	 *
	 * @param pageSize the maximum number of rows per response (must be positive)
	 * @return this request
	 */
	public WqlRequest pageSize(final int pageSize) {
		Utils.checkArgumentNotZeroOrNegative(pageSize, "pageSize");
		this.pageSize = pageSize;
		return this;
	}

	/**
	 * Set the maximum time the server may hold a single Pull request open before answering with
	 * the rows it has ({@code MaxTime}). Default: none — the server decides.
	 *
	 * @param pullTimeout the per-Pull timeout (at least one millisecond)
	 * @return this request
	 */
	public WqlRequest pullTimeout(final Duration pullTimeout) {
		this.pullTimeout = WinRMClient.checkPositive(pullTimeout, "pullTimeout");
		return this;
	}

	/**
	 * Execute the query and collect the complete result.
	 *
	 * @return the query result: rows, columns in query order, and execution time
	 * @throws WqlSyntaxException when the WQL query is invalid
	 * @throws org.metricshub.winrm.exceptions.WinRMTimeoutException when the timeout elapses first
	 * @throws org.metricshub.winrm.exceptions.WinRMAuthenticationException when the credentials are rejected
	 * @throws org.metricshub.winrm.exceptions.WinRMFaultException when the remote service answers with a WSMan fault
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public WqlResult execute() {
		final long start = Utils.getCurrentTimeMillis();
		final long timeoutMillis = WinRMClient.toMillis(timeout);
		final long pullTimeoutMillis = pullTimeout != null ? WinRMClient.toMillis(pullTimeout) : 0;
		try {
			final List<Map<String, Object>> result = client
				.executor()
				.executeWql(namespace, query, timeoutMillis, pageSize, pullTimeoutMillis);

			// Extract the list of properties from the result, with same order as in the WQL query
			final List<String> columns = WmiHelper.extractPropertiesFromResult(result, query);
			final List<WqlRow> rows = result.stream().map(WqlRow::new).collect(Collectors.toList());

			return new WqlResult(columns, rows, Duration.ofMillis(Utils.getCurrentTimeMillis() - start));
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("WQL query timed out after %s on %s", timeout, client.hostname()),
				e
			);
		} catch (final WqlQuerySyntaxException e) {
			throw new WqlSyntaxException(e.getMessage(), e);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}
	}

	/**
	 * Execute the query and stream the rows lazily: each row is yielded as soon as it is parsed,
	 * and the next WS-Enumeration page is pulled from the server only as the stream advances —
	 * memory stays bounded by one page ({@link #pageSize(int)}) instead of the whole result set.
	 *
	 * <pre>{@code
	 * try (Stream<WqlRow> rows = client.wql("SELECT * FROM Win32_NTLogEvent").stream()) {
	 * 	rows.filter(row -> "Error".equals(row.string("Type"))).limit(100).forEach(this::process);
	 * }
	 * }</pre>
	 * <p>
	 * <b>The stream must be closed</b> (same contract as {@link java.nio.file.Files#lines}) — use
	 * try-with-resources. It holds the client's serial connection while open: other operations on
	 * the same client block until it is closed or exhausted. Closing before the last row tells the
	 * server to release the enumeration immediately (WS-Enumeration {@code Release}).
	 * <p>
	 * The timeout acts as an <i>inactivity</i> timeout — the longest silence tolerated from the
	 * server between two responses — not an overall deadline: consuming a large result can take
	 * arbitrarily long as long as the server keeps answering. The initial request is sent here;
	 * later pages are fetched during consumption, so the exceptions below can also be thrown from
	 * the stream's operations while iterating.
	 *
	 * @return a lazy, sequential stream of rows, to use with try-with-resources
	 * @throws WqlSyntaxException when the WQL query is invalid
	 * @throws org.metricshub.winrm.exceptions.WinRMTimeoutException when the inactivity timeout
	 *         elapses
	 * @throws org.metricshub.winrm.exceptions.WinRMAuthenticationException when the credentials are rejected
	 * @throws org.metricshub.winrm.exceptions.WinRMFaultException when the remote service answers with a WSMan fault
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public Stream<WqlRow> stream() {
		final long timeoutMillis = WinRMClient.toMillis(timeout);
		final long pullTimeoutMillis = pullTimeout != null ? WinRMClient.toMillis(pullTimeout) : 0;
		final WqlCursor cursor;
		try {
			cursor = client.executor().streamWql(namespace, query, timeoutMillis, pageSize, pullTimeoutMillis);
		} catch (final TimeoutException e) {
			throw timeoutException(e);
		} catch (final WqlQuerySyntaxException e) {
			throw new WqlSyntaxException(e.getMessage(), e);
		} catch (final WindowsRemoteException e) {
			throw WinRMClient.translate(e);
		}

		final Spliterator<WqlRow> spliterator = new Spliterators.AbstractSpliterator<WqlRow>(
			Long.MAX_VALUE,
			Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE
		) {
			@Override
			public boolean tryAdvance(final Consumer<? super WqlRow> action) {
				final Map<String, Object> row;
				try {
					row = cursor.next();
				} catch (final TimeoutException e) {
					throw timeoutException(e);
				} catch (final WindowsRemoteException e) {
					throw WinRMClient.translate(e);
				}
				if (row == null) {
					return false;
				}
				action.accept(new WqlRow(row));
				return true;
			}
		};
		return StreamSupport.stream(spliterator, false).onClose(cursor::close);
	}

	private WinRMTimeoutException timeoutException(final TimeoutException cause) {
		return new WinRMTimeoutException(
			String.format("WQL query timed out after %s on %s", timeout, client.hostname()),
			cause
		);
	}
}
