package org.metricshub.winrm.exceptions;

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

/**
 * The WQL query is syntactically invalid — the unchecked counterpart of the legacy
 * {@link WqlQuerySyntaxException}, thrown by the fluent {@link org.metricshub.winrm.WinRMClient}
 * API before anything is sent to the remote host.
 */
public class WqlSyntaxException extends WinRMClientException {

	private static final long serialVersionUID = 1L;

	/**
	 * Create the exception with a message and the underlying cause.
	 *
	 * @param message the detail message
	 * @param cause the underlying {@link WqlQuerySyntaxException}
	 */
	public WqlSyntaxException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
