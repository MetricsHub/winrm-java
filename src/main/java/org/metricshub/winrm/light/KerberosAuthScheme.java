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

import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * Kerberos (SPNEGO) authentication scheme using the JDK's built-in GSS-API — no Apache/CXF. It
 * obtains a TGT via JAAS ({@code Krb5LoginModule}) from a username+password (or a ticket cache),
 * then a service ticket for {@code HTTP/<host>} and emits the AP-REQ under the {@code Negotiate}
 * header.
 * <p>
 * HTTPS only. Like the CXF backend (which never implemented Kerberos message encryption over
 * HTTP), the SOAP travels plaintext inside TLS, so {@link #wrap}/{@link #unwrap} are pass-throughs.
 * <p>
 * Realm and KDC resolution is left to the ambient Kerberos configuration (a {@code krb5.conf} or
 * the {@code java.security.krb5.*} system properties), exactly as the CXF path did — the library
 * sets none itself.
 */
final class KerberosAuthScheme extends PlaintextSoapAuthScheme {

	// SPNEGO mechanism OID — the "Negotiate" scheme Windows http.sys expects.
	private static final String SPNEGO_OID = "1.3.6.1.5.5.2";

	private final String servicePrincipalHost;
	private final String username;
	// Kept as char[] by reference (never copied into a String): the caller owns the single wipeable
	// copy of the secret and may zero it after closing the client.
	private final char[] password;
	private final Path ticketCache;

	private GSSContext context;

	/**
	 * @param servicePrincipalHost the host whose {@code HTTP/<host>} SPN to target — must be the FQDN
	 *        the KDC knows (never an IP)
	 * @param username the account name (without any {@code DOMAIN\} prefix)
	 * @param password the account password (unused when {@code ticketCache} is set)
	 * @param ticketCache a Kerberos credential cache to reuse, or {@code null} to log in with
	 *        the password
	 */
	KerberosAuthScheme(
		final String servicePrincipalHost,
		final String username,
		final char[] password,
		final Path ticketCache
	) {
		this.servicePrincipalHost = servicePrincipalHost;
		this.username = username;
		this.password = password;
		this.ticketCache = ticketCache;
	}

	@Override
	public String authenticate(final HttpTransport transport) throws Exception {
		final Subject subject = login();
		final byte[] apReq = Subject.doAs(
			subject,
			(PrivilegedExceptionAction<byte[]>) () -> {
				final GSSManager manager = GSSManager.getInstance();
				final Oid spnego = new Oid(SPNEGO_OID);
				// NT_HOSTBASED_SERVICE "HTTP@host" maps to the SPN HTTP/host.
				final GSSName serverName = manager.createName("HTTP@" + servicePrincipalHost, GSSName.NT_HOSTBASED_SERVICE);
				context = manager.createContext(serverName, spnego, null, GSSContext.DEFAULT_LIFETIME);
				context.requestMutualAuth(true);
				context.requestCredDeleg(false);
				// The AP-REQ is complete after the first call; the KDC issued the service ticket using the
				// Subject's TGT. The server validates it on the first real request (and, over HTTPS, TLS
				// already authenticates the server, so we do not need to process a mutual-auth reply token).
				return context.initSecContext(new byte[0], 0, 0);
			}
		);
		authenticated = true;
		return "Negotiate " + Base64.getEncoder().encodeToString(apReq);
	}

	@Override
	public void reset() {
		// The wipe can come from two threads at once (close() and the last in-flight operation
		// releasing the connection) with no shared lock, so claim the context in a local before
		// disposing: a concurrent reset() that nulls the field cannot make this one dispose a
		// half-disposed GSSContext. Disposing the same context twice is the hazard, not the double
		// null-store, so the local (not the field) is what guards it.
		final GSSContext ctx = context;
		if (ctx != null) {
			try {
				ctx.dispose();
			} catch (final GSSException ignored) {
				// disposing a dead context is best-effort
			}
			context = null;
		}
		authenticated = false;
	}

	/** Obtain a Kerberos {@link Subject} (holding the TGT) via a programmatic JAAS login. */
	private Subject login() throws Exception {
		final LoginContext loginContext = new LoginContext("", null, callbackHandler(), krb5Configuration());
		loginContext.login();
		return loginContext.getSubject();
	}

	private CallbackHandler callbackHandler() {
		return callbacks -> {
			for (final Callback callback : callbacks) {
				if (callback instanceof NameCallback) {
					((NameCallback) callback).setName(username);
				} else if (callback instanceof PasswordCallback) {
					// PasswordCallback clones the array, so the caller's char[] stays the only
					// long-lived copy of the secret outside the JAAS machinery.
					((PasswordCallback) callback).setPassword(password);
				}
			}
		};
	}

	private Configuration krb5Configuration() {
		return new Configuration() {
			@Override
			public AppConfigurationEntry[] getAppConfigurationEntry(final String name) {
				final Map<String, Object> options = new HashMap<>();
				options.put("isInitiator", "true");
				options.put("refreshKrb5Config", "true");
				// Uppercase the whole principal: a lowercase UPN domain with an uppercase realm triggers a
				// "Message stream modified" KrbException; uppercasing avoids it (as the CXF path does).
				options.put("principal", username.toUpperCase(Locale.ROOT));
				if (ticketCache != null) {
					options.put("useTicketCache", "true");
					options.put("ticketCache", ticketCache.toString());
					options.put("doNotPrompt", "true");
				} else {
					options.put("useTicketCache", "false");
					options.put("doNotPrompt", "false");
				}
				return new AppConfigurationEntry[] {
						new AppConfigurationEntry(
							"com.sun.security.auth.module.Krb5LoginModule",
							AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
							options
						)
				};
			}
		};
	}
}
