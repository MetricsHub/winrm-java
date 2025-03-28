package org.metricshub.winrm.service.client.auth.kerberos;

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

import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.apache.http.auth.KerberosCredentials;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.exceptions.KerberosCredentialsException;
import org.metricshub.winrm.service.client.auth.UsernamePasswordCallbackHandler;

public class KerberosUtils {

	private KerberosUtils() {}

	private static final String TRUE = Boolean.TRUE.toString();
	private static final String FALSE = Boolean.FALSE.toString();

	private static final String DO_NOT_PROMPT = "doNotPrompt";
	private static final String USE_TICKET_CACHE = "useTicketCache";
	private static final String IS_INITIATOR = "isInitiator";
	private static final String CLIENT = "client";
	private static final String REFRESH_KRB5_CONFIG = "refreshKrb5Config";

	/**
	 * Object identifier of Kerberos as mechanism used by GSS to obtain the Ticket Granting Ticket (TGT).
	 * @see http://oid-info.com/get/1.2.840.113554.1.2.2
	 */
	private static final String KERBEROS_V5_OID = "1.2.840.113554.1.2.2";

	private static final Configuration JAAS_CONFIG;

	static {
		final Map<String, String> moduleOptions = new HashMap<>();
		moduleOptions.put(REFRESH_KRB5_CONFIG, TRUE);
		moduleOptions.put(CLIENT, TRUE);
		moduleOptions.put(IS_INITIATOR, TRUE);

		// useTicketCache = false, The TGT cache is not used, and the user is prompted for credentials login
		moduleOptions.put(USE_TICKET_CACHE, FALSE);
		moduleOptions.put(DO_NOT_PROMPT, FALSE);

		JAAS_CONFIG =
			new Configuration() {
				@Override
				public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
					return createAppConfigurationEntries(moduleOptions);
				}
			};
	}

	private static AppConfigurationEntry[] createAppConfigurationEntries(final Map<String, String> moduleOptions) {
		return new AppConfigurationEntry[] {
			new AppConfigurationEntry(
				"com.sun.security.auth.module.Krb5LoginModule",
				LoginModuleControlFlag.REQUIRED,
				moduleOptions
			)
		};
	}

	/**
	 * Get Kerberos credentials (i.e a TGT) with the username and password provided.
	 *
	 * @param username The user name (mandatory)
	 * @param password The password (mandatory)
	 * @param ticketCache The Ticket Cache path
	 *
	 * @return credentials wrapping the TGT which will be used for obtaining the SPNego token
	 * @throws KerberosCredentialsException when an error occurred on Kerberos authentication
	 */
	public static KerberosCredentials createCredentials(
		final String username,
		final char[] password,
		final Path ticketCache
	) {
		Utils.checkNonNull(username, "username");
		Utils.checkNonNull(password, "password");

		try {
			// If the Kerberos Realm is in uppercases (which is the norm) and the domain in the user principal (UPN) is in
			// lowercases, a KrbException: "Message stream modified" is thrown.
			// To avoid this exception we force the UPN in uppercases
			final String canonizedUsername = username.trim().toUpperCase();

			final Configuration configuration = ticketCache != null
				? createConfigurationWithTicketCache(ticketCache)
				: JAAS_CONFIG;

			final Subject subject = authenticate(canonizedUsername, password, configuration);

			final PrivilegedAction<GSSCredential> privilegedAction = createPrivilegedAction(canonizedUsername);

			final GSSCredential gssUserCredential = Subject.doAs(subject, privilegedAction);

			return new KerberosCredentials(gssUserCredential);
		} catch (final KerberosCredentialsException e) {
			throw e;
		} catch (final Exception e) {
			throw new KerberosCredentialsException(e);
		}
	}

	public static Configuration createConfigurationWithTicketCache(final Path ticketCache) {
		final Map<String, String> moduleOptions = new HashMap<>();
		moduleOptions.put(REFRESH_KRB5_CONFIG, TRUE);
		moduleOptions.put(CLIENT, TRUE);
		moduleOptions.put(IS_INITIATOR, TRUE);

		// useTicketCache = true, The default TGT cache is used, the user is not prompt for authentication
		// and then failed if the user TGT is not in the cache.
		moduleOptions.put(USE_TICKET_CACHE, TRUE);
		moduleOptions.put(DO_NOT_PROMPT, TRUE);
		moduleOptions.put("ticketCache", ticketCache.toString());

		return new Configuration() {
			@Override
			public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
				return createAppConfigurationEntries(moduleOptions);
			}
		};
	}

	/**
	 * Authenticate the user with the provided password. The login send a request AS-REQ to the Authentication Server.
	 * The response will contain the TGT which will be store in the Subject.
	 *
	 * @param username name of the user to authenticate
	 * @param password The password
	 * @param configuration the {@code Configuration} lists the login modules to be called to perform the authentication
	 *
	 * @return subject of the authenticated user
	 */
	public static Subject authenticate(final String username, final char[] password, final Configuration configuration) {
		try {
			final CallbackHandler callbackHandler = new UsernamePasswordCallbackHandler(username, password);

			final LoginContext loginContext = createLoginContext(callbackHandler, configuration);

			loginContext.login();

			return loginContext.getSubject();
		} catch (final LoginException e) {
			throw new KerberosCredentialsException("Kerberos Login failure. Make sure Kerberos is properly configured.", e);
		}
	}

	private static PrivilegedAction<GSSCredential> createPrivilegedAction(final String username) {
		return () -> {
			try {
				final GSSManager gssManager = GSSManager.getInstance();

				final GSSName gssUserName = gssManager.createName(username, null);

				return gssManager.createCredential(
					gssUserName,
					GSSContext.DEFAULT_LIFETIME,
					new Oid(KERBEROS_V5_OID),
					GSSCredential.INITIATE_ONLY
				);
			} catch (final GSSException e) {
				throw new KerberosCredentialsException(
					String.format("Unable to create credential for user \"%s\" after login", username),
					e
				);
			}
		};
	}

	public static LoginContext createLoginContext(
		final CallbackHandler callbackHandler,
		final Configuration configuration
	) throws LoginException {
		return new LoginContext(Utils.EMPTY, null, callbackHandler, configuration);
	}
}
