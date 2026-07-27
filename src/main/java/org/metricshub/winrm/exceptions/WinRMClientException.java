package org.metricshub.winrm.exceptions;

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

/**
 * Base unchecked exception of the fluent {@link org.metricshub.winrm.WinRMClient} API.
 * <p>
 * Specific failures are reported through the subtypes {@link WinRMAuthenticationException},
 * {@link WinRMFaultException}, {@link WinRMTimeoutException} and {@link WqlSyntaxException},
 * so callers can catch exactly what they care about — or just this type for everything.
 * The legacy checked exceptions ({@link WinRMException}, {@link WindowsRemoteException},
 * {@link WqlQuerySyntaxException}) remain on the legacy API and are unaffected.
 */
public class WinRMClientException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Create the exception with a message.
	 *
	 * @param message the detail message
	 */
	public WinRMClientException(final String message) {
		super(message);
	}

	/**
	 * Create the exception with a message and the underlying cause.
	 *
	 * @param message the detail message
	 * @param cause the underlying cause
	 */
	public WinRMClientException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
