package org.metricshub.winrm.light;

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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.WmiHelper;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * Dependency-free {@link WindowsRemoteExecutor} backed by {@link WsmanClient}. A drop-in
 * alternative to the CXF-based {@code WinRMService}: same public behaviour, no Apache CXF /
 * JAX-WS / JAXB stack, and immune by construction to JAXP {@code ServiceLoader} poisoning
 * (it uses the JDK-default XML factories).
 *
 * <p>Currently supports NTLM over HTTP with message encryption. Kerberos and HTTPS are handled
 * by the CXF backend until the corresponding light support lands.
 */
public final class LightWinRMService implements WindowsRemoteExecutor {

	private final WinRMEndpoint winRMEndpoint;
	private final WsmanClient client;

	private LightWinRMService(final WinRMEndpoint winRMEndpoint, final WsmanClient client) {
		this.winRMEndpoint = winRMEndpoint;
		this.client = client;
	}

	/**
	 * Create a light WinRM executor.
	 *
	 * @param winRMEndpoint  endpoint with credentials (mandatory)
	 * @param timeout        timeout in milliseconds (must be &gt; 0)
	 * @param ticketCache    Kerberos ticket cache path (unused by the light backend)
	 * @param authentications requested authentication schemes; the light backend supports NTLM
	 * @return a new {@code LightWinRMService}
	 * @throws WinRMException on invalid arguments or an unsupported authentication request
	 */
	public static LightWinRMService createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final java.nio.file.Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws WinRMException {
		Utils.checkNonNull(winRMEndpoint, "winRMEndpoint");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		// Reject any list that requests a scheme the light backend cannot honour, even when NTLM is also
		// present. The authentications list is an ordered fallback: accepting e.g. [KERBEROS, NTLM] would
		// silently ignore the preferred Kerberos (and ticketCache) and downgrade to NTLM, which is weaker
		// and fails against NTLM-disabled servers. Fail loudly toward the escape hatch instead.
		if (authentications != null) {
			for (final AuthenticationEnum requested : authentications) {
				if (requested != AuthenticationEnum.NTLM) {
					throw new WinRMException(
						"The light WinRM backend currently supports only NTLM authentication (requested: " +
						authentications +
						"). Select the CXF backend with -Dorg.metricshub.winrm.backend=cxf until light support lands."
					);
				}
			}
		}

		if (winRMEndpoint.getProtocol() != WinRMHttpProtocolEnum.HTTP) {
			throw new WinRMException(
				"The light WinRM backend currently supports only HTTP (endpoint was " +
				winRMEndpoint.getEndpoint() +
				"). Select the CXF backend with -Dorg.metricshub.winrm.backend=cxf until light support lands."
			);
		}

		// Use the endpoint's own validated host/port rather than re-parsing the URL: URI.getHost()/getPort()
		// return null/-1 for names URI cannot classify (underscores, Unicode) that WinRMEndpoint accepts,
		// which would otherwise make the default backend unable to reach hosts the CXF backend could.
		final WsmanClient client = new WsmanClient(
			winRMEndpoint.getHostname(),
			winRMEndpoint.getPort(),
			winRMEndpoint.getDomain(),
			winRMEndpoint.getUsername(),
			new String(winRMEndpoint.getPassword()),
			timeout
		);
		return new LightWinRMService(winRMEndpoint, client);
	}

	@Override
	public List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout)
		throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		Utils.checkNonNull(wqlQuery, "wqlQuery");
		if (!WmiHelper.isValidWql(wqlQuery)) {
			throw new WqlQuerySyntaxException(wqlQuery);
		}
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		// Enforce the caller's timeout as a wall-clock deadline (throwing TimeoutException), matching
		// the CXF WinRMService and bounding the WSMan Pull loop.
		try {
			return Utils.execute(
				() -> {
					final List<Map<String, String>> rows = client.wql(winRMEndpoint.getNamespace(), wqlQuery);
					final List<Map<String, Object>> result = new ArrayList<>(rows.size());
					for (final Map<String, String> row : rows) {
						result.add(new LinkedHashMap<>(row));
					}
					return result;
				},
				timeout
			);
		} catch (final InterruptedException | ExecutionException e) {
			if (e.getCause() != null) {
				throw new WinRMException(e.getCause(), e.getCause().getMessage());
			}
			throw new WinRMException(e);
		}
	}

	@Override
	public WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Charset charset,
		final long timeout
	) throws WindowsRemoteException, TimeoutException {
		Utils.checkNonNull(command, "command");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		// Enforce the caller's timeout as a wall-clock deadline (throwing TimeoutException), matching
		// the CXF WinRMService and bounding the WSMan Receive loop.
		try {
			return Utils.execute(
				() -> {
					final long start = Utils.getCurrentTimeMillis();
					final WsmanClient.CommandOutput output = client.executeCommand(command, workingDirectory, charset);
					final float executionTime = (Utils.getCurrentTimeMillis() - start) / 1000.0f;
					return new WindowsRemoteCommandResult(output.stdout, output.stderr, executionTime, output.exitCode);
				},
				timeout
			);
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
		client.close();
	}
}
