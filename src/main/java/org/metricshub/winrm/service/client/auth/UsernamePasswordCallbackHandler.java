package org.metricshub.winrm.service.client.auth;

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

import java.io.IOException;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

public class UsernamePasswordCallbackHandler implements CallbackHandler {

	private final String username;
	private final char[] password;

	/**
	 * UsernamePasswordCallbackHandler constructor
	 *
	 * @param username name of the user to authenticate
	 * @param password The password
	 */
	public UsernamePasswordCallbackHandler(final String username, final char[] password) {
		this.username = username;
		this.password = password;
	}

	@Override
	public void handle(final Callback[] callbacks) throws IOException, UnsupportedCallbackException {
		if (callbacks == null) {
			return;
		}

		for (final Callback callback : callbacks) {
			if (callback instanceof NameCallback) {
				final NameCallback nameCallback = (NameCallback) callback;
				nameCallback.setName(username);
			} else if (callback instanceof PasswordCallback) {
				final PasswordCallback passwordCallback = (PasswordCallback) callback;
				passwordCallback.setPassword(password);
			} else {
				throw new UnsupportedCallbackException(callback, "Unknown Callback");
			}
		}
	}
}
