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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The complete result of a WQL query: the rows, the column names in query order, and the
 * time the query took. Iterable, so it can be consumed directly:
 *
 * <pre>{@code
 * for (WqlRow row : client.wql("SELECT Name, State FROM Win32_Service").execute()) {
 * 	System.out.println(row.string("Name"));
 * }
 * }</pre>
 */
public final class WqlResult implements Iterable<WqlRow> {

	private final List<String> columns;
	private final List<WqlRow> rows;
	private final Duration elapsed;

	/**
	 * Create the result (lists are copied defensively).
	 *
	 * @param columns the column names, in query order
	 * @param rows the result rows
	 * @param elapsed the query execution time
	 */
	WqlResult(final List<String> columns, final List<WqlRow> rows, final Duration elapsed) {
		this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
		this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
		this.elapsed = elapsed;
	}

	/**
	 * Get the column names, in the order they appear in the WQL query ({@code SELECT *} yields
	 * the order the server returned).
	 *
	 * @return an unmodifiable list of column names
	 */
	public List<String> columns() {
		return columns;
	}

	/**
	 * Get the result rows.
	 *
	 * @return an unmodifiable list of rows
	 */
	public List<WqlRow> rows() {
		return rows;
	}

	/**
	 * Get the number of rows.
	 *
	 * @return the row count
	 */
	public int size() {
		return rows.size();
	}

	/**
	 * Whether the query returned no rows.
	 *
	 * @return {@code true} when the result is empty
	 */
	public boolean isEmpty() {
		return rows.isEmpty();
	}

	/**
	 * Get the time the query took, from request to complete result.
	 *
	 * @return the elapsed time
	 */
	public Duration elapsed() {
		return elapsed;
	}

	@Override
	public Iterator<WqlRow> iterator() {
		return rows.iterator();
	}

	@Override
	public String toString() {
		return String.format("WqlResult[%d rows, columns=%s, elapsed=%s]", rows.size(), columns, elapsed);
	}
}
