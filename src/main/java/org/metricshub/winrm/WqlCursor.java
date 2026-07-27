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

import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;

/**
 * A lazily-advancing cursor over the rows of a WQL enumeration, returned by
 * {@link WindowsRemoteExecutor#streamWql(String, String, long, int, long)}. Rows are parsed and
 * served page by page: advancing past the current WS-Enumeration page issues the next Pull
 * request, so memory stays bounded by one page rather than the whole result set.
 * <p>
 * The cursor owns the executor's serial connection until it is exhausted or closed: no other
 * operation can run on the same executor while the cursor is open (the same contract as a JDBC
 * {@code ResultSet} on its connection). Exhaustion releases the connection on its own; closing
 * before the end additionally sends a WS-Enumeration Release so the server frees the enumeration
 * context immediately. Always close the cursor — use try-with-resources.
 * <p>
 * A cursor is not thread-safe: advance and close it from one thread at a time.
 */
public interface WqlCursor extends AutoCloseable {
	/**
	 * Advance to the next row, issuing the next WS-Enumeration Pull when the current page is
	 * exhausted.
	 *
	 * @return the next row as an ordered property map, or {@code null} once the enumeration is
	 *         exhausted
	 * @throws TimeoutException when the server stays silent for a whole per-round-trip timeout
	 *         (the inactivity timeout of the stream)
	 * @throws WindowsRemoteException for any other failure while pulling
	 */
	Map<String, Object> next() throws TimeoutException, WindowsRemoteException;

	/**
	 * Release the enumeration and the executor's connection. When the enumeration is not
	 * exhausted, a best-effort WS-Enumeration Release tells the server to free the enumeration
	 * context. Idempotent, and never throws: releasing the context is a courtesy the server can
	 * also handle on its own timeout.
	 */
	@Override
	void close();
}
