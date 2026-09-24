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

import java.io.BufferedReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;

/**
 * A listing of a remote directory being prepared, created by {@link RemoteFile#list()}: set the
 * filters, then collect the result with {@link #execute()} or stream it with {@link #stream()}.
 *
 * <pre>{@code
 * RemoteFileList logs = client.file("C:\\inetpub\\logs").list()
 * 	.glob("*.log")
 * 	.recursive()
 * 	.filesOnly()
 * 	.modifiedAfter(Instant.now().minus(Duration.ofDays(1)))
 * 	.execute();
 * }</pre>
 * <p>
 * The directory is walked on the host by a small PowerShell script, and <b>every filter is
 * evaluated there</b>: only the matching entries travel. Without {@link #recursive()}, only the
 * directory's own entries are listed. With it, every subdirectory is traversed — whatever the
 * filters, which select what is reported, not where the walk goes — except <b>reparse points</b>
 * (junctions, symbolic links, mount points), which are reported but never descended into, so a
 * junction looping back to its parent terminates. A subdirectory that cannot be read (typically
 * access denied) does not stop the walk: its path is reported through
 * {@link #onInaccessible(Consumer)} and {@link RemoteFileList#inaccessible()}.
 * <p>
 * A request is not thread-safe; configure it and call its terminals from one thread.
 */
public final class RemoteDirectoryListing {

	private static final int ALL = 0;
	private static final int FILES = 1;
	private static final int DIRECTORIES = 2;

	private final WinRMClient client;
	private final String path;
	private Duration timeout;
	private String glob;
	private boolean recursive;
	private int maxDepth = Integer.MAX_VALUE;
	private int type = ALL;
	private long minSize;
	private long maxSize = Long.MAX_VALUE;
	private long after = -1;
	private long before = Long.MAX_VALUE;
	private Consumer<String> onInaccessible = p -> {};

	/**
	 * Create the request.
	 *
	 * @param client the client the directory is listed through
	 * @param path the path of the directory on the remote host
	 */
	RemoteDirectoryListing(final WinRMClient client, final String path) {
		this.client = client;
		this.path = path;
		this.timeout = client.defaultTimeout();
	}

	/**
	 * Report only the entries whose <b>name</b> (not path) matches the given Windows wildcard
	 * pattern: {@code *} matches any sequence of characters (including none), {@code ?} exactly one
	 * character, and every other character only itself, case-insensitively. The whole name must
	 * match: {@code *.log} matches {@code app.log}, not {@code app.log.1} (unlike {@code dir},
	 * which also matches 8.3 short names). With {@link #recursive()}, every directory is still
	 * traversed; the pattern only selects what is reported.
	 *
	 * @param glob the pattern, e.g. {@code *.log} or {@code u_ex??????.log}
	 * @return this request
	 */
	public RemoteDirectoryListing glob(final String glob) {
		Utils.checkNonBlank(glob, "glob");
		this.glob = glob;
		return this;
	}

	/**
	 * List the whole tree below the directory instead of its own entries only, depth-first.
	 * Reparse points are reported but never descended into.
	 *
	 * @return this request
	 */
	public RemoteDirectoryListing recursive() {
		this.recursive = true;
		return this;
	}

	/**
	 * List recursively, down to the given depth: 1 is the directory's own entries (the same as a
	 * non-recursive listing), 2 adds the entries of its subdirectories, and so on. Implies
	 * {@link #recursive()}.
	 *
	 * @param maxDepth the deepest level listed (at least 1)
	 * @return this request
	 */
	public RemoteDirectoryListing maxDepth(final int maxDepth) {
		Utils.checkArgumentNotZeroOrNegative(maxDepth, "maxDepth");
		this.maxDepth = maxDepth;
		this.recursive = true;
		return this;
	}

	/**
	 * Report files only (directories are still traversed with {@link #recursive()}). Replaces
	 * {@link #directoriesOnly()}.
	 *
	 * @return this request
	 */
	public RemoteDirectoryListing filesOnly() {
		this.type = FILES;
		return this;
	}

	/**
	 * Report directories only. Replaces {@link #filesOnly()}.
	 *
	 * @return this request
	 */
	public RemoteDirectoryListing directoriesOnly() {
		this.type = DIRECTORIES;
		return this;
	}

	/**
	 * Report only the entries last modified strictly after the given instant.
	 *
	 * @param instant the exclusive lower bound
	 * @return this request
	 */
	public RemoteDirectoryListing modifiedAfter(final Instant instant) {
		Utils.checkNonNull(instant, "instant");
		this.after = RemoteFiles.toFileTime(instant);
		return this;
	}

	/**
	 * Report only the entries last modified strictly before the given instant.
	 *
	 * @param instant the exclusive upper bound
	 * @return this request
	 */
	public RemoteDirectoryListing modifiedBefore(final Instant instant) {
		Utils.checkNonNull(instant, "instant");
		// Rounded up to the 100 ns FileTime unit, so a file time just below the bound still passes.
		this.before = RemoteFiles.toFileTime(instant.plusNanos(99));
		return this;
	}

