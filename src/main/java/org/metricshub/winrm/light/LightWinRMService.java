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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.metricshub.winrm.CommandCursor;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.WmiHelper;
import org.metricshub.winrm.WqlCursor;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * Dependency-free {@link WindowsRemoteExecutor} backed by {@link WsmanClient}. A drop-in
 * replacement for the CXF-based {@code WinRMService} that shipped before 2.0.0: same public
 * behaviour, no Apache CXF / JAX-WS / JAXB stack, and immune by construction to JAXP
 * {@code ServiceLoader} poisoning (it uses the JDK-default XML factories).
 * <p>
 * Supports NTLM over HTTP (with message encryption) and over HTTPS (plaintext SOAP inside TLS,
 * validating the server certificate by default; see {@link LightTls}), and Kerberos over HTTPS
 * (SPNEGO via the JDK GSS-API; see {@link KerberosAuthScheme}). A multi-scheme request such as
 * {@code [KERBEROS, NTLM]} is tried in order with fallback.
 */
public final class LightWinRMService implements WindowsRemoteExecutor {

	private final WinRMEndpoint winRMEndpoint;
	private final WsmanClient client;
	private final AtomicBoolean closed = new AtomicBoolean(false);

	private LightWinRMService(final WinRMEndpoint winRMEndpoint, final WsmanClient client) {
		this.winRMEndpoint = winRMEndpoint;
		this.client = client;
	}

	/**
	 * Create a light WinRM executor.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (used by the Kerberos scheme; {@code null} logs
	 *        in with the password)
	 * @param authentications requested authentication schemes, tried in order (NTLM and/or Kerberos);
	 *        {@code null}/empty means NTLM only
	 * @return a new {@code LightWinRMService}
	 * @throws WinRMException on invalid arguments or an unsupported authentication request
	 */
	public static LightWinRMService createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final java.nio.file.Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws WinRMException {
		return createInstance(winRMEndpoint, timeout, ticketCache, authentications, null, false);
	}

