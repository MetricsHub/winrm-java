package org.metricshub.winrm.command;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.WindowsRemoteProcessUtils.copyLocalFilesToShare;
import static org.metricshub.winrm.WindowsRemoteProcessUtils.getWindowsEncodingCharset;
import static org.metricshub.winrm.command.WinRMCommandExecutor.execute;
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

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.WindowsRemoteProcessUtils;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.shares.SmbTempShare;
import org.mockito.MockedStatic;

class WinRMCommandExecutorTest {

	@Test
	void testExecute() throws Exception {
		final String command = "launch";
		final String hostname = "host";
		final String username = "domain\\user";
		final char[] password = "pass".toCharArray();
		final String workingDirectory = " \t\r\n dir \t\r\n ";
		final long timeout = 30 * 1000L;
		final List<String> localFileToCopyList = singletonList(" \r\t\n localFile \t\r\n ");
		final WindowsRemoteCommandResult expected = new WindowsRemoteCommandResult("stdout", "stderr", 1.0f, 0);
		final Path ticketCache = Paths.get("path");
		final List<AuthenticationEnum> authentications = singletonList(NTLM);

		// check arguments
		assertThrows(
			IllegalArgumentException.class,
			() ->
				execute(
					null,
					HTTPS,
					hostname,
					5986,
					username,
					password,
					workingDirectory,
					timeout,
					localFileToCopyList,
					ticketCache,
					authentications
				)
		);

		assertThrows(
			IllegalArgumentException.class,
			() ->
				execute(
					command,
					HTTPS,
					null,
					5986,
					username,
					password,
					workingDirectory,
					timeout,
					localFileToCopyList,
					ticketCache,
					authentications
				)
		);

		assertThrows(
			IllegalArgumentException.class,
			() ->
				execute(
					command,
					HTTPS,
					hostname,
					5986,
					null,
					password,
					workingDirectory,
					timeout,
					localFileToCopyList,
					ticketCache,
					authentications
				)
		);

		assertThrows(
			IllegalArgumentException.class,
			() ->
				execute(
					command,
					HTTPS,
					hostname,
					5986,
					username,
					null,
					workingDirectory,
					timeout,
					localFileToCopyList,
					ticketCache,
					authentications
				)
		);

		assertThrows(
			IllegalArgumentException.class,
			() ->
				execute(
					command,
					HTTPS,
					hostname,
					5986,
					username,
					password,
					workingDirectory,
					-1L,
					localFileToCopyList,
					ticketCache,
					authentications
				)
		);

		assertThrows(
			IllegalArgumentException.class,
			() ->
				execute(
					command,
					HTTPS,
					hostname,
					5986,
					username,
					password,
					workingDirectory,
					0L,
					localFileToCopyList,
					ticketCache,
					authentications
				)
		);

		// case localFileToCopyList null or empty
		try (
			final MockedStatic<WindowsRemoteProcessUtils> mockedWindowsRemoteProcessUtils = mockStatic(
				WindowsRemoteProcessUtils.class
			);
			final MockedStatic<WinRMService> mockedWinRMService = mockStatic(WinRMService.class)
		) {
			mockedWindowsRemoteProcessUtils.when(() -> getWindowsEncodingCharset(any(), anyLong())).thenReturn(UTF_8);

			final WinRMService winRMService = mock(WinRMService.class);

			mockedWinRMService
				.when(() -> WinRMService.createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
				.thenReturn(winRMService);

			doReturn(expected).when(winRMService).executeCommand(eq(command), isNull(), eq(UTF_8), anyLong());

			assertEquals(
				expected,
				execute(command, null, hostname, null, username, password, null, timeout, null, null, null)
			);

			assertEquals(
				expected,
				execute(command, null, hostname, null, username, password, null, timeout, emptyList(), null, null)
			);
		}

		// Case with localFileToCopyList
		try (
			final MockedStatic<WindowsRemoteProcessUtils> mockedWindowsRemoteProcessUtils = mockStatic(
				WindowsRemoteProcessUtils.class
			);
			final MockedStatic<SmbTempShare> mockedSmbTempShare = mockStatic(SmbTempShare.class)
		) {
			mockedWindowsRemoteProcessUtils.when(() -> getWindowsEncodingCharset(any(), anyLong())).thenReturn(UTF_8);

			mockedWindowsRemoteProcessUtils
				.when(() -> copyLocalFilesToShare(anyString(), anyList(), anyString(), anyString()))
				.thenReturn("launch remote/localFile");

			final SmbTempShare smbTempShare = mock(SmbTempShare.class);
			final WindowsRemoteExecutor windowsRemoteExecutor = mock(WindowsRemoteExecutor.class);

			mockedSmbTempShare
				.when(() -> SmbTempShare.createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
				.thenReturn(smbTempShare);

			doNothing().when(smbTempShare).checkConnectedFirst();
			doReturn(windowsRemoteExecutor).when(smbTempShare).getWindowsRemoteExecutor();
			doReturn("\\\\2001-db8--85b-3c51-f5ff-ffdb.ipv6-literal.net\\SEN_ShareFor_PC-TEST$")
				.when(smbTempShare)
				.getUncSharePath();
			doReturn("Windows\\Temp\\SEN_ShareFor_TEST$").when(smbTempShare).getRemotePath();

			doReturn(expected)
				.when(windowsRemoteExecutor)
				.executeCommand(anyString(), isNull(), any(Charset.class), anyLong());

			assertEquals(
				expected,
				execute(command, null, hostname, null, username, password, null, timeout, localFileToCopyList, null, null)
			);
		}
	}
}
