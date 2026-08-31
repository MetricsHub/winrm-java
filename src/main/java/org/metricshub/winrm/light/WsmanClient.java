package org.metricshub.winrm.light;

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLSocketFactory;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.exceptions.WinRMAuthenticationException;
import org.metricshub.winrm.exceptions.WinRMFaultException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Dependency-free WinRM/WS-Management client: NTLM (masqueraded as Negotiate) with message
 * encryption over HTTP, templated SOAP envelopes, and JDK-default XML parsing (no Apache CXF,
 * JAX-WS, JAXB, or Woodstox). Supports Identify, WQL queries, and command execution.
 */
final class WsmanClient implements AutoCloseable {

	// If no output is available before the OperationTimeout expires, the server returns this WSMan
	// fault code and the client is expected to immediately re-issue the Receive request.
	private static final String FAULT_OPERATION_TIMEOUT = "2150858793";
	private static final String FAULT_SHELL_NOT_FOUND = "2150858843";

	// The WSMan service clamps an OperationTimeout below 500 ms UP to 500 ms (MS-WSMV; measured on
	// Windows Server 2008 R2): a bounded Receive's "nothing yet" fault never arrives before this
	// floor, however early the header asks for it.
	private static final long MIN_OPERATION_TIMEOUT_MS = 500;

	// A bounded poll shorter than this cannot be honored by a network round trip: the server
	// answers no earlier than MIN_OPERATION_TIMEOUT_MS, and the fault needs transit slack to beat
	// the socket cut at the budget. Shorter waits are waited out locally instead of going to the
	// wire.
	private static final long MIN_WIRE_POLL_MS = 750;

	// WS-Enumeration namespace: the EndOfSequence / EnumerationContext markers live here. Match them by
	// namespace, never by local name alone, so a WMI property that happens to be named "EndOfSequence"
	// or "EnumerationContext" inside <Items> cannot be mistaken for the enumeration control element.
	private static final String WS_ENUMERATION_NS = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";

	// WinRM also emits the Items / EndOfSequence markers in its own WSMan namespace (the wsman:Items /
	// wsman:EndOfSequence variants); the CXF backend accepts both, so the light backend must too.
	private static final String WSMAN_NS = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	private final long timeoutMs;
	private final int consoleCodePage;
	private final String url;
	private final String rawUsername;
	private final AuthScheme auth;
	private final HttpTransport transport;

	// Opt-in retry policy for transient connection failures (issue #158): how many times one round
	// trip may re-attempt to establish and authenticate the connection, and the pause before each
	// attempt. Applies ONLY to failures where the round trip's request provably never reached the
	// server (see send()); 0 retries — the default — keeps the historical fail-fast behavior.
	private final int connectRetries;
	private final long retryDelayMs;

	private String pendingAuthorization;
	private String shellId;

	// The shell's working directory and environment variables are pinned by the FIRST command on
	// this connection and reused whenever the shell must be (re)created — e.g. after the server
	// reaped it — so a recreation stays invisible to the caller instead of silently moving later
	// commands to the default directory or environment. Guarded by connectionPermit, like shellId.
	private String shellWorkingDirectory;
	private Map<String, String> shellEnvironment;
	private boolean shellSettingsPinned;

	// A single NTLM connection is a serial channel: one socket, stateful RC4 ciphers with sequence
	// numbers, and a single shellId. Concurrent callers (e.g. one executor shared across
	// threads) MUST NOT interleave, or they read each other's responses and desync the cipher streams.
	// Every high-level operation (wql/executeCommand) and every open streaming handle
	// (WqlEnumeration/RemoteCommand) runs while holding this single permit; close() only tries it,
	// so it can still hard-close the transport to unblock an abandoned, timed-out worker. A
	// Semaphore rather than a ReentrantLock because a streaming handle may legitimately be advanced
	// and closed by a different thread than the one that opened it (a lock could then not be
	// released at all — unlock is owner-only).
	private final Semaphore connectionPermit = new Semaphore(1);

	// Set (before anything else) by close(): a straggler — an abandoned worker or a streaming
	// handle outliving the client — must never send another request, because request() would
	// happily reconnect and re-authenticate the hard-closed transport, reviving a connection
	// nothing will ever close again. Volatile: close() may run on another thread.
	private volatile boolean closed;

	/**
	 * Acquire {@link #connectionPermit}, aborting when this task has been cancelled. A caller's
	 * wall-clock timeout can fire while its operation is still QUEUED behind another one on this
	 * serial connection; the timeout path then cancels (interrupts) the worker thread, which must
	 * NOT go on to acquire the permit and execute the operation the caller was already told timed
	 * out — a command would run its side effects after the failure was reported. Interruption
	 * while waiting aborts the acquisition; an interrupt that arrived just before or during the
	 * acquisition is detected right after it, before anything is sent.
	 */
	private void lockAbortably() throws InterruptedException {
		connectionPermit.acquire();
		if (Thread.interrupted()) {
			connectionPermit.release();
			throw new InterruptedException("Operation abandoned: cancelled while waiting for the connection.");
		}
	}

