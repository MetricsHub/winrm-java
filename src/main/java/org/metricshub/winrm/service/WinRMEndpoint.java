package org.metricshub.winrm.service;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2024 Metricshub
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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Objects;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WmiHelper;

public class WinRMEndpoint {

	private static final int DEFAULT_WIN_RM_HTTP_PORT = 5985;
	private static final int DEFAULT_WIN_RM_HTTPS_PORT = 5986;

	private final String hostname;
	private final String endpoint;
	private final String domain;
	private final String username;
	private final char[] password;
	private final String namespace;
	private final String rawUsername;
	private final WinRMHttpProtocolEnum protocol;

	/**
	 * Constructor of the endpoint and credentials for WinRM.
	 *
	 * @param protocol The protocol
	 * @param hostname The host name (mandatory)
	 * @param port The port
	 * @param username The user name (mandatory)
	 * @param password The password (mandatory)
	 * @param namespace The namespace default value: {@value WmiHelper#DEFAULT_NAMESPACE}
	 */
	public WinRMEndpoint(
		final WinRMHttpProtocolEnum protocol,
		final String hostname,
		final Integer port,
		final String username,
		final char[] password,
		final String namespace
	) {
		Utils.checkNonNull(hostname, "hostname");
		Utils.checkNonNull(username, "username");
		Utils.checkNonNull(password, "password");

		this.hostname = hostname.replaceAll("\\s", Utils.EMPTY);
		this.password = password;
		rawUsername = username;

		this.namespace = buildNamespace(namespace);

		final String user = username.replaceAll("\\s", Utils.EMPTY);
		if (user.contains("\\")) {
			final String[] array = user.split("\\\\");
			domain = array[0];
			this.username = array[1];
		} else {
			domain = null;
			this.username = user;
		}

		this.protocol = protocol != null ? protocol : WinRMHttpProtocolEnum.HTTP;
		final String endpointUrl = buildEndpointUrl(this.protocol, this.hostname, port);

		endpoint = buildWSManEndpoint(endpointUrl);
	}

	/** Get the hostname */
	public String getHostname() {
		return hostname;
	}

	/** Get the endpoint in the form of http(s)://host:port/wsman */
	public String getEndpoint() {
		return endpoint;
	}

	/** Get the domain from the raw username */
	public String getDomain() {
		return domain;
	}

	/** get the username as indicated in the constructor (could be in domain\\user form) */
	public String getRawUsername() {
		return rawUsername;
	}

	/** Get the username part of the raw username */
	public String getUsername() {
		return username;
	}

	/** Get the password */
	public char[] getPassword() {
		return password;
	}

	/** Get the namespace */
	public String getNamespace() {
		return namespace;
	}

	/** Get the protocol */
	public WinRMHttpProtocolEnum getProtocol() {
		return protocol;
	}

	/**
	 * Build the endpoint URL.
	 *
	 * @param protocol The HTTP protocol
	 * @param hostname The host name
	 * @param port The port
	 *
	 * @return the endpoint URL in form of protocol://hostname:port (with protocol in HTTP or HTTPS).
	 */
	public static String buildEndpointUrl(
		final WinRMHttpProtocolEnum protocol,
		final String hostname,
		final Integer port
	) {
		final int endpointPort = getEndpointPort(protocol, port);

		return String.format("%s://%s:%d", protocol.toString(), hostname, endpointPort);
	}

	/**
	 *	Get the endpoint port:
	 *<ul>
	 * <li>The port if provided</li>
	 * <li>5986 if the protocol is HTTPS</li>
	 * <li>5985 otherwise</li>
	 *</ul>
	 * @param protocol The HTTP protocol
	 * @param port The port
	 *
	 * @return The endpoint port
	 */
	public static int getEndpointPort(final WinRMHttpProtocolEnum protocol, final Integer port) {
		if (port != null) {
			return port;
		}
		return protocol == WinRMHttpProtocolEnum.HTTPS ? DEFAULT_WIN_RM_HTTPS_PORT : DEFAULT_WIN_RM_HTTP_PORT;
	}

	/**
	 * Get the namespace and replace '\' by '/' if necessary
	 * default: {@value WmiHelper#DEFAULT_NAMESPACE} if null
	 * @param namespace the provided namespace
	 *
	 * @return
	 */
	public static String buildNamespace(final String namespace) {
		final String cleanNamespace = namespace != null ? namespace.replaceAll("\\s", Utils.EMPTY) : Utils.EMPTY;

		final String usedNamespace = Utils.isNotBlank(cleanNamespace) ? cleanNamespace : WmiHelper.DEFAULT_NAMESPACE;

		return usedNamespace.replace('\\', '/');
	}

	/**
	 * Build the WSMan URL endpoint.
	 *
	 * @param endpoint the endpoint in form of protocol://hostname:port (with protocol in HTTP or HTTPS).
	 *
	 * @return The external form of the endpoint URL
	 */
	private static String buildWSManEndpoint(final String endpoint) {
		try {
			return new URL(String.format("%s/wsman", endpoint)).toExternalForm();
		} catch (final MalformedURLException e) {
			throw new IllegalArgumentException(String.format("endpoint %s is invalid.", endpoint), e);
		}
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(password);
		result = prime * result + Objects.hash(endpoint, namespace, rawUsername);
		return result;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof WinRMEndpoint)) {
			return false;
		}
		final WinRMEndpoint other = (WinRMEndpoint) obj;
		return (
			Objects.equals(endpoint, other.endpoint) &&
			Objects.equals(namespace, other.namespace) &&
			Arrays.equals(password, other.password) &&
			Objects.equals(rawUsername, other.rawUsername)
		);
	}

	@Override
	public String toString() {
		return new StringBuilder()
			.append("WinRMEndpoint [")
			.append("endpoint=")
			.append(endpoint)
			.append(", domain=")
			.append(domain)
			.append(", username=")
			.append(username)
			.append(", namespace=")
			.append(namespace)
			.append("]")
			.toString();
	}
}
