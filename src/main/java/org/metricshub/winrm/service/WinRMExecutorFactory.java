package org.metricshub.winrm.service;

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

import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.light.LightWinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * Creates the {@link WindowsRemoteExecutor} that fulfils a request. Since 2.0.0 the dependency-free
 * {@link LightWinRMService} is the only implementation: the legacy CXF-based backend has been removed.
 */
public final class WinRMExecutorFactory {

	private WinRMExecutorFactory() {}

	/**
	 * Create a {@link WindowsRemoteExecutor}.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (may be {@code null})
	 * @param authentications requested authentication schemes (may be {@code null})
	 * @return an executor backed by {@link LightWinRMService}
	 * @throws WinRMException for any problem creating the executor
	 */
	public static WindowsRemoteExecutor createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws WinRMException {
		return LightWinRMService.createInstance(winRMEndpoint, timeout, ticketCache, authentications);
	}

	/**
	 * Create a {@link WindowsRemoteExecutor} with an explicit TLS configuration, overriding the
	 * {@code org.metricshub.winrm.tls.insecure} system property for this instance.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (may be {@code null})
	 * @param authentications requested authentication schemes (may be {@code null})
	 * @param sslContext the {@link SSLContext} providing the HTTPS socket factory (hostname
	 *        verification stays on); {@code null} uses the default configuration
	 * @param trustAllCertificates when {@code true} (and no {@code sslContext} is given), trust every
	 *        server certificate and skip hostname verification — insecure, testing only
	 * @return an executor backed by {@link LightWinRMService}
	 * @throws WinRMException for any problem creating the executor
	 */
	public static WindowsRemoteExecutor createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications,
		final SSLContext sslContext,
		final boolean trustAllCertificates
	) throws WinRMException {
		return createInstance(winRMEndpoint, timeout, ticketCache, authentications, sslContext, trustAllCertificates, 0);
	}

	/**
	 * Create a {@link WindowsRemoteExecutor} with an explicit console code page for the command
	 * shell.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (may be {@code null})
	 * @param authentications requested authentication schemes (may be {@code null})
	 * @param sslContext the {@link SSLContext} providing the HTTPS socket factory (hostname
	 *        verification stays on); {@code null} uses the default configuration
	 * @param trustAllCertificates when {@code true} (and no {@code sslContext} is given), trust every
	 *        server certificate and skip hostname verification — insecure, testing only
	 * @param consoleCodePage the console code page of the command shell; 0 keeps the default 65001
	 * @return an executor backed by {@link LightWinRMService}
	 * @throws WinRMException for any problem creating the executor
	 */
	public static WindowsRemoteExecutor createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications,
		final SSLContext sslContext,
		final boolean trustAllCertificates,
		final int consoleCodePage
	) throws WinRMException {
		return LightWinRMService.createInstance(
			winRMEndpoint,
			timeout,
			ticketCache,
			authentications,
			sslContext,
			trustAllCertificates,
			consoleCodePage
		);
	}

	/**
	 * Create a {@link WindowsRemoteExecutor} with an opt-in retry policy for transient connection
	 * failures. A round trip is retried only when it failed to establish and authenticate the
	 * connection — i.e. when its request provably never reached the server — so at-most-once
	 * execution semantics are preserved.
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (may be {@code null})
	 * @param authentications requested authentication schemes (may be {@code null})
	 * @param sslContext the {@link SSLContext} providing the HTTPS socket factory (hostname
	 *        verification stays on); {@code null} uses the default configuration
	 * @param trustAllCertificates when {@code true} (and no {@code sslContext} is given), trust every
	 *        server certificate and skip hostname verification — insecure, testing only
	 * @param consoleCodePage the console code page of the command shell; 0 keeps the default 65001
	 * @param connectRetries how many times one round trip may re-attempt to connect and authenticate
	 *        (must be &gt;= 0); 0 keeps the historical fail-fast behavior
	 * @param retryDelay the pause in milliseconds before each retry (must be &gt;= 0)
	 * @return an executor backed by {@link LightWinRMService}
	 * @throws WinRMException for any problem creating the executor
	 */
	public static WindowsRemoteExecutor createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications,
		final SSLContext sslContext,
		final boolean trustAllCertificates,
		final int consoleCodePage,
		final int connectRetries,
		final long retryDelay
	) throws WinRMException {
		return LightWinRMService.createInstance(
			winRMEndpoint,
			timeout,
			ticketCache,
			authentications,
			sslContext,
			trustAllCertificates,
			consoleCodePage,
			connectRetries,
			retryDelay
		);
	}
}