	/**
	 * Abort between protocol steps when this task has been cancelled. A classic socket read does
	 * not observe the interrupt the timeout path delivers: a worker blocked in (say) the Create
	 * shell response can outlive its caller's timeout and would otherwise go on to the next step —
	 * sending a command after the caller was already told the operation timed out. Checked before
	 * every step with side effects.
	 */
	private static void checkNotCancelled() throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException("Operation abandoned: cancelled after its timeout was reported.");
		}
	}

	WsmanClient(
		final String host,
		final int port,
		final long timeoutMs,
		final SSLSocketFactory sslSocketFactory,
		final boolean verifyHostname,
		final AuthScheme auth,
		final String rawUsername,
		final int consoleCodePage,
		final int connectRetries,
		final long retryDelayMs
	) {
		this.timeoutMs = timeoutMs;
		this.consoleCodePage = consoleCodePage;
		this.connectRetries = connectRetries;
		this.retryDelayMs = retryDelayMs;
		// A non-null socket factory selects HTTPS: TLS wraps the transport and the SOAP travels plaintext.
		this.url = (sslSocketFactory != null ? "https" : "http") + "://" + host + ":" + port + "/wsman";
		this.rawUsername = rawUsername;
		this.auth = auth;
		this.transport = new HttpTransport(host, port, toSocketTimeoutMillis(timeoutMs), sslSocketFactory, verifyHostname);
	}

	/**
	 * Convert the public {@code long} timeout to the {@code int} milliseconds a {@link java.net.Socket}
	 * accepts. Clamp so a large but valid timeout never narrows to a negative/garbage value, leaving
	 * headroom for the extra read-timeout seconds {@link HttpTransport} adds. The full {@code long}
	 * remains authoritative for the WSMan OperationTimeout and the wall-clock deadline in the service.
	 */
	private static int toSocketTimeoutMillis(final long millis) {
		return (int) Math.min(millis, Integer.MAX_VALUE - 10_000L);
	}

	/**
	 * Align the transport's socket timeouts with the operation being opened. Blocking operations
	 * keep the read-timeout headroom (their caller's wall-clock deadline governs, and the WSMan
	 * op-timeout fault must arrive before the socket gives up so the Receive loop can retry);
	 * streaming operations must observe the configured inactivity timeout on the socket itself —
	 * a server that stops answering entirely would otherwise be detected ten seconds late.
	 */
	private void configureTimeouts(final long operationTimeoutMs, final boolean failOnQuietTimeout) {
		final int millis = toSocketTimeoutMillis(operationTimeoutMs);
		if (failOnQuietTimeout) {
			transport.inactivityTimeout(millis);
		} else {
			transport.operationTimeout(millis);
		}
	}

	/** A decrypted WSMan response: HTTP status plus the (decrypted) SOAP body. */
	private static final class Decoded {

		final int status;
		final Document document;

		Decoded(final int status, final Document document) {
			this.status = status;
			this.document = document;
		}
	}

	/**
	 * Run a WQL query and return the rows as ordered property maps. Implemented as "drain the
	 * stream" over {@link #openWql} so the blocking and streaming paths cannot drift apart.
	 *
	 * @param namespace the WMI namespace
	 * @param query the WQL query
	 * @param operationTimeoutMs this operation's timeout, driving the WSMan OperationTimeout header
	 *        and the socket read timeout
	 * @param maxElements the WS-Enumeration MaxElements batch size for Enumerate and every Pull
	 * @param maxTimeMs the WS-Enumeration MaxTime for each Pull in milliseconds; 0 omits the element
	 */
	List<Map<String, String>> wql(
		final String namespace,
		final String query,
		final long operationTimeoutMs,
		final int maxElements,
		final long maxTimeMs
	) throws Exception {
		final List<Map<String, String>> rows = new ArrayList<>();
		try (WqlEnumeration enumeration = openWql(namespace, query, operationTimeoutMs, maxElements, maxTimeMs, false)) {
			Map<String, String> row;
			while ((row = enumeration.next()) != null) {
				rows.add(row);
			}
		}
		return rows;
	}

	/**
	 * Start a WQL enumeration and return a lazy handle over its rows. The handle owns the
	 * connection (see {@link #connectionPermit}) until it is exhausted or closed: no other
	 * operation can run on this client while it is open.
	 *
	 * @param namespace the WMI namespace
	 * @param query the WQL query
	 * @param operationTimeoutMs each WSMan round trip's timeout, driving the OperationTimeout
	 *        header and the socket read timeout — for a streaming consumer this is the inactivity
	 *        timeout: the longest silence tolerated between two responses
	 * @param maxElements the WS-Enumeration MaxElements batch size for Enumerate and every Pull
	 * @param maxTimeMs the WS-Enumeration MaxTime for each Pull in milliseconds; 0 omits the element
	 * @param failOnQuietTimeout streaming mode: convert a server "no result yet" operation-timeout
	 *        fault or a socket read timeout on Pull into a {@link TimeoutException} instead of
	 *        letting the raw fault/IO failure surface (the blocking path is bounded by the caller's
	 *        wall-clock deadline instead)
	 */
	WqlEnumeration openWql(
		final String namespace,
		final String query,
		final long operationTimeoutMs,
		final int maxElements,
		final long maxTimeMs,
		final boolean failOnQuietTimeout
	) throws Exception {
		// Serialize the whole enumeration (Enumerate + all Pulls + Release) against any other
		// operation sharing this connection; see connectionPermit.
		lockAbortably();
		boolean opened = false;
		try {
			configureTimeouts(operationTimeoutMs, failOnQuietTimeout);
			// WMI namespaces are case-insensitive, but preserve the caller's case to match the CXF backend.
			final String ns = namespace.replace('\\', '/');
			final WqlEnumeration enumeration = new WqlEnumeration(
				ns,
				operationTimeoutMs,
				maxElements,
				maxTimeMs,
				failOnQuietTimeout
			);
			enumeration.ingest(
				exchange(
					Envelopes.enumerateWql(url, ns, query, operationTimeoutMs, maxElements),
					"Enumerate",
					operationTimeoutMs,
					failOnQuietTimeout
				)
			);
			opened = true;
			return enumeration;
		} finally {
			if (!opened) {
				connectionPermit.release();
			}
		}
	}

	/**
	 * A lazily-advancing WQL enumeration: rows are served from the current WS-Enumeration page and
	 * the next Pull is issued only when the page runs out, so memory stays bounded by one page.
	 * Holds {@link #connectionPermit} from creation until exhaustion or {@link #close()}; closing
	 * before the end sends a WS-Enumeration Release so the server frees the enumeration context.
	 */
	final class WqlEnumeration implements AutoCloseable {

		private final String namespace;
		private final long operationTimeoutMs;
		private final int maxElements;
		private final long maxTimeMs;
		private final boolean failOnQuietTimeout;

		// The current page only: previous pages (rows and DOM) are unreachable once served.
		private List<Map<String, String>> page = new ArrayList<>();
		private int cursor;
		private String context;
		private boolean endOfSequence;
		private boolean finished;

		// Set when an advance failed: the connection state is then unknown (a fault, a half-read
		// response, a cancellation), so close() must not push a Release into it — it releases the
		// permit only, exactly like the pre-streaming code did on its error paths.
		private boolean broken;

		private WqlEnumeration(
			final String namespace,
			final long operationTimeoutMs,
			final int maxElements,
			final long maxTimeMs,
			final boolean failOnQuietTimeout
		) {
			this.namespace = namespace;
			this.operationTimeoutMs = operationTimeoutMs;
			this.maxElements = maxElements;
			this.maxTimeMs = maxTimeMs;
			this.failOnQuietTimeout = failOnQuietTimeout;
		}

		/** Absorb one Enumerate/Pull response: its rows become the current page. */
		private void ingest(final Document doc) {
			page = new ArrayList<>();
			cursor = 0;
			collectItems(doc, page);
			endOfSequence = hasEnumerationElement(doc, "EndOfSequence");
			// Pull only while the server hands back a context (matching the CXF backend).
			context = endOfSequence ? null : textNS(doc, WS_ENUMERATION_NS, "EnumerationContext");
			if (context == null || context.isEmpty()) {
				endOfSequence = true;
			}
		}

		/**
		 * The next row, or {@code null} once the enumeration is exhausted. Exhaustion releases the
		 * connection immediately (no Release is needed — the server discarded the context when it
		 * sent EndOfSequence), so a fully-consumed enumeration does not depend on {@link #close()}.
		 */
		Map<String, String> next() throws Exception {
			try {
				return advance();
			} catch (final Exception e) {
				broken = true;
				throw e;
			}
		}

		private Map<String, String> advance() throws Exception {
			if (finished) {
				return null;
			}
			while (cursor >= page.size()) {
				if (endOfSequence) {
					finished = true;
					connectionPermit.release();
					return null;
				}
				// Stop pulling once the caller has been told the operation timed out.
				checkNotCancelled();
				ingest(
					exchange(
						Envelopes.pull(url, namespace, context, operationTimeoutMs, maxElements, maxTimeMs),
						"Pull",
						operationTimeoutMs,
						failOnQuietTimeout
					)
				);
			}
			return page.get(cursor++);
		}

		/**
		 * Release the enumeration: when the server still holds an enumeration context, a
		 * best-effort WS-Enumeration Release lets it free the context (and the operation slot it
		 * counts against server-side quotas) immediately. Always releases the connection; idempotent.
		 */
		@Override
		public void close() {
			if (finished) {
				return;
			}
			finished = true;
			try {
				// No Release when the whole client was closed while this handle was open: its transport
				// is gone, and the request would reconnect and re-authenticate just to be thrown away.
				if (!broken && !closed && !endOfSequence && context != null && !context.isEmpty()) {
					try {
						request(Envelopes.release(url, namespace, context, operationTimeoutMs));
					} catch (final Exception ignored) {
						// Best-effort cleanup: the server reaps an unreleased context on its own timeout.
					}
				}
			} finally {
				connectionPermit.release();
			}
		}
	}

	/** The result of running a command in the remote shell. */
	static final class CommandOutput {

		final String stdout;
		final String stderr;
		final int exitCode;

		CommandOutput(final String stdout, final String stderr, final int exitCode) {
			this.stdout = stdout;
			this.stderr = stderr;
			this.exitCode = exitCode;
		}
	}

	/**
	 * Execute a command in the remote command shell, creating the shell on first use. Implemented
	 * as "drain the stream" over {@link #startCommand} so the blocking and streaming paths cannot
	 * drift apart: the raw stream BYTES are accumulated and decoded once at the end, because a
	 * multibyte character (e.g. UTF-8) can be split across Stream elements or Receive responses,
	 * and decoding each chunk independently would corrupt the boundary bytes into replacement
	 * characters.
	 *
	 * @param commandLine the command line to run
	 * @param workingDirectory working directory of the shell (only honored when the shell is created)
	 * @param environment environment variables of the shell (only honored when the shell is created)
	 * @param charset the charset decoding the output streams; {@code null} uses
	 *        {@link WindowsRemoteExecutor#SHELL_OUTPUT_CHARSET}
	 * @param operationTimeoutMs this operation's timeout, driving the WSMan OperationTimeout header
	 *        and the socket read timeout
	 */
	CommandOutput executeCommand(
		final String commandLine,
		final String workingDirectory,
		final Map<String, String> environment,
		final Charset charset,
		final long operationTimeoutMs
	) throws Exception {
		final Charset cs = charset != null ? charset : WindowsRemoteExecutor.SHELL_OUTPUT_CHARSET;
		final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		try (
			RemoteCommand command = startCommand(
				commandLine,
				workingDirectory,
				environment,
				operationTimeoutMs,
				false,
				true
			)) {
			RemoteCommand.Chunk chunk;
			while ((chunk = command.nextChunk()) != null) {
				stdout.write(chunk.stdout, 0, chunk.stdout.length);
				stderr.write(chunk.stderr, 0, chunk.stderr.length);
			}
			return new CommandOutput(
				new String(stdout.toByteArray(), cs),
				new String(stderr.toByteArray(), cs),
				command.exitCode()
			);
		}
	}

	/**
	 * Start a command in the remote command shell (creating the shell on first use) and return a
	 * handle over its raw output chunks. The handle owns the connection (see
	 * {@link #connectionPermit}) until the command completes or the handle is closed: no other
	 * operation can run on this client while it is open.
	 *
	 * @param commandLine the command line to run
	 * @param workingDirectory working directory of the shell (only honored when the shell is created)
	 * @param environment environment variables of the shell (only honored when the shell is created)
	 * @param operationTimeoutMs each WSMan round trip's timeout, driving the OperationTimeout
	 *        header and the socket read timeout — for a streaming consumer this is the inactivity
	 *        timeout: the longest silence tolerated between two responses
	 * @param failOnQuietTimeout streaming mode: convert a server "no output yet" operation-timeout
	 *        fault or a socket read timeout into a {@link TimeoutException} instead of re-issuing
	 *        the Receive forever (the blocking path is bounded by the caller's wall-clock deadline
	 *        instead)
	 * @param consoleModeStdin value of the {@code WINRS_CONSOLEMODE_STDIN} option; see
	 *        {@link Envelopes#command(String, String, String, long, boolean)}
	 */
	RemoteCommand startCommand(
		final String commandLine,
		final String workingDirectory,
		final Map<String, String> environment,
		final long operationTimeoutMs,
		final boolean failOnQuietTimeout,
		final boolean consoleModeStdin
	) throws Exception {
		// Serialize the whole shell lifecycle (Create + Command + Receive loop + Signal) against any
		// other operation sharing this connection and the shellId field; see connectionPermit.
		lockAbortably();
		boolean opened = false;
		try {
			configureTimeouts(operationTimeoutMs, failOnQuietTimeout);
			if (!shellSettingsPinned) {
				shellWorkingDirectory = workingDirectory;
				// A defensive copy: a recreation must replay exactly what the first command set, not
				// whatever the caller's map contains by then.
				shellEnvironment = environment == null || environment.isEmpty()
					? null
					: new LinkedHashMap<>(environment);
				shellSettingsPinned = true;
			}
			if (shellId == null) {
				createShell(shellWorkingDirectory, shellEnvironment, operationTimeoutMs, failOnQuietTimeout);
			}
			// The caller's timeout may have fired while the Create response was being awaited (socket
			// reads do not observe interrupts): never START the command after the reported timeout.
			checkNotCancelled();
			String commandId;
			try {
				commandId = sendCommand(commandLine, operationTimeoutMs, failOnQuietTimeout, consoleModeStdin);
			} catch (final WinRMFaultException e) {
				if (!FAULT_SHELL_NOT_FOUND.equals(e.getFaultCode())) {
					throw e;
				}
				// The server reaped the cached shell between commands (e.g. its IdleTimeout expired on a
				// long-lived client). The Command was rejected before it could run, so it is safe to
				// recreate the shell — with its ORIGINAL working directory and environment — and retry
				// once.
				shellId = null;
				createShell(shellWorkingDirectory, shellEnvironment, operationTimeoutMs, failOnQuietTimeout);
				checkNotCancelled();
				commandId = sendCommand(commandLine, operationTimeoutMs, failOnQuietTimeout, consoleModeStdin);
			}
			opened = true;
			return new RemoteCommand(commandId, operationTimeoutMs, failOnQuietTimeout);
		} finally {
			if (!opened) {
				connectionPermit.release();
			}
		}
	}

	/**
	 * A running remote command: each {@link #nextChunk()} is one WSMan Receive round trip yielding
	 * the raw output bytes as the server handed them out, so memory stays bounded by one response.
	 * Holds {@link #connectionPermit} from creation until completion or {@link #close()}; both
	 * paths send the terminate Signal, exactly like the pre-streaming receive loop did — Signal
	 * after completion is part of the shell protocol, and Signal on early close is what actually
	 * stops the remote command. The one exception is completion discovered inside a bounded poll
	 * (see {@link #finishBounded}), whose Signal is bounded by — or skipped for — the caller's
	 * remaining wait.
	 */
	final class RemoteCommand implements AutoCloseable {

		/** One Receive response's worth of raw output bytes, split by stream. */
		final class Chunk {

			final byte[] stdout;
			final byte[] stderr;

			Chunk(final byte[] stdout, final byte[] stderr) {
				this.stdout = stdout;
				this.stderr = stderr;
			}
		}

		private final String commandId;
		private final long operationTimeoutMs;
		private final boolean failOnQuietTimeout;
		private Integer exitCode;
		private boolean finished;

		// The end-of-input Send was delivered: the remote stdin reached EOF, later sends are a
		// caller bug and are rejected locally instead of drawing a server fault.
		private boolean stdinEnded;

		private RemoteCommand(final String commandId, final long operationTimeoutMs, final boolean failOnQuietTimeout) {
			this.commandId = commandId;
			this.operationTimeoutMs = operationTimeoutMs;
			this.failOnQuietTimeout = failOnQuietTimeout;
		}

		/**
		 * The next chunk of raw output — one Receive round trip, possibly empty — or {@code null}
		 * once the command has completed. The {@code null} return has already sent the terminate
		 * Signal and released the connection, so a fully-drained command does not depend on
		 * {@link #close()}; the exit code is then available from {@link #exitCode()}.
		 */
		Chunk nextChunk() throws Exception {
			if (finished) {
				// Already signaled — normally after completion, but also after an early close(): the
				// connection was released either way, so never touch it again from this handle.
				return null;
			}
			if (exitCode != null) {
				// The command completed with the previous chunk: Signal it and release the connection.
				finish();
				return null;
			}
			return toChunk(receiveOutput());
		}

		/**
		 * Bounded variant of {@link #nextChunk()}: block at most the given wait — a hard bound. A
		 * wire poll asks the server to answer EARLIER than the wait (the difference is transit
		 * slack for its "nothing yet" op-timeout fault to reach us before the socket cuts at the
		 * full wait); that fault is returned as an EMPTY chunk instead of failing the handle —
		 * the protocol's clean expiry, leaving the command and this handle fully usable. A wait
		 * too short for any network round trip is waited out locally instead. Returns {@code null}
		 * exactly like {@link #nextChunk()} once the command has completed.
		 *
		 * @param maxWaitMs how long to block at most, capped by the handle's own per-round-trip
		 *        timeout
		 */
		Chunk pollChunk(final long maxWaitMs) throws Exception {
			return pollChunk(maxWaitMs, maxWaitMs);
		}

		/**
		 * Cadence variant: ask the server to answer within {@code askMs} (the polling cadence),
		 * while allowing the answer itself up to {@code maxWaitMs} to arrive — a polling consumer
		 * (the interactive shell pump) wants short idle rounds without failing the session when a
		 * loaded or distant server takes longer than one cadence to get its answer across. The
		 * hard-bound variant above is simply {@code askMs == maxWaitMs}.
		 *
		 * @param askMs when the server should answer at the latest (its Receive hold)
		 * @param maxWaitMs how long to block at most, capped by the handle's own per-round-trip
		 *        timeout
		 */
		Chunk pollChunk(final long askMs, final long maxWaitMs) throws Exception {
			if (finished) {
				return null;
			}
			if (exitCode != null) {
				// The command completed with the previous chunk: Signal it — but under the poll's
				// budget, never the full inactivity timeout of the plain fetches.
				finishBounded(maxWaitMs);
				return null;
			}
			// The per-round-trip timeout caps the poll, strictly: a bounded wait must never outlast
			// the inactivity tolerance its caller configured.
			final long budget = Math.max(1, Math.min(maxWaitMs, operationTimeoutMs));
			if (budget < MIN_WIRE_POLL_MS) {
				// Less than any network round trip can honor — because the caller asked for it, or
				// because the handle's own per-round-trip timeout is below the protocol's floor.
				// Waiting the budget out locally is the only way to honor it; the protocol advances
				// on the next full-size poll or on an unbounded fetch, whose socket budget is the
				// same per-round-trip timeout.
				Thread.sleep(budget);
				return new Chunk(new byte[0], new byte[0]);
			}
			// Split the budget: the server may hold the Receive for the first part (never more
			// than the requested cadence), and the rest is transit slack for its "nothing yet"
			// op-timeout fault to arrive BEFORE the socket cuts at the full budget — the expected
			// expiry of a bounded poll is that fault, and it must win the race or the poll would
			// desync the connection it is supposed to leave intact. The hold never asks for less
			// than the service's own floor: the answer would not come any earlier, and the
			// requested timeout should reflect when the server may answer.
			final long transit = Math.min(1_000, budget / 2);
			final long hold = Math.max(MIN_OPERATION_TIMEOUT_MS, Math.min(askMs, budget - transit));
			transport.pollTimeout(toSocketTimeoutMillis(budget));
			try {
				checkNotCancelled();
				final Decoded resp;
				try {
					resp = request(Envelopes.receive(url, shellId, commandId, hold));
				} catch (final SocketTimeoutException e) {
					// The peer answered neither within its shortened hold nor within the transit
					// slack. The Receive is abandoned mid-flight, so drop the connection outright: a
					// late response must not be readable as the answer to a LATER request.
					transport.close();
					throw quietTimeout("No response from the WinRM service", budget, e);
				}
				if (resp.status != 200) {
					if (FAULT_OPERATION_TIMEOUT.equals(wsmanFaultCode(resp.document))) {
						// Nothing yet: the bounded wait elapsed server-side.
						return new Chunk(new byte[0], new byte[0]);
					}
					throw faultException("Receive", resp);
				}
				return toChunk(resp);
			} finally {
				// Back to the strict streaming bound for the ordinary (unbounded) fetches.
				transport.inactivityTimeout(toSocketTimeoutMillis(operationTimeoutMs));
			}
		}

		/**
		 * Feed standard input to the running command: one or more WSMan Send requests carrying the
		 * bytes as base64 {@code stdin} streams, the last one flagged {@code End} when {@code end} is
		 * set. Payloads larger than {@link Envelopes#MAX_STDIN_CHUNK} are split so no envelope
		 * exceeds the MaxEnvelopeSize the client advertises; the split happens on the ENCODED bytes,
		 * so a multibyte character straddling two Sends is reassembled by the remote pipe.
		 * <p>
		 * A Send is an ordinary request under this handle's connection permit: it does not interleave
		 * with the Receive loop, it alternates with it on the caller's thread — the same discipline
		 * {@link java.lang.Process} pipes require.
		 *
		 * @param data the input bytes (may be empty, e.g. for a pure end-of-input Send)
		 * @param end whether this is the last input the command will get
		 */
		void send(final byte[] data, final boolean end) throws Exception {
			if (finished || exitCode != null) {
				throw new IllegalStateException("The command has completed: its standard input is closed.");
			}
			if (stdinEnded) {
				throw new IllegalStateException("The command's standard input has already been closed.");
			}
			if (data.length == 0 && !end) {
				// Nothing to say and no EOF to announce: an empty Send would be a pure round trip.
				return;
			}
			int offset = 0;
			do {
				checkNotCancelled();
				final int length = Math.min(Envelopes.MAX_STDIN_CHUNK, data.length - offset);
				final boolean last = offset + length >= data.length;
				final String base64 = length == 0
					? ""
					: Base64.getEncoder().encodeToString(Arrays.copyOfRange(data, offset, offset + length));
				// The same streaming timeout translation as the Receive loop: a server staying
				// quiet for a whole inactivity timeout is the documented TimeoutException, not a
				// raw socket failure or fault.
				exchange(
					Envelopes.send(url, shellId, commandId, base64, end && last, operationTimeoutMs),
					"Send",
					operationTimeoutMs,
					failOnQuietTimeout
				);
				offset += length;
			} while (offset < data.length);
			if (end) {
				stdinEnded = true;
			}
		}

		/**
		 * Interrupt the command's child process the way a console Ctrl+C would, WITHOUT terminating
		 * the command or its shell: the session stays usable afterward, which is what an interactive
		 * shell needs. A missing shell is tolerated, exactly like the terminate Signal.
		 */
		void interrupt() throws Exception {
			// Nothing to interrupt once the command completed (or the handle was closed): the child
			// is gone, and the Signal would only draw a fault.
			if (finished || exitCode != null) {
				return;
			}
			checkNotCancelled();
			try {
				signal(commandId, Envelopes.CTRL_C_CODE, operationTimeoutMs);
			} catch (final SocketTimeoutException e) {
				// Same streaming translation as every other round trip: a server staying quiet
				// for a whole inactivity timeout is the documented TimeoutException.
				if (failOnQuietTimeout) {
					throw quietTimeout("The interrupt Signal was not answered", operationTimeoutMs, e);
				}
				throw e;
			} catch (final WinRMFaultException e) {
				if (failOnQuietTimeout && FAULT_OPERATION_TIMEOUT.equals(e.getFaultCode())) {
					throw quietTimeout("The interrupt Signal was not answered", operationTimeoutMs, null);
				}
				throw e;
			}
		}

		/** Turn one 200 Receive response into a chunk, recording the exit code when it says Done. */
		private Chunk toChunk(final Decoded resp) {
			final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
			final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
			collectStreams(resp.document, stdout, stderr);
			exitCode = doneExitCode(resp.document);
			return new Chunk(stdout.toByteArray(), stderr.toByteArray());
		}

		/** Issue Receive until a usable response arrives, honoring the timeout mode. */
		private Decoded receiveOutput() throws Exception {
			while (true) {
				// A late non-final response (or an op-timeout fault) must not keep an abandoned worker
				// re-issuing Receive — and holding the serial connection — until the remote command ends.
				// Aborting here still sends the Signal (via close), which terminates the remote command.
				checkNotCancelled();
				final Decoded resp;
				try {
					resp = request(Envelopes.receive(url, shellId, commandId, operationTimeoutMs));
				} catch (final SocketTimeoutException e) {
					if (failOnQuietTimeout) {
						throw quietTimeout("No response from the WinRM service", operationTimeoutMs, e);
					}
					throw e;
				}
				if (resp.status == 200) {
					return resp;
				}
				if (!FAULT_OPERATION_TIMEOUT.equals(wsmanFaultCode(resp.document))) {
					throw faultException("Receive", resp);
				}
				// No output before OperationTimeout expired. The blocking path re-issues the Receive
				// immediately (its caller's wall-clock deadline governs); for a streaming consumer
				// that silence IS the inactivity timeout.
				if (failOnQuietTimeout) {
					throw quietTimeout("The command produced no output", operationTimeoutMs, null);
				}
			}
		}

		/**
		 * The command's exit code, once {@link #nextChunk()} has returned {@code null}.
		 */
		int exitCode() {
			if (exitCode == null) {
				throw new IllegalStateException("The command has not completed yet.");
			}
			return exitCode;
		}

		/**
		 * Signal the command (terminate) and release the connection; runs at most once. For a
		 * still-running command (an early close) the Signal is what actually stops it, so its
		 * failures are reported; once the command has COMPLETED the Signal is best-effort cleanup
		 * (see {@link #terminateCompleted}) — a completed command with a known exit code must
		 * never turn into a failure because its acknowledgement hiccuped.
		 */
		private void finish() throws Exception {
			if (finished) {
				return;
			}
			finished = true;
			try {
				// No Signal when the whole client was closed while this handle was open: its transport
				// is gone, and the request would reconnect and re-authenticate just to be thrown away —
				// the server reaps the shell (and its commands) on its own IdleTimeout instead.
				if (!closed) {
					if (exitCode != null) {
						terminateCompleted(operationTimeoutMs);
					} else {
						terminate(commandId, operationTimeoutMs);
					}
				}
			} finally {
				connectionPermit.release();
			}
		}

		/**
		 * Completion cleanup under a poll budget: like {@link #finish()} after completion, but the
		 * Signal must not outlive the caller's remaining wait either. Runs at most once.
		 */
		private void finishBounded(final long budgetMs) {
			if (finished) {
				return;
			}
			finished = true;
			try {
				if (!closed) {
					terminateCompleted(budgetMs);
				}
			} finally {
				connectionPermit.release();
			}
		}

		/**
		 * Best-effort Signal for an ALREADY-COMPLETED command, bounded by the given budget. No
		 * failure of it may be reported: the command completed and its exit code is known, and
		 * that must never be hidden behind a cleanup hiccup. A fault answering the Signal is a
		 * complete, in-sync exchange and is simply ignored; any other failure (a timeout, a reset,
		 * a half-read response) leaves the connection in an unknown state, so it is dropped — a
		 * late response must not desync a later request. A budget too small for any round trip
		 * skips the Signal outright, leaving the healthy connection untouched; the server reaps
		 * the completed command's state with the shell.
		 */
		private void terminateCompleted(final long budgetMs) {
			// Same strict clamp as the bounded poll: the per-round-trip timeout caps this cleanup too.
			final long budget = Math.max(1, Math.min(budgetMs, operationTimeoutMs));
			if (budget < MIN_WIRE_POLL_MS) {
				return;
			}
			transport.pollTimeout(toSocketTimeoutMillis(budget));
			try {
				terminate(commandId, budget);
			} catch (final WinRMFaultException ignored) {
				// The Signal was answered with a fault: the exchange completed, the connection is in
				// sync — and the command's completion is what matters.
			} catch (final Exception e) {
				transport.close();
			} finally {
				transport.inactivityTimeout(toSocketTimeoutMillis(operationTimeoutMs));
			}
		}

		/**
		 * Send the terminate Signal (stopping the remote command when it is still running) and
		 * release the connection. Idempotent; a no-op when the command already completed and was
		 * signaled by the final {@link #nextChunk()}.
		 */
		@Override
		public void close() throws Exception {
			finish();
		}
	}

	private void createShell(
		final String workingDirectory,
		final Map<String, String> environment,
		final long timeoutMs,
		final boolean failOnQuietTimeout
	) throws Exception {
		final Document doc = exchange(
			Envelopes.createShell(url, workingDirectory, environment, timeoutMs, consoleCodePage),
			"Create shell",
			timeoutMs,
			failOnQuietTimeout
		);
		final NodeList selectors = doc.getElementsByTagNameNS("*", "Selector");
		for (int i = 0; i < selectors.getLength(); i++) {
			final Element selector = (Element) selectors.item(i);
			if ("ShellId".equals(selector.getAttribute("Name"))) {
				shellId = selector.getTextContent();
				return;
			}
		}
		throw new IllegalStateException("Shell ID not found in Create response");
	}

	private String sendCommand(
		final String commandLine,
		final long timeoutMs,
		final boolean failOnQuietTimeout,
		final boolean consoleModeStdin
	) throws Exception {
		final Document doc = exchange(
			Envelopes.command(url, shellId, commandLine, timeoutMs, consoleModeStdin),
			"Command",
			timeoutMs,
			failOnQuietTimeout
		);
		final String commandId = text(doc, "CommandId");
		if (commandId == null) {
			throw new IllegalStateException("No CommandId in Command response");
		}
		return commandId;
	}

	private void terminate(final String commandId, final long timeoutMs) throws Exception {
		signal(commandId, Envelopes.TERMINATE_CODE, timeoutMs);
	}

	/**
	 * Send one Signal for a command. A missing shell is tolerated (the command may already be gone),
	 * and the cached shell id is then dropped so the next command creates a fresh one up front
	 * instead of discovering the stale one the hard way.
	 */
	private void signal(final String commandId, final String code, final long timeoutMs) throws Exception {
		final Decoded resp = request(Envelopes.signal(url, shellId, commandId, code, timeoutMs));
		// A missing shell is fine here — the command already finished and the shell may be gone.
		// But drop the cached ID so the next command creates a fresh shell up front instead of
		// discovering the stale one the hard way.
		if (resp.status != 200) {
			if (!FAULT_SHELL_NOT_FOUND.equals(wsmanFaultCode(resp.document))) {
				throw faultException("Signal", resp);
			}
			shellId = null;
		}
	}

	// --- transport / crypto -------------------------------------------------

	/** Send a request, expecting HTTP 200; throw with the WSMan fault detail otherwise. */
	private Document expectOk(final String soap, final String operation) throws Exception {
		final Decoded resp = request(soap);
		if (resp.status != 200) {
			throw faultException(operation, resp);
		}
		return resp.document;
	}

	/**
	 * Send one request of a streaming-capable operation, expecting HTTP 200. In streaming mode
	 * ({@code failOnQuietTimeout}) the two "server stayed quiet for a whole timeout" signals — a
	 * socket read timeout and the WSMan operation-timeout fault — are translated into the
	 * {@link TimeoutException} the streaming contract documents, on EVERY round trip (startup
	 * included), so the caller sees one consistent inactivity failure regardless of which request
	 * exceeded the limit first. In blocking mode this is exactly {@link #expectOk}: the raw
	 * failures surface and the caller's wall-clock deadline governs.
	 */
	private Document exchange(
		final String soap,
		final String operation,
		final long operationTimeoutMs,
		final boolean failOnQuietTimeout
	) throws Exception {
		if (!failOnQuietTimeout) {
			return expectOk(soap, operation);
		}
		final Decoded resp;
		try {
			resp = request(soap);
		} catch (final SocketTimeoutException e) {
			throw quietTimeout("No response from the WinRM service", operationTimeoutMs, e);
		}
		if (resp.status != 200) {
			if (FAULT_OPERATION_TIMEOUT.equals(wsmanFaultCode(resp.document))) {
				throw quietTimeout(operation + " produced no result", operationTimeoutMs, null);
			}
			throw faultException(operation, resp);
		}
		return resp.document;
	}

	/** The streaming inactivity timeout: the server produced nothing for a whole timeout. */
	private static TimeoutException quietTimeout(final String what, final long timeoutMs, final Throwable cause) {
		final TimeoutException timeout = new TimeoutException(what + " within the " + timeoutMs + " ms timeout.");
		if (cause != null) {
			timeout.initCause(cause);
		}
		return timeout;
	}

	/**
	 * Build the exception for a faulting response: the message keeps the historical
	 * {@code <operation> failed: <summary>} format (part of the exception-message contract inherited
	 * from the CXF backend), and the WSMan fault code, reason and provider detail travel as fields so
	 * the fluent API can expose them programmatically.
	 */
	private static WinRMFaultException faultException(final String operation, final Decoded resp) {
		return new WinRMFaultException(
			operation + " failed: " + faultSummary(resp),
			resp.status,
			trimToNull(wsmanFaultCode(resp.document)),
			trimToNull(text(resp.document, "Text")),
			trimToNull(wsmanFaultMessage(resp.document))
		);
	}

	/**
	 * Send one SOAP request (authenticating the connection on first use via the {@link AuthScheme})
	 * and decode the response. The caller must hold {@link #connectionPermit}; every path here is
	 * reached from an open enumeration/command handle (which owns the permit) or a permit-holding
	 * close, so requests never interleave on the stateful connection.
	 */
	private Decoded request(final String soap) throws Exception {
		// A straggler outliving close() must fail here rather than transparently reconnect and
		// re-authenticate the hard-closed transport — that revived connection would leak, since
		// nothing will ever close this client again. Same message as the executor's own guard.
		if (closed) {
			throw new IllegalStateException("This instance has been closed and a new one must be created.");
		}
		return send(soap);
	}

	/** The body of {@link #request(String)}, also reachable from close() itself. */
	private Decoded send(final String soap) throws Exception {
		// If the connection was dropped (e.g. the server sent "Connection: close"), the session bound
		// to it is dead — re-handshake on the fresh connection rather than sending unauthenticated.
		if (auth.isAuthenticated() && !transport.isConnected()) {
			auth.reset();
		}
		final byte[] body = soap.getBytes(StandardCharsets.UTF_8);
		// Stays null while the loop retries the next authentication scheme on a fresh connection.
		Decoded decoded = null;
		int retriesLeft = connectRetries;
		while (decoded == null) {
			if (!auth.isAuthenticated()) {
				try {
					// Connect explicitly (rather than letting post() do it lazily) so every failure to
					// REACH the endpoint — TCP connect, DNS resolution, TLS handshake — surfaces here,
					// alongside the authentication round trips: the one phase where this round trip's
					// request provably never reached the server, which is the only situation where a
					// retry cannot duplicate a side effect (issue #158).
					transport.connect();
					pendingAuthorization = auth.authenticate(transport);
				} catch (final Exception e) {
					// Transient connection failure: apply the opt-in retry policy — but only to a
					// transport I/O failure (possibly wrapped, e.g. by an ordered authentication
					// fallback), and unless the client is closing (best-effort cleanup must not
					// linger), the retries are exhausted, or a deadline-bounded poll cannot absorb
					// the pause within its hard bound. A cancelled worker (its caller was already
					// told the operation timed out) stops in the sleep.
					if (closed
						||
						retriesLeft <= 0
						||
						!isConnectionFailure(e)
						||
						transport.remainingPollBudgetMillis() <= retryDelayMs) {
						throw e;
					}
					retriesLeft--;
					auth.reset();
					Thread.sleep(retryDelayMs);
					// close() cannot interrupt a worker sleeping here: recheck after the pause, or
					// the retry would revive the hard-closed transport and send the (possibly
					// non-idempotent) operation after the client was closed.
					if (closed) {
						throw e;
					}
					continue;
				}
			}
			// The handshake's Authorization accompanies the first real request; stateless schemes
			// (Basic) instead repeat their header on EVERY request, and NTLM/Kerberos need none after
			// the first.
			final String authorization = pendingAuthorization != null ? pendingAuthorization : auth.requestAuthorization();
			pendingAuthorization = null;

			final HttpTransport.Response resp = transport.post(
				"/wsman",
				auth.wrap(body),
				auth.wrapContentType(),
				authorization
			);

			// HTTP 401 = the server rejected the credentials/token. For NTLM and Kerberos this can only
			// surface here — the Type 3 / AP-REQ rides the first real request, not the handshake — so we
			// must NOT keep the "authenticated" connection (it would loop resending with no Authorization
			// header, wedging the executor). Drop it and, for an ordered fallback list, retry the next
			// scheme once on a fresh connection. A 401'd request was rejected before processing, so
			// re-sending it is safe.
			if (resp.status == 401) {
				transport.close();
				auth.reset();
				if (auth.advance()) {
					continue;
				}
				// Same message format as the CXF backend's credential-rejection path — callers (and their
				// operators) match on it.
				throw new WinRMAuthenticationException(
					String.format("Authentication error on %s with user name \"%s\"", url, rawUsername)
				);
			}

			// 200 = success, 500 = SOAP fault. Anything else is a protocol failure whose body is not a
			// usable WSMan response.
			if (resp.status != 200 && resp.status != 500) {
				throw new IllegalStateException("WSMan request failed: HTTP " + resp.status);
			}
			decoded = new Decoded(resp.status, parse(auth.unwrap(resp)));
		}
		return decoded;
	}

	/**
	 * Whether the failure is a transport I/O failure — directly, or through its cause chain: an
	 * ordered authentication fallback reports "all schemes failed" with the last scheme's failure
	 * as the cause, which must still be recognized when that failure was a transient transport
	 * error. Anything without an {@link IOException} in the chain (a protocol violation, a
	 * rejected credential, an interrupt) is not a connection failure and must not be retried.
	 */
	private static boolean isConnectionFailure(final Exception exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof IOException) {
				return true;
			}
		}
		return false;
	}

	// --- XML helpers --------------------------------------------------------

	static Document parse(final byte[] xml) throws Exception {
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
		factory.setNamespaceAware(true);
		// Harden against XXE: a malicious/compromised WinRM endpoint must not be able to make us
		// resolve external entities (local file read, SSRF, entity-expansion DoS). WSMan responses
		// never carry a DOCTYPE, so rejecting it outright is the strongest and safest defence.
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		final DocumentBuilder builder = factory.newDocumentBuilder();
		// Throw parse errors instead of letting the default handler print them to stderr — a request
		// abandoned by the timeout may parse a truncated response on a soon-to-die background thread.
		builder.setErrorHandler(
			new org.xml.sax.helpers.DefaultHandler() {
				@Override
				public void error(final org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
					throw e;
				}

				@Override
				public void fatalError(final org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
					throw e;
				}
			}
		);
		return builder.parse(new ByteArrayInputStream(xml));
	}

	private static String text(final Document doc, final String localName) {
		final NodeList nodes = doc.getElementsByTagNameNS("*", localName);
		return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
	}

	/**
	 * Whether the document contains the given enumeration control element (namespace-scoped).
	 * WinRM emits these markers either in the WS-Enumeration namespace or in its own WSMan namespace
	 * (e.g. {@code wsen:EndOfSequence} vs {@code wsman:EndOfSequence}); accept both, like the CXF
	 * backend does.
	 */
	static boolean hasEnumerationElement(final Document doc, final String localName) {
		return doc.getElementsByTagNameNS(WS_ENUMERATION_NS, localName).getLength() > 0
			||
			doc.getElementsByTagNameNS(WSMAN_NS, localName).getLength() > 0;
	}

	/** First text content of an element matched by both namespace and local name. */
	private static String textNS(final Document doc, final String namespace, final String localName) {
		final NodeList nodes = doc.getElementsByTagNameNS(namespace, localName);
		return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
	}

	static void collectItems(final Document doc, final List<Map<String, String>> rows) {
		// The Items wrapper comes in the WS-Enumeration namespace (EnumerateResponse) or the WSMan
		// namespace (PullResponse) depending on the operation; accept both, like the CXF backend, and
		// nothing else — a WMI property or class named "Items" must not be mistaken for the wrapper.
		collectRows(doc.getElementsByTagNameNS(WS_ENUMERATION_NS, "Items"), rows);
		collectRows(doc.getElementsByTagNameNS(WSMAN_NS, "Items"), rows);
	}

	private static void collectRows(final NodeList items, final List<Map<String, String>> rows) {
		for (int i = 0; i < items.getLength(); i++) {
			final NodeList instances = items.item(i).getChildNodes();
			for (int j = 0; j < instances.getLength(); j++) {
				final Node instance = instances.item(j);
				if (instance.getNodeType() != Node.ELEMENT_NODE) {
					continue;
				}
				final Map<String, String> row = new LinkedHashMap<>();
				final NodeList props = instance.getChildNodes();
				for (int k = 0; k < props.getLength(); k++) {
					final Node prop = props.item(k);
					if (prop.getNodeType() == Node.ELEMENT_NODE) {
						row.put(((Element) prop).getLocalName(), prop.getTextContent());
					}
				}
				if (!row.isEmpty()) {
					rows.add(row);
				}
			}
		}
	}

	private static void collectStreams(
		final Document doc,
		final ByteArrayOutputStream stdout,
		final ByteArrayOutputStream stderr
	) {
		final NodeList streams = doc.getElementsByTagNameNS("*", "Stream");
		for (int i = 0; i < streams.getLength(); i++) {
			final Element stream = (Element) streams.item(i);
			final String value = stream.getTextContent();
			if (value == null || value.isEmpty()) {
				continue;
			}
			final byte[] bytes = Base64.getDecoder().decode(value);
			if ("stdout".equals(stream.getAttribute("Name"))) {
				stdout.write(bytes, 0, bytes.length);
			} else if ("stderr".equals(stream.getAttribute("Name"))) {
				stderr.write(bytes, 0, bytes.length);
			}
		}
	}

	/** Return the exit code if the response carries a CommandState of Done, otherwise null. */
	private static Integer doneExitCode(final Document doc) {
		final NodeList states = doc.getElementsByTagNameNS("*", "CommandState");
		for (int i = 0; i < states.getLength(); i++) {
			final Element state = (Element) states.item(i);
			if (Envelopes.COMMAND_STATE_DONE.equals(state.getAttribute("State"))) {
				final NodeList exit = state.getElementsByTagNameNS("*", "ExitCode");
				// Parse as long, then narrow: Windows reports HRESULT exit codes (e.g. certutil's
				// 0x80070002) as unsigned 32-bit values that overflow Integer.parseInt.
				return exit.getLength() > 0 ? (int) Long.parseLong(exit.item(0).getTextContent().trim()) : 0;
			}
		}
		return null;
	}

	private static String wsmanFaultCode(final Document doc) {
		final NodeList faults = doc.getElementsByTagNameNS("*", "WSManFault");
		return faults.getLength() > 0 ? ((Element) faults.item(0)).getAttribute("Code") : null;
	}

	/**
	 * The detailed WSManFault Message text, or null. This is where WinRM puts the provider-level
	 * detail — notably the WMI error mnemonics (WBEM_E_INVALID_CLASS, WBEM_E_INVALID_NAMESPACE,
	 * WBEM_E_NOT_FOUND, ...) that callers match on to tell a bad query from a broken connection.
	 * {@code getTextContent()} also flattens any nested ProviderFault detail into the message.
	 */
	private static String wsmanFaultMessage(final Document doc) {
		final NodeList faults = doc.getElementsByTagNameNS("*", "WSManFault");
		if (faults.getLength() == 0) {
			return null;
		}
		final NodeList messages = ((Element) faults.item(0)).getElementsByTagNameNS("*", "Message");
		return messages.getLength() > 0 ? messages.item(0).getTextContent() : null;
	}

	static String faultSummary(final int status, final Document doc) {
		final String reason = trimToNull(text(doc, "Text"));
		final String detail = trimToNull(wsmanFaultMessage(doc));
		final String code = wsmanFaultCode(doc);
		final StringBuilder summary = new StringBuilder("HTTP ").append(status);
		if (code != null && !code.isEmpty()) {
			summary.append(" (WSManFault ").append(code).append(')');
		}
		if (reason != null) {
			summary.append(": ").append(reason);
		}
		// Append the detailed fault message when it adds anything beyond the Reason text: the WMI
		// mnemonics it carries are part of the exception-message contract inherited from CXF.
		if (detail != null && (reason == null || !reason.contains(detail))) {
			summary.append(reason == null ? ": " : " - ").append(detail);
		}
		return summary.toString();
	}

	private static String faultSummary(final Decoded resp) {
		return faultSummary(resp.status, resp.document);
	}

	private static String trimToNull(final String s) {
		if (s == null) {
			return null;
		}
		final String trimmed = s.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@Override
	public void close() {
		// Fence stragglers first: any in-flight or later request() from a worker or streaming handle
		// that outlives this close must fail instead of reviving the connection (see request()).
		closed = true;
		// Only attempt a graceful shell Delete if no operation is currently using the connection: a
		// non-blocking tryAcquire (never an acquire()) keeps close() from waiting on an abandoned,
		// timed-out worker — or an open streaming handle — still holding the permit while blocked on
		// a socket read. When we cannot acquire the permit, or a request would otherwise race the
		// worker, we skip the Delete and just hard-close the transport below — which unblocks that
		// worker's read; the shell is reaped by the server IdleTimeout.
		final boolean locked = connectionPermit.tryAcquire();
		try {
			final String shell = shellId;
			shellId = null;
			if (locked) {
				if (shell != null) {
					try {
						send(Envelopes.deleteShell(url, shell, timeoutMs));
					} catch (final Exception ignored) {
						// best-effort shell cleanup
					}
				}
				// Release the connection-bound auth state — notably the Kerberos GSSContext, whose only
				// disposal path is reset(). Skipped when not locked: another (timed-out) worker still owns
				// the auth scheme, and the transport hard-close below unblocks it.
				auth.reset();
			}
		} finally {
			if (locked) {
				connectionPermit.release();
			}
			transport.close();
		}
	}
}
