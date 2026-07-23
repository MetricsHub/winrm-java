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

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS setup for the light backend's HTTPS transport.
 *
 * <p>Unlike the legacy CXF path (which trusts every certificate), the light backend validates by
 * default: it uses the JDK default {@link SSLSocketFactory}, so the platform trust store (and any
 * {@code -Djavax.net.ssl.trustStore}) applies and the server hostname is verified during the
 * handshake. Setting the system property {@value #INSECURE_PROPERTY} to {@code true} opts out —
 * trusting all certificates and skipping hostname verification — for self-signed test hosts. That
 * is insecure and must not be used in production.
 */
final class LightTls {

	/** System property that disables TLS certificate and hostname validation (insecure; testing only). */
	static final String INSECURE_PROPERTY = "org.metricshub.winrm.tls.insecure";

	private LightTls() {}

	/** Whether TLS validation has been disabled via {@value #INSECURE_PROPERTY}. */
	static boolean isInsecure() {
		return Boolean.getBoolean(INSECURE_PROPERTY);
	}

	/** Whether the server hostname should be verified during the TLS handshake (true unless insecure). */
	static boolean verifyHostname() {
		return !isInsecure();
	}

	/**
	 * The socket factory for HTTPS connections: the JDK default (validating) factory, or a
	 * trust-all factory when {@value #INSECURE_PROPERTY} is set.
	 *
	 * @return an {@link SSLSocketFactory}
	 */
	static SSLSocketFactory socketFactory() {
		if (!isInsecure()) {
			return (SSLSocketFactory) SSLSocketFactory.getDefault();
		}
		try {
			final SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[] { trustAllManager() }, null);
			return context.getSocketFactory();
		} catch (final GeneralSecurityException e) {
			throw new IllegalStateException("Cannot build an insecure (trust-all) TLS context", e);
		}
	}

	private static X509TrustManager trustAllManager() {
		return new X509TrustManager() {
			@Override
			public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
				// insecure mode: accept any client certificate
			}

			@Override
			public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
				// insecure mode: accept any server certificate
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
	}
}
