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

import java.util.List;

/**
 * The result of {@link RemoteDirectoryListing#execute()}: the matching entries, and the
 * directories that could not be read.
 */
public final class RemoteFileList {

	private final List<RemoteFileInfo> entries;
	private final List<String> inaccessible;

	/**
	 * Create the result.
	 *
	 * @param entries the matching entries
	 * @param inaccessible the paths of the directories that could not be read
	 */
	RemoteFileList(final List<RemoteFileInfo> entries, final List<String> inaccessible) {
		this.entries = List.copyOf(entries);
		this.inaccessible = List.copyOf(inaccessible);
	}

	/**
	 * Get the matching entries, in the order the host walked them: the entries of a directory
	 * together, depth-first.
	 *
	 * @return an unmodifiable list, empty for an empty directory or when nothing matches
	 */
	public List<RemoteFileInfo> entries() {
		return entries;
	}

	/**
	 * Get the directories that could not be read (typically access denied): their content is
	 * missing from {@link #entries()}, while the walk went on elsewhere.
	 *
	 * @return an unmodifiable list of paths, empty when the whole tree was read
	 */
	public List<String> inaccessible() {
		return inaccessible;
	}

	@Override
	public String toString() {
		return entries.size() + " entries" + (inaccessible.isEmpty() ? "" : ", " + inaccessible.size() + " inaccessible");
	}
}
