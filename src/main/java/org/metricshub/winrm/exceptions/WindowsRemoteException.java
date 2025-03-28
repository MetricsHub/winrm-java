package org.metricshub.winrm.exceptions;

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

public class WindowsRemoteException extends Exception {

	private static final long serialVersionUID = 1L;

	public WindowsRemoteException(final String message) {
		super(message);
	}

	public WindowsRemoteException(final String messageFromat, final Object... args) {
		super(String.format(messageFromat, args));
	}

	public WindowsRemoteException(final Throwable cause, final String message) {
		super(message, cause);
	}

	public WindowsRemoteException(final Throwable cause, final String messageFromat, final Object... args) {
		super(String.format(messageFromat, args), cause);
	}

	public WindowsRemoteException(final Throwable cause) {
		super(cause);
	}
}
