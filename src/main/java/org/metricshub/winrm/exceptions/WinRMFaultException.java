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
 * The remote WinRM service answered with a WSMan fault (or a non-success HTTP status).
 * <p>
 * Beyond the human-readable message, the fault is exposed programmatically:
 * {@link #getFaultCode()} is the numeric WSManFault code (e.g. {@code 2150858778}) and
 * {@link #getFaultDetail()} the provider-level detail text — where WMI puts mnemonics such as
 * {@code WBEM_E_INVALID_CLASS} or {@code WBEM_E_INVALID_NAMESPACE} that callers historically had
 * to extract from the message with {@code contains()}.
 */
public class WinRMFaultException extends WinRMClientException {

	private static final long serialVersionUID = 1L;

	private final int httpStatus;
	private final String faultCode;
	private final String faultReason;
	private final String faultDetail;

	/**
	 * Create the exception.
	 *
	 * @param message the complete detail message (same format as the legacy API)
	 * @param httpStatus the HTTP status of the faulting response
	 * @param faultCode the WSManFault code, or {@code null} when the response carried none
	 * @param faultReason the SOAP fault reason text, or {@code null}
	 * @param faultDetail the detailed WSManFault message (provider-level detail), or {@code null}
	 */
	public WinRMFaultException(
		final String message,
		final int httpStatus,
		final String faultCode,
		final String faultReason,
		final String faultDetail
	) {
		super(message);
		this.httpStatus = httpStatus;
		this.faultCode = faultCode;
		this.faultReason = faultReason;
		this.faultDetail = faultDetail;
	}

	/**
	 * Get the HTTP status of the faulting response (typically 500 for a SOAP fault).
	 *
	 * @return the HTTP status code
	 */
	public int getHttpStatus() {
		return httpStatus;
	}

	/**
	 * Get the numeric WSManFault code, e.g. {@code 2150858778}.
	 *
	 * @return the fault code, or {@code null} when the response carried none
	 */
	public String getFaultCode() {
		return faultCode;
	}

	/**
	 * Get the SOAP fault reason text.
	 *
	 * @return the reason text, or {@code null}
	 */
	public String getFaultReason() {
		return faultReason;
	}

	/**
	 * Get the detailed WSManFault message — the provider-level detail where WMI puts mnemonics
	 * such as {@code WBEM_E_INVALID_CLASS}.
	 *
	 * @return the fault detail, or {@code null}
	 */
	public String getFaultDetail() {
		return faultDetail;
	}
}
