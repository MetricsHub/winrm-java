package org.metricshub.winrm.light;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.security.auth.login.CredentialException;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.junit.jupiter.api.Test;

/**
 * Credential delegation of {@link KerberosAuthScheme}, without a KDC: before its first token, a
 * GSS context reports the flags it was asked for, and the delegation check only reads the flag the
 * initialized context reports (a live test covers the real exchange).
 */
class KerberosAuthSchemeTest {

	@Test
	void requestsDelegationOnlyWhenAllowed() throws Exception {
		// A realm-qualified name: a host-based one would need a Kerberos configuration to resolve
		final GSSName name = GSSManager
			.getInstance()
			.createName("HTTP/server.example.net@EXAMPLE.NET", GSSName.NT_USER_NAME);
		final GSSContext plain = KerberosAuthScheme.createContext(name, false);
		final GSSContext delegating = KerberosAuthScheme.createContext(name, true);
		try {
			assertFalse(plain.getCredDelegState());
			assertTrue(delegating.getCredDelegState());
			assertTrue(plain.getMutualAuthState());
			assertTrue(delegating.getMutualAuthState());

			// GSS drops the request silently when the TGT is not forwardable: the check must not
			KerberosAuthScheme.checkDelegation(plain, false);
			KerberosAuthScheme.checkDelegation(delegating, true);
			final CredentialException e = assertThrows(
				CredentialException.class,
				() -> KerberosAuthScheme.checkDelegation(plain, true)
			);
			assertTrue(e.getMessage().contains("forwardable = true"), e.getMessage());
		} finally {
			plain.dispose();
			delegating.dispose();
		}
	}
}
