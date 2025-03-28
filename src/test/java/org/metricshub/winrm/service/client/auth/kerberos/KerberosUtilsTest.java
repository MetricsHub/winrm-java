package org.metricshub.winrm.service.client.auth.kerberos;

import static java.nio.file.Paths.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils.authenticate;
import static org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils.createConfigurationWithTicketCache;
import static org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils.createCredentials;
import static org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils.createLoginContext;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.nio.file.Path;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.KerberosCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.exceptions.KerberosCredentialsException;
import org.mockito.MockedStatic;

class KerberosUtilsTest {

	private static final String USERNAME = "username";
	private static final char[] PASSWORD = "pwd".toCharArray();
	private static final Path TICKET_CACHE = get("path");

	// To neutralize Subject.doAs
	private static final MockedStatic<Subject> MOCKED_SUBJECT = mockStatic(Subject.class);

	@AfterAll
	static void closeMockStatics() {
		MOCKED_SUBJECT.close();
	}

	@Test
	void testCreateCredentialsArgumentsKO() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> createCredentials(null, PASSWORD, TICKET_CACHE));

		assertThrows(IllegalArgumentException.class, () -> createCredentials(USERNAME, null, TICKET_CACHE));

		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			mockedKerberosUtils.when(() -> createCredentials(USERNAME, PASSWORD, null)).thenCallRealMethod();

			mockedKerberosUtils
				.when(() -> authenticate(anyString(), any(char[].class), any(Configuration.class)))
				.thenCallRealMethod();

			final LoginContext loginContext = mock(LoginContext.class);

			mockedKerberosUtils
				.when(() -> createLoginContext(any(CallbackHandler.class), any(Configuration.class)))
				.thenReturn(loginContext);

			doThrow(new LoginException("KrbException: Cannot locate default realm")).when(loginContext).login();

			final KerberosCredentialsException exception = assertThrows(
				KerberosCredentialsException.class,
				() -> createCredentials(USERNAME, PASSWORD, null)
			);

			assertEquals("Kerberos Login failure. Make sure Kerberos is properly configured.", exception.getMessage());
			assertEquals(LoginException.class, exception.getCause().getClass());
			assertEquals("KrbException: Cannot locate default realm", exception.getCause().getMessage());
		}

		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			mockedKerberosUtils.when(() -> createCredentials(USERNAME, PASSWORD, null)).thenCallRealMethod();

			mockedKerberosUtils
				.when(() -> authenticate(anyString(), any(char[].class), any(Configuration.class)))
				.thenThrow(new SecurityException("Security Error"));

			final KerberosCredentialsException exception = assertThrows(
				KerberosCredentialsException.class,
				() -> createCredentials(USERNAME, PASSWORD, null)
			);

			assertEquals("java.lang.SecurityException: Security Error", exception.getMessage());
			assertEquals(SecurityException.class, exception.getCause().getClass());
			assertEquals("Security Error", exception.getCause().getMessage());
		}
	}

	@Test
	void testCreateCredentialsArgumentsWithoutTicketCacheOK() {
		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			mockedKerberosUtils.when(() -> createCredentials(USERNAME, PASSWORD, null)).thenCallRealMethod();

			final Credentials actual = createCredentials(USERNAME, PASSWORD, null);
			assertNotNull(actual);
			assertTrue(actual instanceof KerberosCredentials);

			mockedKerberosUtils.verify(() -> createConfigurationWithTicketCache(any(Path.class)), times(0));
		}
	}

	@Test
	void testCreateCredentialsArgumentsWithTicketCacheOK() {
		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			mockedKerberosUtils.when(() -> createCredentials(USERNAME, PASSWORD, TICKET_CACHE)).thenCallRealMethod();

			final Credentials actual = createCredentials(USERNAME, PASSWORD, TICKET_CACHE);
			assertNotNull(actual);
			assertTrue(actual instanceof KerberosCredentials);

			mockedKerberosUtils.verify(() -> createConfigurationWithTicketCache(any(Path.class)), times(1));
		}
	}
}
