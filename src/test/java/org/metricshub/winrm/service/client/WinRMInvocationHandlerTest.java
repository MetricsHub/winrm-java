package org.metricshub.winrm.service.client;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.Utils.EMPTY;
import static org.metricshub.winrm.Utils.checkNonNull;
import static org.metricshub.winrm.Utils.checkNonNullField;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.service.client.WinRMInvocationHandler.computeCredentials;
import static org.metricshub.winrm.service.client.WinRMInvocationHandler.createCallInfos;
import static org.metricshub.winrm.service.client.WinRMInvocationHandler.createCredentials;
import static org.metricshub.winrm.service.client.WinRMInvocationHandler.createWinRMWebService;
import static org.metricshub.winrm.service.client.WinRMInvocationHandler.getWebServiceClient;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.KERBEROS;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils.createCredentials;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.xml.soap.SOAPFactory;
import jakarta.xml.ws.WebServiceException;
import jakarta.xml.ws.soap.SOAPFaultException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import org.apache.cxf.Bus;
import org.apache.cxf.endpoint.Client;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.KerberosCredentials;
import org.apache.http.auth.NTCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.exceptions.KerberosCredentialsException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMWebService;
import org.metricshub.winrm.service.client.WinRMInvocationHandler.AuthCredentials;
import org.metricshub.winrm.service.client.WinRMInvocationHandler.RetryAuthenticationException;
import org.metricshub.winrm.service.client.WinRMInvocationHandler.RetryTgtExpirationException;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils;
import org.metricshub.winrm.service.client.auth.ntlm.NTCredentialsWithEncryption;
import org.mockito.MockedStatic;

class WinRMInvocationHandlerTest {

	private static final WinRMEndpoint WIN_RM_ENDPOINT = new WinRMEndpoint(
		null,
		"host",
		null,
		"JohnDoe",
		"pwd".toCharArray(),
		null
	);

	private static final WinRMEndpoint WIN_RM_ENDPOINT_2 = new WinRMEndpoint(
		null,
		"host2",
		null,
		"JohnDoe2",
		"pwd".toCharArray(),
		null
	);

	private static final WinRMEndpoint WIN_RM_ENDPOINT_3 = new WinRMEndpoint(
		null,
		"host3",
		null,
		"JohnDoe3",
		"pwd".toCharArray(),
		null
	);

	private static final Bus BUS = mock(Bus.class);
	private static final long TIMEOUT = 120L;
	private static final WinRMWebService WIN_RM_WS = mock(WinRMWebService.class);
	private static final Client WS_CLIENT = mock(Client.class);
	private static final KerberosCredentials KERBEROS_CREDENTIALS = mock(KerberosCredentials.class);
	private static final NTCredentials NTC_CREDENTIALS = mock(NTCredentialsWithEncryption.class);
	private static final Method METHOD = mock(Method.class);
	private static final List<AuthenticationEnum> AUTHENTICATIONS = singletonList(NTLM);

	private static final Queue<AuthenticationEnum> AUTHENTICATIONS_KERBEROS_NTLM = asList(KERBEROS, NTLM)
		.stream()
		.collect(Collectors.toCollection(LinkedList::new));

	private static final Queue<AuthenticationEnum> AUTHENTICATIONS_NTLM = AUTHENTICATIONS
		.stream()
		.collect(Collectors.toCollection(LinkedList::new));

	private static final Queue<AuthenticationEnum> AUTHENTICATIONS_KERBEROS = singletonList(KERBEROS)
		.stream()
		.collect(Collectors.toCollection(LinkedList::new));

	private static final AuthCredentials AUTH_CREDENTIALS_NTLM = new AuthCredentials(NTLM, NTC_CREDENTIALS);

	private static final AuthCredentials AUTH_CREDENTIALS_KERBEROS = new AuthCredentials(KERBEROS, KERBEROS_CREDENTIALS);

	private static final Object PROXY = new Object();
	private static final Object[] ARGS = {};
	private static final Object RESULT = new Object();

	/** to neutralize Utils.sleep */
	private static final MockedStatic<Utils> MOCKED_UTILS = mockStatic(Utils.class);

	private static final MockedStatic<WinRMInvocationHandler> MOCKED_WIN_RM_INVOCATION_HANDLER = mockStatic(
		WinRMInvocationHandler.class
	);