	/**
	 * Create a light WinRM executor with an explicit TLS configuration, overriding the
	 * {@code org.metricshub.winrm.tls.insecure} system property for this instance.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (used by the Kerberos scheme; {@code null} logs
	 *        in with the password)
	 * @param authentications requested authentication schemes, tried in order (NTLM and/or Kerberos);
	 *        {@code null}/empty means NTLM only
	 * @param sslContext the {@link SSLContext} providing the HTTPS socket factory (hostname
	 *        verification stays on); {@code null} uses the default configuration
	 * @param trustAllCertificates when {@code true} (and no {@code sslContext} is given), trust every
	 *        server certificate and skip hostname verification — insecure, testing only
	 * @return a new {@code LightWinRMService}
	 * @throws WinRMException on invalid arguments or an unsupported authentication request
	 */
	public static LightWinRMService createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final java.nio.file.Path ticketCache,
		final List<AuthenticationEnum> authentications,
		final SSLContext sslContext,
		final boolean trustAllCertificates
	) throws WinRMException {
		return createInstance(winRMEndpoint, timeout, ticketCache, authentications, sslContext, trustAllCertificates, 0);
	}

	/**
	 * Create a light WinRM executor with an explicit console code page for the command shell.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (used by the Kerberos scheme; {@code null} logs
	 *        in with the password)
	 * @param authentications requested authentication schemes, tried in order (NTLM and/or Kerberos);
	 *        {@code null}/empty means NTLM only
	 * @param sslContext the {@link SSLContext} providing the HTTPS socket factory (hostname
	 *        verification stays on); {@code null} uses the default configuration
	 * @param trustAllCertificates when {@code true} (and no {@code sslContext} is given), trust every
	 *        server certificate and skip hostname verification — insecure, testing only
	 * @param consoleCodePage the console code page of the command shell; 0 keeps the default 65001,
	 *        which makes command output UTF-8 whatever the remote locale
	 * @return a new {@code LightWinRMService}
	 * @throws WinRMException on invalid arguments or an unsupported authentication request
	 */
	public static LightWinRMService createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final java.nio.file.Path ticketCache,
		final List<AuthenticationEnum> authentications,
		final SSLContext sslContext,
		final boolean trustAllCertificates,
		final int consoleCodePage
	) throws WinRMException {
		Utils.checkNonNull(winRMEndpoint, "winRMEndpoint");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		// HTTPS wraps the transport in TLS and exchanges plaintext SOAP; HTTP uses NTLM message sealing.
		// TLS validates by default (platform trust store + hostname verification); see LightTls. A
		// caller-provided SSLContext keeps hostname verification on; trust-all disables both checks.
		final boolean https = winRMEndpoint.getProtocol() == WinRMHttpProtocolEnum.HTTPS;
		final SSLSocketFactory sslSocketFactory;
		final boolean verifyHostname;
		if (!https) {
			sslSocketFactory = null;
			verifyHostname = false;
		} else if (sslContext != null) {
			sslSocketFactory = sslContext.getSocketFactory();
			verifyHostname = true;
		} else if (trustAllCertificates) {
			sslSocketFactory = LightTls.insecureSocketFactory();
			verifyHostname = false;
		} else {
			sslSocketFactory = LightTls.socketFactory();
			verifyHostname = LightTls.verifyHostname();
		}

		final AuthScheme authScheme = resolveAuthScheme(winRMEndpoint, authentications, https, ticketCache);

		// Use the endpoint's own validated host/port rather than re-parsing the URL: URI.getHost()/getPort()
		// return null/-1 for names URI cannot classify (underscores, Unicode) that WinRMEndpoint accepts,
		// which would otherwise make the default backend unable to reach hosts the CXF backend could.
		final WsmanClient client = new WsmanClient(
			winRMEndpoint.getHostname(),
			winRMEndpoint.getPort(),
			timeout,
			sslSocketFactory,
			verifyHostname,
			authScheme,
			winRMEndpoint.getRawUsername(),
			consoleCodePage
		);
		return new LightWinRMService(winRMEndpoint, client);
	}

	/**
	 * Resolve the requested authentication schemes into a single {@link AuthScheme}, honoring the
	 * caller's order. {@code null}/empty means NTLM only. A single scheme is used directly; several
	 * become an ordered {@link FallbackAuthScheme} (e.g. Kerberos then NTLM). Kerberos requires HTTPS
	 * (no message encryption over plain HTTP, matching the CXF backend), so it is dropped from the
	 * candidate list over HTTP — a fallback list then uses its remaining schemes, and a Kerberos-only
	 * request over HTTP fails toward the escape hatch.
	 */
	private static AuthScheme resolveAuthScheme(
		final WinRMEndpoint winRMEndpoint,
		final List<AuthenticationEnum> authentications,
		final boolean https,
		final java.nio.file.Path ticketCache
	) throws WinRMException {
		final List<AuthenticationEnum> requested = authentications == null || authentications.isEmpty()
			? List.of(AuthenticationEnum.NTLM)
			: authentications;

		final String domain = winRMEndpoint.getDomain();
		final String username = winRMEndpoint.getUsername();
		final String password = new String(winRMEndpoint.getPassword());

		final List<AuthScheme> schemes = new ArrayList<>();
		for (final AuthenticationEnum auth : requested) {
			if (auth == AuthenticationEnum.NTLM) {
				schemes.add(new NtlmAuthScheme(domain, username, password, https));
			} else if (auth == AuthenticationEnum.KERBEROS) {
				if (https) {
					// The SPN is HTTP/<hostname>, so the caller must connect by the FQDN the KDC knows.
					schemes.add(new KerberosAuthScheme(winRMEndpoint.getHostname(), username, password, ticketCache));
				}
				// else: Kerberos is unavailable over plain HTTP — leave it out of the candidate list.
			} else {
				throw new WinRMException(
					"The light WinRM backend supports only NTLM and Kerberos (requested: " + requested + ")."
				);
			}
		}

		if (schemes.isEmpty()) {
			// e.g. Kerberos requested over plain HTTP with no other scheme to fall back to.
			throw new WinRMException(
				"Kerberos over WinRM requires HTTPS (endpoint was " +
					winRMEndpoint.getEndpoint() +
					"): there is no Kerberos message encryption over plain HTTP. Use HTTPS, or add NTLM to the authentication list."
			);
		}
		return schemes.size() == 1 ? schemes.get(0) : new FallbackAuthScheme(schemes);
	}

	@Override
	public List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout)
		throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		return executeWql(winRMEndpoint.getNamespace(), wqlQuery, timeout, DEFAULT_WQL_MAX_ELEMENTS, 0);
	}

	@Override
	public List<Map<String, Object>> executeWql(
		final String namespace,
		final String wqlQuery,
		final long timeout,
		final int maxElements,
		final long pullTimeout
	) throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		checkWqlArguments(namespace, wqlQuery, timeout, maxElements, pullTimeout);

		// Enforce the caller's timeout as a wall-clock deadline (throwing TimeoutException), matching
		// the CXF WinRMService and bounding the WSMan Pull loop.
		return executeWithTimeout(
			() -> {
				final List<Map<String, String>> rows = client.wql(namespace, wqlQuery, timeout, maxElements, pullTimeout);
				final List<Map<String, Object>> result = new ArrayList<>(rows.size());
				for (final Map<String, String> row : rows) {
					result.add(new LinkedHashMap<>(row));
				}
				return result;
			},
			timeout
		);
	}

	@Override
	public WqlCursor streamWql(
		final String namespace,
		final String wqlQuery,
		final long timeout,
		final int maxElements,
		final long pullTimeout
	) throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		checkWqlArguments(namespace, wqlQuery, timeout, maxElements, pullTimeout);

		// The initial Enumerate is sent here, on the caller's thread, so configuration and
		// authentication failures surface immediately rather than on the first row.
		final WsmanClient.WqlEnumeration enumeration = callStreaming(
			() -> client.openWql(namespace, wqlQuery, timeout, maxElements, pullTimeout, true)
		);
		return new WqlCursor() {
			@Override
			public Map<String, Object> next() throws TimeoutException, WindowsRemoteException {
				final Map<String, String> row = callStreaming(enumeration::next);
				return row == null ? null : new LinkedHashMap<>(row);
			}

			@Override
			public void close() {
				enumeration.close();
			}
		};
	}

	/** Validate the arguments shared by the blocking and streaming WQL entry points. */
	private void checkWqlArguments(
		final String namespace,
		final String wqlQuery,
		final long timeout,
		final int maxElements,
		final long pullTimeout
	) throws WqlQuerySyntaxException {
		checkNotClosed();
		Utils.checkNonNull(namespace, "namespace");
		Utils.checkNonNull(wqlQuery, "wqlQuery");
		if (!WmiHelper.isValidWql(wqlQuery)) {
			throw new WqlQuerySyntaxException(wqlQuery);
		}
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");
		Utils.checkArgumentNotZeroOrNegative(maxElements, "maxElements");
		if (pullTimeout < 0) {
			throw new IllegalArgumentException("pullTimeout must not be negative.");
		}
	}

	@Override
	public CommandCursor startCommand(final String command, final String workingDirectory, final long timeout)
		throws TimeoutException, WindowsRemoteException {
		return startCommand(command, workingDirectory, timeout, true);
	}

	@Override
	public CommandCursor startCommand(
		final String command,
		final String workingDirectory,
		final long timeout,
		final boolean consoleModeStdin
	) throws TimeoutException, WindowsRemoteException {
		return startCommand(command, workingDirectory, null, timeout, consoleModeStdin);
	}

	@Override
	public CommandCursor startCommand(
		final String command,
		final String workingDirectory,
		final Map<String, String> environment,
		final long timeout,
		final boolean consoleModeStdin
	) throws TimeoutException, WindowsRemoteException {
		checkNotClosed();
		Utils.checkNonNull(command, "command");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		// Shell creation and command startup happen here, on the caller's thread, so failures
		// surface immediately rather than on the first output chunk.
		final WsmanClient.RemoteCommand remoteCommand = callStreaming(
			() -> client.startCommand(command, workingDirectory, environment, timeout, true, consoleModeStdin)
		);
		return new CommandCursor() {
			@Override
			public Chunk next() throws TimeoutException, WindowsRemoteException {
				return adapt(callStreaming(remoteCommand::nextChunk));
			}

			@Override
			public Chunk poll(final long maxWaitMillis) throws TimeoutException, WindowsRemoteException {
				return adapt(callStreaming(() -> remoteCommand.pollChunk(maxWaitMillis)));
			}

			@Override
			public Chunk poll(final long askMillis, final long maxWaitMillis)
				throws TimeoutException, WindowsRemoteException {
				return adapt(callStreaming(() -> remoteCommand.pollChunk(askMillis, maxWaitMillis)));
			}

			private Chunk adapt(final WsmanClient.RemoteCommand.Chunk chunk) {
				return chunk == null ? null : new Chunk(chunk.stdout, chunk.stderr);
			}

			@Override
			public void send(final byte[] data, final boolean end) throws TimeoutException, WindowsRemoteException {
				callStreaming(() -> {
					remoteCommand.send(data, end);
					return null;
				});
			}

			@Override
			public void interrupt() throws TimeoutException, WindowsRemoteException {
				callStreaming(() -> {
					remoteCommand.interrupt();
					return null;
				});
			}

			@Override
			public int exitCode() {
				return remoteCommand.exitCode();
			}

			@Override
			public void close() {
				try {
					remoteCommand.close();
				} catch (final RuntimeException e) {
					// Typed protocol failures (e.g. a fault answering the terminate Signal) pass through.
					throw e;
				} catch (final InterruptedException e) {
					// Closing on an already-cancelled thread: restore the flag, the connection permit
					// has been released and the transport is torn down with the executor.
					Thread.currentThread().interrupt();
				} catch (final Exception e) {
					throw new WinRMClientException(e.getMessage(), e);
				}
			}
		};
	}

	/**
	 * Run one streaming protocol step on the caller's thread, translating the raw client failures
	 * the way the blocking operations do. Unlike the blocking operations there is no worker thread
	 * and no wall-clock deadline: each round trip is bounded by the operation timeout (the WSMan
	 * OperationTimeout header and the socket read timeout), which acts as the inactivity timeout
	 * of the stream and surfaces as the {@link TimeoutException} this method lets through.
	 *
	 * @param step the protocol step to run
	 * @param <T> the step's result type
	 * @return the step's result
	 * @throws TimeoutException when the step exceeds the inactivity timeout
	 * @throws WinRMException when the step fails with a checked failure
	 */
	private static <T> T callStreaming(final Callable<T> step) throws TimeoutException, WinRMException {
		try {
			return step.call();
		} catch (final TimeoutException | RuntimeException e) {
			throw e;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WinRMException(e);
		} catch (final Exception e) {
			throw new WinRMException(e, e.getMessage());
		}
	}

	@Override
	public WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Charset charset,
		final long timeout
	) throws WindowsRemoteException, TimeoutException {
		return executeCommand(command, workingDirectory, null, charset, timeout);
	}

	@Override
	public WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Map<String, String> environment,
		final Charset charset,
		final long timeout
	) throws WindowsRemoteException, TimeoutException {
		checkNotClosed();
		Utils.checkNonNull(command, "command");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		// Enforce the caller's timeout as a wall-clock deadline (throwing TimeoutException), matching
		// the CXF WinRMService and bounding the WSMan Receive loop.
		return executeWithTimeout(
			() -> {
				final long start = Utils.getCurrentTimeMillis();
				final WsmanClient.CommandOutput output = client.executeCommand(
					command,
					workingDirectory,
					environment,
					charset,
					timeout
				);
				final float executionTime = (Utils.getCurrentTimeMillis() - start) / 1000.0f;
				return new WindowsRemoteCommandResult(output.stdout, output.stderr, executionTime, output.exitCode);
			},
			timeout
		);
	}

	/**
	 * Run the task through {@link Utils#execute(Callable, long)} under the caller's wall-clock
	 * timeout, converting the executor's checked exceptions into {@link WinRMException} — the
	 * task's own failure is unwrapped from {@link ExecutionException} so the caller sees the real
	 * cause and its message.
	 *
	 * @param task the operation to run
	 * @param timeout timeout in milliseconds
	 * @param <T> the task's result type
	 * @return the task's result
	 * @throws TimeoutException when the deadline elapses first
	 * @throws WinRMException when the task fails or the wait is interrupted
	 */
	private static <T> T executeWithTimeout(final Callable<T> task, final long timeout)
		throws TimeoutException, WinRMException {
		try {
			return Utils.execute(task, timeout);
		} catch (final InterruptedException | ExecutionException e) {
			if (e.getCause() != null) {
				throw new WinRMException(e.getCause(), e.getCause().getMessage());
			}
			throw new WinRMException(e);
		}
	}

	@Override
	public String getHostname() {
		return winRMEndpoint.getHostname();
	}

	@Override
	public String getUsername() {
		return winRMEndpoint.getRawUsername();
	}

	@Override
	public char[] getPassword() {
		return winRMEndpoint.getPassword();
	}

	@Override
	public void close() {
		// Idempotent: releases the underlying connection exactly once, and marks the executor closed so
		// a later operation is rejected rather than silently reviving it with a fresh handshake.
		if (closed.compareAndSet(false, true)) {
			client.close();
		}
	}

	private void checkNotClosed() {
		// Same message as the CXF backend's checkConnectedFirst() — part of the exception surface.
		if (closed.get()) {
			throw new IllegalStateException("This instance has been closed and a new one must be created.");
		}
	}
}
