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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;

/**
 * A cursor over the raw output of a running remote command, returned by
 * {@link WindowsRemoteExecutor#startCommand(String, String, long)}. Each {@link #next()} is one
 * WSMan Receive round trip yielding the output bytes exactly as the server handed them out —
 * undecoded, because a multibyte character can be split across chunks; decode with a stateful
 * {@link java.nio.charset.CharsetDecoder} (or accumulate the bytes and decode once at the end).
 * <p>
 * The cursor owns the executor's serial connection until the command completes or the cursor is
 * closed: no other operation can run on the same executor while the cursor is open. Completion
 * (a {@code null} return from {@link #next()}) sends the protocol's terminate Signal and releases
 * the connection on its own; closing earlier sends the same Signal, which actually stops the
 * still-running remote command. Always close the cursor — use try-with-resources.
 * <p>
 * A cursor is not thread-safe: advance and close it from one thread at a time.
 */
public interface CommandCursor extends AutoCloseable {
	/**
	 * Block until the remote command produces output (or completes), for at most one
	 * per-round-trip timeout.
	 *
	 * @return the next chunk of raw output — possibly empty — or {@code null} once the command has
	 *         completed; the exit code is then available from {@link #exitCode()}
	 * @throws TimeoutException when the command produces no output for a whole per-round-trip
	 *         timeout (the inactivity timeout of the stream)
	 * @throws WindowsRemoteException for any other failure while receiving
	 */
	Chunk next() throws TimeoutException, WindowsRemoteException;

	/**
	 * Bounded variant of {@link #next()}: block at most the given wait for output. When the
	 * command produces nothing in that window, an <b>empty</b> chunk is returned — a bounded poll
	 * expiring is not a failure, and the cursor remains fully usable — unlike {@link #next()},
	 * whose whole per-round-trip timeout counts as the stream's inactivity limit. Deadline-bounded
	 * waits (e.g. {@code RemoteProcess.waitFor(Duration)}) are built on this.
	 * <p>
	 * The default implementation does not bound the wait: it delegates to {@link #next()}.
	 *
	 * @param maxWaitMillis how long to block at most, capped by the cursor's per-round-trip timeout
	 * @return the next chunk of raw output — empty when the wait elapsed first — or {@code null}
	 *         once the command has completed
	 * @throws TimeoutException when the server does not even answer the bounded request
	 * @throws WindowsRemoteException for any other failure while receiving
	 */
	default Chunk poll(final long maxWaitMillis) throws TimeoutException, WindowsRemoteException {
		return next();
	}

	/**
	 * Get the command's exit code.
	 *
	 * @return the exit code
	 * @throws IllegalStateException when the command has not completed yet — completion is
	 *         observed as a {@code null} return from {@link #next()}
	 */
	int exitCode();

	/**
	 * Terminate the command (when it is still running) with the WinRM terminate Signal and release
	 * the executor's connection. Idempotent; a no-op when the command already completed. After an
	 * early close, {@link #next()} returns {@code null} without touching the connection again (and
	 * no exit code is available, since the command never completed). May throw an unchecked
	 * {@link org.metricshub.winrm.exceptions.WinRMClientException} when the Signal itself fails —
	 * the remote command may then still be running.
	 */
	@Override
	void close();

	/** One Receive response's worth of raw output bytes, split by stream. */
	final class Chunk {

		private final byte[] stdout;
		private final byte[] stderr;

		/**
		 * Create a chunk over the given stream bytes (not copied: a chunk is a transient carrier
		 * between the protocol loop and the decoder, not a retained value).
		 *
		 * @param stdout the raw stdout bytes of this chunk (possibly empty, never null)
		 * @param stderr the raw stderr bytes of this chunk (possibly empty, never null)
		 */
		@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Chunks are transient carriers on the output hot path; defensive copies "
			+
			"would double the allocation for no benefit")
		public Chunk(final byte[] stdout, final byte[] stderr) {
			this.stdout = stdout;
			this.stderr = stderr;
		}

		/**
		 * Get the raw stdout bytes of this chunk.
		 *
		 * @return the stdout bytes, possibly empty
		 */
		@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Chunks are transient carriers on the output hot path; defensive copies "
			+
			"would double the allocation for no benefit")
		public byte[] stdout() {
			return stdout;
		}

		/**
		 * Get the raw stderr bytes of this chunk.
		 *
		 * @return the stderr bytes, possibly empty
		 */
		@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Chunks are transient carriers on the output hot path; defensive copies "
			+
			"would double the allocation for no benefit")
		public byte[] stderr() {
			return stderr;
		}
	}
}