	/**
	 * Report only the files of at least the given size. Directories have no size and are not
	 * filtered by it: combine with {@link #filesOnly()} to leave them out.
	 *
	 * @param bytes the smallest size, inclusive
	 * @return this request
	 */
	public RemoteDirectoryListing minSize(final long bytes) {
		this.minSize = bytes;
		return this;
	}

	/**
	 * Report only the files of at most the given size. Directories have no size and are not
	 * filtered by it: combine with {@link #filesOnly()} to leave them out.
	 *
	 * @param bytes the largest size, inclusive
	 * @return this request
	 */
	public RemoteDirectoryListing maxSize(final long bytes) {
		this.maxSize = bytes;
		return this;
	}

	/**
	 * Be told of each directory that could not be read (typically access denied): its path is
	 * given to the consumer as soon as the host reports it, and the walk goes on. Applies to both
	 * terminals; {@link #execute()} also collects the paths in
	 * {@link RemoteFileList#inaccessible()}. The directory being listed is not concerned: when it
	 * cannot be read, the listing fails.
	 *
	 * @param consumer receives the path of each directory that could not be read
	 * @return this request
	 */
	public RemoteDirectoryListing onInaccessible(final Consumer<String> consumer) {
		Utils.checkNonNull(consumer, "consumer");
		this.onInaccessible = consumer;
		return this;
	}

	/**
	 * Override the client's timeout for this request. For {@link #execute()} it is a wall-clock
	 * deadline for the whole listing; for {@link #stream()} it is an <i>inactivity</i> timeout.
	 * The host signals it is alive every second while it walks, so the inactivity timeout only
	 * trips when the host is unresponsive — or when reading a single directory takes longer.
	 *
	 * @param timeout the timeout (at least one millisecond)
	 * @return this request
	 */
	public RemoteDirectoryListing timeout(final Duration timeout) {
		this.timeout = WinRMClient.checkPositive(timeout, "timeout");
		return this;
	}

	/**
	 * List the directory and collect the result.
	 *
	 * @return the matching entries and the directories that could not be read
	 * @throws WinRMClientException when the directory cannot be listed (not found, a file, access
	 *         denied, PowerShell unavailable or constrained)
	 * @throws WinRMTimeoutException when the timeout elapses first
	 */
	public RemoteFileList execute() {
		return RemoteFiles.blocking(client, path, timeout, () -> {
			final List<String> inaccessible = new ArrayList<>();
			final Consumer<String> collect = inaccessible::add;
			try (Stream<RemoteFileInfo> entries = stream(collect.andThen(onInaccessible))) {
				return new RemoteFileList(entries.collect(Collectors.toList()), inaccessible);
			}
		});
	}

	/**
	 * List the directory and stream the entries as the host reports them: memory stays bounded
	 * whatever the size of the tree.
	 *
	 * <pre>{@code
	 * try (Stream<RemoteFileInfo> tree = client.file("D:\\data").list().recursive().stream()) {
	 * 	tree.filter(RemoteFileInfo::isDirectory).forEach(System.out::println);
	 * }
	 * }</pre>
	 * <p>
	 * <b>The stream must be closed</b> — use try-with-resources. It holds the client's connection
	 * until it is exhausted or closed; closing it early stops the remote walk. The listing starts
	 * here, and the failures below are thrown from the stream's operations, when the host reports
	 * them. The timeout is an inactivity timeout (see {@link #timeout(Duration)}).
	 *
	 * @return a lazy, sequential stream of entries, to use with try-with-resources
	 * @throws WinRMClientException when the directory cannot be listed
	 * @throws WinRMTimeoutException when the host does not answer in time
	 */
	public Stream<RemoteFileInfo> stream() {
		return stream(onInaccessible);
	}

	private Stream<RemoteFileInfo> stream(final Consumer<String> inaccessible) {
		final String script = RemoteFiles.listScript(
			path,
			glob,
			type,
			minSize,
			maxSize,
			after,
			before,
			recursive ? maxDepth : 1
		);
		final RemoteProcess process = RemoteFiles.start(client, path, script, timeout);
		final BufferedReader stdout = process.stdout();
		final String hostname = client.hostname();
		final Spliterator<RemoteFileInfo> spliterator = new Spliterators.AbstractSpliterator<RemoteFileInfo>(
			Long.MAX_VALUE,
			Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE
		) {
			private boolean ended;

			@Override
			public boolean tryAdvance(final Consumer<? super RemoteFileInfo> action) {
				while (!ended) {
					final String line = RemoteFiles.readLine(stdout);
					if (line == null) {
						ended = true;
						RemoteFiles.finish(process, path, hostname, false);
					} else if (line.startsWith("!")) {
						inaccessible.accept(RemoteFiles.parseInaccessible(line.strip()));
					} else if (!line.isBlank()) {
						action.accept(RemoteFiles.parseEntry(line.strip()));
						return true;
					}
				}
				return false;
			}
		};
		return StreamSupport.stream(spliterator, false).onClose(process::close);
	}
}
