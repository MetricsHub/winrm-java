package org.metricshub.winrm.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.Utils.EMPTY;
import static org.metricshub.winrm.service.WinRMService.createInstance;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.cxf.Bus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.client.WinRMInvocationHandler;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.service.enumeration.EnumerateResponse;
import org.metricshub.winrm.service.enumeration.EnumerationContextType;
import org.metricshub.winrm.service.transfer.ResourceCreated;
import org.metricshub.winrm.service.wsman.SelectorSetType;
import org.mockito.MockedStatic;

class WinRMServiceTest {

	private static final MockedStatic<WinRMService> MOCKED_WIN_RM_SERVICE = mockStatic(WinRMService.class);

	@BeforeAll
	static void init() {
		MOCKED_WIN_RM_SERVICE
			.when(() -> createInstance(isNull(), anyLong(), any(Path.class), anyList()))
			.thenCallRealMethod();

		MOCKED_WIN_RM_SERVICE
			.when(() -> createInstance(any(WinRMEndpoint.class), anyLong(), any(Path.class), anyList()))
			.thenCallRealMethod();

		MOCKED_WIN_RM_SERVICE
			.when(() -> createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
			.thenCallRealMethod();

		MOCKED_WIN_RM_SERVICE
			.when(() ->
				WinRMService.createWinRMInvocationHandlerInstance(
					any(WinRMEndpoint.class),
					any(Bus.class),
					anyLong(),
					anyString(),
					isNull(),
					anyList()
				)
			)
			.thenReturn(mock(WinRMInvocationHandler.class));

		MOCKED_WIN_RM_SERVICE
			.when(() ->
				WinRMService.createWinRMInvocationHandlerInstance(
					any(WinRMEndpoint.class),
					any(Bus.class),
					anyLong(),
					isNull(),
					isNull(),
					anyList()
				)
			)
			.thenReturn(mock(WinRMInvocationHandler.class));
	}

	@AfterAll
	static void closeMockStatics() {
		MOCKED_WIN_RM_SERVICE.close();
	}

	@Test
	void testCreateInstance() throws Exception {
		final String hostname = "host";
		final String username = "user";
		final String rawUsername = "domain\\" + username;
		final char[] password = "pwd".toCharArray();
		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(null, hostname, null, rawUsername, password, null);
		final long timeout = 30 * 1000L;
		final Path ticketCache = Paths.get("path");
		final List<AuthenticationEnum> authentications = singletonList(NTLM);

		//check arguments
		assertThrows(IllegalArgumentException.class, () -> createInstance(null, timeout, ticketCache, authentications));

		assertThrows(
			IllegalArgumentException.class,
			() -> createInstance(winRMEndpoint, -1L, ticketCache, authentications)
		);

		assertThrows(IllegalArgumentException.class, () -> createInstance(winRMEndpoint, 0L, ticketCache, authentications));

		final WinRMService winRMService1 = createInstance(winRMEndpoint, timeout, null, null);
		assertNotNull(winRMService1);
		assertEquals(1, winRMService1.getUseCount());
		assertEquals(rawUsername, winRMService1.getUsername());
		assertEquals(hostname, winRMService1.getHostname());
		assertArrayEquals(password, winRMService1.getPassword());
		assertTrue(winRMService1.isConnected());

		final WinRMService winRMService2 = createInstance(winRMEndpoint, timeout, null, null);
		assertNotNull(winRMService2);
		assertEquals(2, winRMService1.getUseCount());
		assertEquals(2, winRMService2.getUseCount());
		assertEquals(rawUsername, winRMService2.getUsername());
		assertEquals(hostname, winRMService2.getHostname());
		assertArrayEquals(password, winRMService2.getPassword());
		assertTrue(winRMService1.isConnected());
		assertTrue(winRMService2.isConnected());

		winRMService1.close();
		assertTrue(winRMService1.isConnected());
		assertTrue(winRMService2.isConnected());
		assertEquals(1, winRMService1.getUseCount());
		assertEquals(1, winRMService2.getUseCount());

		winRMService2.close();
		assertFalse(winRMService1.isConnected());
		assertFalse(winRMService2.isConnected());
		assertEquals(0, winRMService1.getUseCount());
		assertEquals(0, winRMService2.getUseCount());
	}

	@Test
	void testExecuteCommand() throws Exception {
		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(
			null,
			"host",
			null,
			"domain\\user",
			"pwd".toCharArray(),
			null
		);
		final long timeout = 30 * 1000L;
		final String command = "ipconfig";
		final String workingDirectory = "dir";

		//check arguments
		try (final WinRMService winRMService = createInstance(winRMEndpoint, timeout, null, null)) {
			assertThrows(
				IllegalArgumentException.class,
				() -> winRMService.executeCommand(null, workingDirectory, UTF_8, timeout)
			);

			assertThrows(
				IllegalArgumentException.class,
				() -> winRMService.executeCommand(command, workingDirectory, UTF_8, -1L)
			);

			assertThrows(
				IllegalArgumentException.class,
				() -> winRMService.executeCommand(command, workingDirectory, UTF_8, 0L)
			);
		}

		try (final WinRMService winRMService = spy(createInstance(winRMEndpoint, timeout, null, null))) {
			doNothing().when(winRMService).checkConnectedFirst();

			doReturn(new ResourceCreated()).when(winRMService).create(null);

			doReturn(0).when(winRMService).execute(eq(command), any(StringWriter.class), any(StringWriter.class), eq(UTF_8));

			final WindowsRemoteCommandResult actual = winRMService.executeCommand(command, null, null, timeout);

			assertEquals(EMPTY, actual.getStdout());
			assertEquals(EMPTY, actual.getStderr());
		}

		try (final WinRMService winRMService = spy(createInstance(winRMEndpoint, timeout, null, null))) {
			doNothing().when(winRMService).checkConnectedFirst();

			doReturn(new SelectorSetType()).when(winRMService).getShellSelector();
			verify(winRMService, times(0)).create(null);

			doReturn(0).when(winRMService).execute(eq(command), any(StringWriter.class), any(StringWriter.class), eq(UTF_8));

			final WindowsRemoteCommandResult actual = winRMService.executeCommand(command, null, null, timeout);

			assertEquals(EMPTY, actual.getStdout());
			assertEquals(EMPTY, actual.getStderr());
		}
	}

	@Test
	void testExecuteWql() throws Exception {
		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(
			null,
			"host",
			null,
			"domain\\user",
			"pwd".toCharArray(),
			null
		);
		final long timeout = 30 * 1000L;
		final String wqlQuery = "Select Name,Path from Win32_Share";

		//check arguments
		try (final WinRMService winRMService = createInstance(winRMEndpoint, timeout, null, null)) {
			assertThrows(IllegalArgumentException.class, () -> winRMService.executeWql(null, timeout));

			assertThrows(WqlQuerySyntaxException.class, () -> winRMService.executeWql(EMPTY, timeout));

			assertThrows(WqlQuerySyntaxException.class, () -> winRMService.executeWql("Win32_Share", timeout));

			assertThrows(IllegalArgumentException.class, () -> winRMService.executeWql(wqlQuery, -1L));

			assertThrows(IllegalArgumentException.class, () -> winRMService.executeWql(wqlQuery, 0L));
		}

		try (final WinRMService winRMService = spy(createInstance(winRMEndpoint, timeout, null, null))) {
			doNothing().when(winRMService).checkConnectedFirst();

			final EnumerationContextType contextType = mock(EnumerationContextType.class);
			final EnumerateResponse enumerateResponse = new EnumerateResponse();
			enumerateResponse.setEnumerationContext(contextType);

			doReturn(enumerateResponse).when(winRMService).enumerate(wqlQuery);
			doReturn(emptyList()).when(contextType).getContent();

			doReturn(true).when(winRMService).getItemsFrom(eq(enumerateResponse), anyList());

			verify(winRMService, times(0)).getContextIdFrom(eq(contextType));
			verify(winRMService, times(0)).pull(anyString(), anyList());

			assertEquals(emptyList(), winRMService.executeWql(wqlQuery, timeout));
		}

		try (final WinRMService winRMService = spy(createInstance(winRMEndpoint, timeout, null, null))) {
			doNothing().when(winRMService).checkConnectedFirst();

			final EnumerationContextType contextType = mock(EnumerationContextType.class);
			final EnumerateResponse enumerateResponse = new EnumerateResponse();
			enumerateResponse.setEnumerationContext(contextType);

			doReturn(enumerateResponse).when(winRMService).enumerate(wqlQuery);
			doReturn(emptyList()).when(contextType).getContent();

			doReturn(false).when(winRMService).getItemsFrom(eq(enumerateResponse), anyList());

			doReturn("nextContextId").when(winRMService).getContextIdFrom(eq(contextType));
			doReturn("nextContextId").when(winRMService).pull(anyString(), anyList());

			assertEquals(emptyList(), winRMService.executeWql(wqlQuery, timeout));
		}
	}
}
