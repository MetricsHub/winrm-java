package org.sentrysoftware.winrm.exceptions;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 Sentry Software
 * ჻჻჻჻჻჻
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * ╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱
 */

public class WindowsRemoteException extends Exception {

	private static final long serialVersionUID = 1L;

	public WindowsRemoteException(final String message) {
		super(message);
	}

	public WindowsRemoteException(final String messageFromat, final Object...args) {
		super(String.format(messageFromat, args));
	}

	public WindowsRemoteException(final Throwable cause, final String message) {
		super(message, cause);
	}

	public WindowsRemoteException(final Throwable cause, final String messageFromat, final Object...args) {
		super(String.format(messageFromat, args), cause);
	}

	public WindowsRemoteException(final Throwable cause) {
		super(cause);
	}

}
