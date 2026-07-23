package org.metricshub.winrm.service;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2026 MetricsHub
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
import java.util.Locale;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.light.LightWinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * Creates the {@link WindowsRemoteExecutor} that fulfils a request. Since 2.0.0 the dependency-free
 * {@link LightWinRMService} is the only backend: the legacy CXF-based backend has been removed.
 * The {@value #BACKEND_PROPERTY} system property is kept so operators who still set it get a clear
 * error ({@code cxf}) or a no-op ({@code light}) instead of a silent behavior change.
 */
public final class WinRMExecutorFactory {

	/** System property selecting the backend; {@code light} is the only supported value. */
	public static final String BACKEND_PROPERTY = "org.metricshub.winrm.backend";

	private static final String LIGHT = "light";
	private static final String CXF = "cxf";

	private WinRMExecutorFactory() {}

	/**
	 * Create a {@link WindowsRemoteExecutor} (light backend).
	 *
	 * @param winRMEndpoint endpoint with credentials (mandatory)
	 * @param timeout timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache Kerberos ticket cache path (may be {@code null})
	 * @param authentications requested authentication schemes (may be {@code null})
	 * @return a light-backed executor
	 * @throws WinRMException for any problem creating the executor, or when {@value #BACKEND_PROPERTY}
	 *         requests the removed CXF backend or an unknown value
	 */
	public static WindowsRemoteExecutor createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws WinRMException {
		final String backend = System.getProperty(BACKEND_PROPERTY, LIGHT).trim().toLowerCase(Locale.ROOT);
		if (LIGHT.equals(backend)) {
			return LightWinRMService.createInstance(winRMEndpoint, timeout, ticketCache, authentications);
		}
		if (CXF.equals(backend)) {
			// Fail loudly: an operator who explicitly pinned the legacy backend must not be silently
			// switched to another implementation.
			throw new WinRMException(
				"The CXF WinRM backend was removed in winrm-java 2.0.0; remove the " +
					BACKEND_PROPERTY +
					" system property to use the light backend (or stay on winrm-java 1.x)."
			);
		}
		throw new WinRMException(
			"Unsupported value \"" + backend + "\" for system property " + BACKEND_PROPERTY + "; expected \"" + LIGHT + "\"."
		);
	}
}