	@BeforeAll
	static void init() {
		MOCKED_UTILS.when(() -> checkNonNull(isNull(), anyString())).thenCallRealMethod();
		MOCKED_UTILS.when(() -> checkNonNull(any(), anyString())).thenCallRealMethod();

		MOCKED_UTILS.when(() -> checkNonNullField(isNull(), anyString())).thenCallRealMethod();
		MOCKED_UTILS.when(() -> checkNonNullField(any(), anyString())).thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER.when(() -> createWinRMWebService(WIN_RM_ENDPOINT, BUS)).thenReturn(WIN_RM_WS);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCredentials(any(WinRMEndpoint.class), eq(NTLM), isNull()))
			.thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCredentials(WIN_RM_ENDPOINT, KERBEROS, null))
			.thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCredentials(WIN_RM_ENDPOINT_2, KERBEROS, null))
			.thenThrow(KerberosCredentialsException.class);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCredentials(WIN_RM_ENDPOINT_2, NTLM, null))
			.thenReturn(NTC_CREDENTIALS);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCredentials(WIN_RM_ENDPOINT_3, NTLM, null))
			.thenThrow(IllegalStateException.class);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCredentials(WIN_RM_ENDPOINT_3, KERBEROS, null))
			.thenReturn(KERBEROS_CREDENTIALS);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> computeCredentials(WIN_RM_ENDPOINT, null, AUTHENTICATIONS_NTLM))
			.thenReturn(AUTH_CREDENTIALS_NTLM);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> computeCredentials(WIN_RM_ENDPOINT, null, AUTHENTICATIONS_KERBEROS_NTLM))
			.thenReturn(AUTH_CREDENTIALS_NTLM);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> computeCredentials(WIN_RM_ENDPOINT, null, AUTHENTICATIONS_KERBEROS))
			.thenReturn(AUTH_CREDENTIALS_KERBEROS);

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> computeCredentials(eq(WIN_RM_ENDPOINT_2), isNull(), any()))
			.thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> computeCredentials(eq(WIN_RM_ENDPOINT_3), isNull(), any()))
			.thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() ->
				getWebServiceClient(
					eq(WIN_RM_ENDPOINT),
					eq(TIMEOUT),
					isNull(),
					any(WinRMWebService.class),
					any(Credentials.class)
				)
			)
			.thenReturn(WS_CLIENT);

		MOCKED_WIN_RM_INVOCATION_HANDLER.when(() -> createCallInfos(isNull(), isNull())).thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER.when(() -> createCallInfos(isNull(), any(Object[].class))).thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER.when(() -> createCallInfos(any(Method.class), isNull())).thenCallRealMethod();

		MOCKED_WIN_RM_INVOCATION_HANDLER
			.when(() -> createCallInfos(any(Method.class), any(Object[].class)))
			.thenCallRealMethod();
	}

	@AfterAll
	static void closeMockStatics() {
		MOCKED_UTILS.close();
		MOCKED_WIN_RM_INVOCATION_HANDLER.close();
	}

	@BeforeEach
	void initMock() {
		doReturn("Receive").when(METHOD).getName();
	}

	@AfterEach
	void resetMocks() {
		reset(METHOD);
	}

	@Test
	void testNewWinRMInvocationHandlerInstance() {
		final String resourceUri = "resourceURI";
		final Path ticketCache = mock(Path.class);

		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		// check arguments
		assertThrows(
			IllegalArgumentException.class,
			() -> new WinRMInvocationHandler(null, BUS, TIMEOUT, resourceUri, ticketCache, AUTHENTICATIONS)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> new WinRMInvocationHandler(WIN_RM_ENDPOINT, null, TIMEOUT, resourceUri, ticketCache, AUTHENTICATIONS)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, resourceUri, ticketCache, null)
		);

		assertNotNull(winRMInvocationHandler);
		assertEquals(WS_CLIENT, winRMInvocationHandler.getClient());
	}

	@Test
	void testInvoke() throws Throwable {
		// check argument method null
		{
			final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
				WIN_RM_ENDPOINT,
				BUS,
				TIMEOUT,
				null,
				null,
				AUTHENTICATIONS
			);

			assertThrows(IllegalArgumentException.class, () -> winRMInvocationHandler.invoke(PROXY, null, ARGS));
		}

		// check KERBEROS authentication failure like a ticket validity expiration
		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			final List<AuthenticationEnum> authentications = singletonList(KERBEROS);

			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, authentications)
			);

			doThrow(
				new RetryTgtExpirationException(
					new RuntimeException("Authentication error on HTTP://host:5985 with user name \"JohnDoe\"")
				)
			)
				.doReturn(RESULT)
				.when(winRMInvocationHandler)
				.invokeMethod(METHOD, ARGS);

			mockedKerberosUtils
				.when(() -> KerberosUtils.createCredentials("JohnDoe", "pwd".toCharArray(), null))
				.thenReturn(KERBEROS_CREDENTIALS);

			assertEquals(RESULT, winRMInvocationHandler.invoke(PROXY, METHOD, ARGS));
			verify(winRMInvocationHandler, times(2)).invoke(PROXY, METHOD, ARGS);
		}

		// check KERBEROS authentication failure like a ticket validity expiration with an exception and no more retry
		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			final List<AuthenticationEnum> authentications = asList(KERBEROS, NTLM);

			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, authentications)
			);

			doThrow(
				new RetryTgtExpirationException(
					new RuntimeException("Authentication error on HTTP://host:5985 with user name \"JohnDoe\"")
				)
			)
				.doReturn(RESULT)
				.when(winRMInvocationHandler)
				.invokeMethod(METHOD, ARGS);

			doReturn(false).when(winRMInvocationHandler).continueToRetry();

			mockedKerberosUtils
				.when(() -> KerberosUtils.createCredentials("JohnDoe", "pwd".toCharArray(), null))
				.thenThrow(KerberosCredentialsException.class);

			assertThrows(KerberosCredentialsException.class, () -> winRMInvocationHandler.invoke(PROXY, METHOD, ARGS));
			verify(winRMInvocationHandler, times(1)).invoke(PROXY, METHOD, ARGS);
		}

		// check KERBEROS authentication failure like a ticket validity expiration with an exception and a retry
		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			final List<AuthenticationEnum> authentications = asList(KERBEROS, NTLM);

			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, authentications)
			);

			doThrow(
				new RetryTgtExpirationException(
					new RuntimeException("Authentication error on HTTP://host:5985 with user name \"JohnDoe\"")
				)
			)
				.doReturn(RESULT)
				.when(winRMInvocationHandler)
				.invokeMethod(METHOD, ARGS);

			doReturn(true).when(winRMInvocationHandler).continueToRetry();

			mockedKerberosUtils
				.when(() -> KerberosUtils.createCredentials("JohnDoe", "pwd".toCharArray(), null))
				.thenThrow(KerberosCredentialsException.class);

			assertEquals(RESULT, winRMInvocationHandler.invoke(PROXY, METHOD, ARGS));
			verify(winRMInvocationHandler, times(2)).invoke(PROXY, METHOD, ARGS);
		}

		// check retry
		{
			final List<AuthenticationEnum> authentications = asList(KERBEROS, NTLM);

			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, authentications)
			);

			doThrow(new RetryAuthenticationException(new SOAPFaultException(SOAPFactory.newInstance().createFault())))
				.doReturn(RESULT)
				.when(winRMInvocationHandler)
				.invokeMethod(METHOD, ARGS);

			doReturn(true, false).when(winRMInvocationHandler).continueToRetry();

			assertEquals(RESULT, winRMInvocationHandler.invoke(PROXY, METHOD, ARGS));
			verify(winRMInvocationHandler, times(2)).invoke(PROXY, METHOD, ARGS);
		}

		// check KO no retries KERBEROS over HTTP
		{
			final List<AuthenticationEnum> authentications = singletonList(KERBEROS);

			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, authentications)
			);

			doThrow(new RetryAuthenticationException(new SOAPFaultException(SOAPFactory.newInstance().createFault())))
				.when(winRMInvocationHandler)
				.invokeMethod(METHOD, ARGS);

			doReturn(false).when(winRMInvocationHandler).continueToRetry();

			final RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> winRMInvocationHandler.invoke(PROXY, METHOD, ARGS)
			);
			assertEquals("KERBEROS with encryption over HTTP is not implemented.", exception.getMessage());
			verify(winRMInvocationHandler, times(1)).invoke(PROXY, METHOD, ARGS);
		}

		// check KO no retries
		{
			final List<AuthenticationEnum> authentications = singletonList(NTLM);

			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, authentications)
			);

			doThrow(
				new RetryAuthenticationException(
					new RuntimeException("Authentication error on HTTP://host:5985 with user name \"JohnDoe\"")
				)
			)
				.when(winRMInvocationHandler)
				.invokeMethod(METHOD, ARGS);

			doReturn(false).when(winRMInvocationHandler).continueToRetry();

			assertThrows(RuntimeException.class, () -> winRMInvocationHandler.invoke(PROXY, METHOD, ARGS));
			verify(winRMInvocationHandler, times(1)).invoke(PROXY, METHOD, ARGS);
		}

		// check OK without retry
		{
			final WinRMInvocationHandler winRMInvocationHandler = spy(
				new WinRMInvocationHandler(WIN_RM_ENDPOINT, BUS, TIMEOUT, null, null, AUTHENTICATIONS)
			);

			doReturn(RESULT).when(winRMInvocationHandler).invokeMethod(METHOD, ARGS);

			assertEquals(RESULT, winRMInvocationHandler.invoke(PROXY, METHOD, ARGS));
			verify(winRMInvocationHandler, times(1)).invoke(PROXY, METHOD, ARGS);
		}
	}

	@Test
	void testComputeCredentials() throws Exception {
		// check KerberosCredentialsException no retry
		{
			final Queue<AuthenticationEnum> authenticationsQueue = singletonList(KERBEROS)
				.stream()
				.collect(Collectors.toCollection(LinkedList::new));

			assertThrows(
				KerberosCredentialsException.class,
				() -> computeCredentials(WIN_RM_ENDPOINT_2, null, authenticationsQueue)
			);
		}

		// check KerberosCredentialsException OK with retry
		{
			final Queue<AuthenticationEnum> authenticationsQueue = asList(KERBEROS, NTLM)
				.stream()
				.collect(Collectors.toCollection(LinkedList::new));

			assertEquals(AUTH_CREDENTIALS_NTLM, computeCredentials(WIN_RM_ENDPOINT_2, null, authenticationsQueue));
		}

		// check IllegalStateException on NTLM no retry
		{
			final Queue<AuthenticationEnum> authenticationsQueue = singletonList(NTLM)
				.stream()
				.collect(Collectors.toCollection(LinkedList::new));

			assertThrows(
				IllegalStateException.class,
				() -> computeCredentials(WIN_RM_ENDPOINT_3, null, authenticationsQueue)
			);
		}

		// check IllegalStateException on NTLM OK with retry
		{
			final Queue<AuthenticationEnum> authenticationsQueue = asList(NTLM, KERBEROS)
				.stream()
				.collect(Collectors.toCollection(LinkedList::new));

			assertEquals(AUTH_CREDENTIALS_KERBEROS, computeCredentials(WIN_RM_ENDPOINT_3, null, authenticationsQueue));
		}

		// check OK without Exception
		{
			final Queue<AuthenticationEnum> authenticationsQueue = singletonList(NTLM)
				.stream()
				.collect(Collectors.toCollection(LinkedList::new));

			assertEquals(AUTH_CREDENTIALS_NTLM, computeCredentials(WIN_RM_ENDPOINT_2, null, authenticationsQueue));
		}
	}

	@Test
	void testInvokeMethodSOAPFaultExceptionKerberosRetry() throws Exception {
		final List<AuthenticationEnum> authentications = singletonList(KERBEROS);

		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			authentications
		);

		doThrow(new InvocationTargetException(new SOAPFaultException(SOAPFactory.newInstance().createFault())))
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		assertThrows(RetryAuthenticationException.class, () -> winRMInvocationHandler.invokeMethod(METHOD, ARGS));

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethodSOAPFaultExceptionKO() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(new InvocationTargetException(new SOAPFaultException(SOAPFactory.newInstance().createFault())))
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		assertThrows(SOAPFaultException.class, () -> winRMInvocationHandler.invokeMethod(METHOD, ARGS));

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethodNotWebServiceExceptionNoRetryKO() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(new InvocationTargetException(new IllegalArgumentException())).when(METHOD).invoke(WIN_RM_WS, ARGS);

		final IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			() -> winRMInvocationHandler.invokeMethod(METHOD, ARGS)
		);

		assertEquals("Failure when calling Receive", exception.getMessage());

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethodWebServiceExceptionNotIOExceptionNoRetryKO() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(new InvocationTargetException(new WebServiceException())).when(METHOD).invoke(WIN_RM_WS, ARGS);

		final RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> winRMInvocationHandler.invokeMethod(METHOD, ARGS)
		);

		assertEquals("Exception occurred while making WinRM WebService call Receive", exception.getMessage());

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethodAuthorizationLoopRetryKerberos() throws Exception {
		final List<AuthenticationEnum> authentications = singletonList(KERBEROS);

		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			authentications
		);

		doThrow(
			new InvocationTargetException(new WebServiceException(new IOException("Authorization loop detected on Conduit")))
		)
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		final RetryTgtExpirationException exception = assertThrows(
			RetryTgtExpirationException.class,
			() -> winRMInvocationHandler.invokeMethod(METHOD, ARGS)
		);

		final Throwable cause = exception.getCause();
		assertTrue(cause instanceof RuntimeException);
		assertEquals("Authentication error on http://host:5985/wsman with user name \"JohnDoe\"", cause.getMessage());

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethodAuthorizationLoopRetryCredentials() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(
			new InvocationTargetException(new WebServiceException(new IOException("Authorization loop detected on Conduit")))
		)
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		final RetryAuthenticationException exception = assertThrows(
			RetryAuthenticationException.class,
			() -> winRMInvocationHandler.invokeMethod(METHOD, ARGS)
		);

		final Throwable cause = exception.getCause();
		assertTrue(cause instanceof RuntimeException);
		assertEquals("Authentication error on http://host:5985/wsman with user name \"JohnDoe\"", cause.getMessage());

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethod3RetriesKO() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(new InvocationTargetException(new WebServiceException(new IOException())))
			.doThrow(new InvocationTargetException(new WebServiceException(new IOException())))
			.doThrow(new InvocationTargetException(new WebServiceException(new IOException())))
			.doReturn(RESULT)
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		final RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> winRMInvocationHandler.invokeMethod(METHOD, ARGS)
		);

		assertEquals("failed task \"Receive\" after 3 attempts", exception.getMessage());

		verify(METHOD, times(3)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethodOK() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doReturn(RESULT).when(METHOD).invoke(WIN_RM_WS, ARGS);

		assertEquals(RESULT, winRMInvocationHandler.invokeMethod(METHOD, ARGS));

		verify(METHOD, times(1)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethod1RetryOK() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(new InvocationTargetException(new WebServiceException(new IOException())))
			.doReturn(RESULT)
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		assertEquals(RESULT, winRMInvocationHandler.invokeMethod(METHOD, ARGS));

		verify(METHOD, times(2)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testInvokeMethod2RetriesOK() throws Exception {
		final WinRMInvocationHandler winRMInvocationHandler = new WinRMInvocationHandler(
			WIN_RM_ENDPOINT,
			BUS,
			TIMEOUT,
			null,
			null,
			AUTHENTICATIONS
		);

		doThrow(new InvocationTargetException(new WebServiceException(new IOException())))
			.doThrow(new InvocationTargetException(new WebServiceException(new IOException())))
			.doReturn(RESULT)
			.when(METHOD)
			.invoke(WIN_RM_WS, ARGS);

		assertEquals(RESULT, winRMInvocationHandler.invokeMethod(METHOD, ARGS));

		verify(METHOD, times(3)).invoke(WIN_RM_WS, ARGS);
	}

	@Test
	void testCreateCredentials() {
		// check NTLM HTTP
		{
			final Credentials credentials = createCredentials(WIN_RM_ENDPOINT, NTLM, null);

			assertTrue(credentials instanceof NTCredentialsWithEncryption);
		}

		// check NTLM HTTPS
		{
			final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(HTTPS, "host", null, "JohnDoe", "pwd".toCharArray(), null);

			final Credentials credentials = createCredentials(winRMEndpoint, NTLM, null);

			assertFalse(credentials instanceof NTCredentialsWithEncryption);
			assertTrue(credentials instanceof NTCredentials);
		}

		// check KERBEROS
		try (final MockedStatic<KerberosUtils> mockedKerberosUtils = mockStatic(KerberosUtils.class)) {
			mockedKerberosUtils
				.when(() -> createCredentials(anyString(), any(char[].class), isNull()))
				.thenReturn(KERBEROS_CREDENTIALS);

			assertEquals(KERBEROS_CREDENTIALS, createCredentials(WIN_RM_ENDPOINT, KERBEROS, null));
		}
	}

	@Test
	void testCreateCallInfos() {
		assertEquals(EMPTY, createCallInfos(null, null));
		assertEquals(EMPTY, createCallInfos(mock(Method.class), null));
		assertEquals("Receive", createCallInfos(METHOD, null));
		assertEquals(EMPTY, createCallInfos(null, ARGS));

		final Object[] args = { "arg1", 2, true };
		assertEquals("Receive arg1 2 true", createCallInfos(METHOD, args));
	}
}
