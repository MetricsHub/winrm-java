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
 * Selects the WinRM backend that fulfils a request. The default is the dependency-free
 * {@link LightWinRMService}; setting the system property {@value #BACKEND_PROPERTY} to
 * {@code cxf} selects the mature CXF-based {@link WinRMService} instead — needed for capabilities
 * the light backend does not yet cover (HTTPS and Kerberos).
 *
 * <p>Both backends implement {@link WindowsRemoteExecutor}, so callers are agnostic to the choice.
 */
public final class WinRMExecutorFactory {

	/** System property selecting the backend: {@code light} (default) or {@code cxf}. */
	public static final String BACKEND_PROPERTY = "org.metricshub.winrm.backend";

	private static final String LIGHT = "light";
	private static final String CXF = "cxf";

	private WinRMExecutorFactory() {}

	/**
	 * Create a {@link WindowsRemoteExecutor} using the configured backend.
	 *
	 * @param winRMEndpoint   endpoint with credentials (mandatory)
	 * @param timeout         timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache     Kerberos ticket cache path (may be {@code null})
	 * @param authentications requested authentication schemes (may be {@code null})
	 * @return a light-backed or CXF-backed executor depending on {@value #BACKEND_PROPERTY}
	 * @throws WinRMException for any problem creating the executor
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
			return WinRMService.createInstance(winRMEndpoint, timeout, ticketCache, authentications);
		}
		// Fail loudly on a typo or unsupported value rather than silently running a backend the operator
		// did not ask for (which would also emit misleading "set the property" hints downstream).
		throw new WinRMException(
			"Unsupported value \"" +
			backend +
			"\" for system property " +
			BACKEND_PROPERTY +
			"; expected \"" +
			LIGHT +
			"\" (default) or \"" +
			CXF +
			"\"."
		);
	}
}
