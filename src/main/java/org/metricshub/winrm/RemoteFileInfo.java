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

import java.time.Instant;
import java.util.Objects;

/**
 * The properties of a file or directory on the remote host, as returned by
 * {@link RemoteFile#info()} and {@link RemoteDirectoryListing}: an immutable value.
 * <p>
 * Timestamps come from the host's UTC file times, with their 100-nanosecond precision. The
 * attribute accessors decode the Windows {@code FileAttributes} bit flags; {@link #attributes()}
 * returns the raw value for anything else (e.g. {@code 0x800} compressed, {@code 0x4000}
 * encrypted).
 */
public final class RemoteFileInfo {

	private static final int READ_ONLY = 0x1;
	private static final int HIDDEN = 0x2;
	private static final int SYSTEM = 0x4;
	private static final int DIRECTORY = 0x10;
	private static final int ARCHIVE = 0x20;
	private static final int REPARSE_POINT = 0x400;

	private final String path;
	private final int attributes;
	private final long size;
	private final Instant lastModified;
	private final Instant created;
	private final Instant lastAccessed;

	/**
	 * Create the value.
	 *
	 * @param path the full path, as reported by the host
	 * @param attributes the raw {@code FileAttributes} value
	 * @param size the size in bytes (0 for a directory)
	 * @param lastModified the last write time
	 * @param created the creation time
	 * @param lastAccessed the last access time
	 */
	RemoteFileInfo(
		final String path,
		final int attributes,
		final long size,
		final Instant lastModified,
		final Instant created,
		final Instant lastAccessed
	) {
		this.path = path;
		this.attributes = attributes;
		this.size = size;
		this.lastModified = lastModified;
		this.created = created;
		this.lastAccessed = lastAccessed;
	}

	/**
	 * Get the full path of the entry, as reported by the host (absolute, with backslashes).
	 *
	 * @return the path
	 */
	public String path() {
		return path;
	}

	/**
	 * Get the last element of the path: the file or directory name ({@code C:} for a drive root).
	 *
	 * @return the name
	 */
	public String name() {
		int end = path.length();
		while (end > 1 && path.charAt(end - 1) == '\\') {
			end--;
		}
		return path.substring(path.lastIndexOf('\\', end - 1) + 1, end);
	}

	/**
	 * Tell whether the entry is a directory.
	 *
	 * @return {@code true} for a directory (including a junction or a directory symbolic link)
	 */
	public boolean isDirectory() {
		return (attributes & DIRECTORY) != 0;
	}

	/**
	 * Get the size of the file.
	 *
	 * @return the size in bytes, 0 for a directory
	 */
	public long size() {
		return size;
	}

	/**
	 * Get the time of the last write.
	 *
	 * @return the last modification time
	 */
	public Instant lastModified() {
		return lastModified;
	}

	/**
	 * Get the creation time.
	 *
	 * @return the creation time
	 */
	public Instant created() {
		return created;
	}

	/**
	 * Get the time of the last access. Windows updates it lazily, or not at all when last access
	 * updates are disabled (the default on many versions): do not rely on it.
	 *
	 * @return the last access time
	 */
	public Instant lastAccessed() {
		return lastAccessed;
	}

	/**
	 * Tell whether the entry is hidden.
	 *
	 * @return {@code true} when the hidden attribute is set
	 */
	public boolean isHidden() {
		return (attributes & HIDDEN) != 0;
	}

	/**
	 * Tell whether the entry is a system file or directory.
	 *
	 * @return {@code true} when the system attribute is set
	 */
	public boolean isSystem() {
		return (attributes & SYSTEM) != 0;
	}

	/**
	 * Tell whether the entry is read-only.
	 *
	 * @return {@code true} when the read-only attribute is set
	 */
	public boolean isReadOnly() {
		return (attributes & READ_ONLY) != 0;
	}

	/**
	 * Tell whether the entry is marked for archiving (backup).
	 *
	 * @return {@code true} when the archive attribute is set
	 */
	public boolean isArchive() {
		return (attributes & ARCHIVE) != 0;
	}

	/**
	 * Tell whether the entry is a reparse point: a junction, a symbolic link, a mount point, or a
	 * cloud placeholder. A listing reports reparse points but never descends into them.
	 *
	 * @return {@code true} when the reparse point attribute is set
	 */
	public boolean isReparsePoint() {
		return (attributes & REPARSE_POINT) != 0;
	}

	/**
	 * Get the raw Windows {@code FileAttributes} bit flags.
	 *
	 * @return the attributes
	 */
	public int attributes() {
		return attributes;
	}

	@Override
	public boolean equals(final Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof RemoteFileInfo)) {
			return false;
		}
		final RemoteFileInfo that = (RemoteFileInfo) other;
		return attributes == that.attributes
			&&
			size == that.size
			&&
			path.equals(that.path)
			&&
			lastModified.equals(that.lastModified)
			&&
			created.equals(that.created)
			&&
			lastAccessed.equals(that.lastAccessed);
	}

	@Override
	public int hashCode() {
		return Objects.hash(path, attributes, size, lastModified, created, lastAccessed);
	}

	@Override
	public String toString() {
		return String.format("%s %12d %s %s", isDirectory() ? "d" : "-", size, lastModified, path);
	}
}
