package org.metricshub.winrm.shares;

import static java.nio.file.Paths.get;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.WindowsTempShare.getOrCreateShare;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.shares.SmbTempShare.createInstance;
import static org.metricshub.winrm.shares.SmbTempShare.createSmbClient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.SmbConfig.Builder;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.ShareRemoteDirectoryConsumer;
import org.metricshub.winrm.WindowsTempShare;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.mockito.MockedStatic;

class SmbTempShareTest {

	@SuppressWarnings("unchecked")
	@Test
	void testCreateInstance() throws Exception {
		final String hostname = "host";
		final String username = "user";
		final char[] password = "pwd".toCharArray();
		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(null, hostname, null, "domain\\" + username, password, null);
		final long timeout = 30 * 1000L;
		final Path ticketCache = get("path");
		final List<AuthenticationEnum> authentications = singletonList(NTLM);

		//check arguments
		assertThrows(IllegalArgumentException.class, () -> createInstance(null, timeout, ticketCache, authentications));

		assertThrows(
			IllegalArgumentException.class,
			() -> createInstance(winRMEndpoint, -1L, ticketCache, authentications)
		);

		assertThrows(IllegalArgumentException.class, () -> createInstance(winRMEndpoint, 0L, ticketCache, authentications));

		try (
			final MockedStatic<WinRMService> mockedWinRMService = mockStatic(WinRMService.class);
			final MockedStatic<SmbTempShare> mockedSmbTempShare = mockStatic(SmbTempShare.class);
			final MockedStatic<WindowsTempShare> mockedWindowsTempShare = mockStatic(WindowsTempShare.class);
			final MockedStatic<SmbConfig> mockedSmbConfig = mockStatic(SmbConfig.class)
		) {
			final WinRMService winRMService = mock(WinRMService.class);
			mockedWinRMService
				.when(() -> WinRMService.createInstance(winRMEndpoint, timeout, null, null))
				.thenReturn(winRMService);

			final WindowsTempShare windowsTempShare = mock(WindowsTempShare.class);
			mockedWindowsTempShare
				.when(() -> getOrCreateShare(eq(winRMService), anyLong(), any(ShareRemoteDirectoryConsumer.class)))
				.thenReturn(windowsTempShare);
			doReturn("\\\\2001-db8--85b-3c51-f5ff-ffdb.ipv6-literal.net\\SEN_ShareFor_PC-TEST$")
				.when(windowsTempShare)
				.getUncSharePath();
			doReturn("Windows\\Temp\\SEN_ShareFor_TEST$").when(windowsTempShare).getRemotePath();
			doReturn("SEN_ShareFor_PC-TEST$").when(windowsTempShare).getShareName();

			final Builder smbConfigBuilder = mock(Builder.class);
			mockedSmbConfig.when(SmbConfig::builder).thenReturn(smbConfigBuilder);

			doReturn(smbConfigBuilder).when(smbConfigBuilder).withSecurityProvider(any(BCSecurityProvider.class));

			doReturn(smbConfigBuilder).when(smbConfigBuilder).withTimeout(anyLong(), eq(SECONDS));

			final SmbConfig smbConfig = mock(SmbConfig.class);
			doReturn(smbConfig).when(smbConfigBuilder).build();

			final SMBClient smbClient = mock(SMBClient.class);
			mockedSmbTempShare.when(() -> createSmbClient(smbConfig)).thenReturn(smbClient);

			final Connection connection = mock(Connection.class);
			doReturn(connection).when(smbClient).connect(anyString());

			final Session session = mock(Session.class);
			doReturn(session).when(connection).authenticate(any(AuthenticationContext.class));

			final DiskShare diskShare = mock(DiskShare.class);
			doReturn(diskShare).when(session).connectShare(anyString());

			mockedSmbTempShare
				.when(() -> createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
				.thenCallRealMethod();

			final SmbTempShare smbTempShare1 = createInstance(winRMEndpoint, timeout, null, null);
			assertNotNull(smbTempShare1);
			assertEquals(1, smbTempShare1.getUseCount());
			assertEquals(winRMService, smbTempShare1.getWindowsRemoteExecutor());
			assertTrue(smbTempShare1.isConnected());

			final SmbTempShare smbTempShare2 = createInstance(winRMEndpoint, timeout, null, null);
			assertNotNull(smbTempShare2);
			assertEquals(2, smbTempShare1.getUseCount());
			assertEquals(2, smbTempShare2.getUseCount());
			assertEquals(winRMService, smbTempShare2.getWindowsRemoteExecutor());
			assertTrue(smbTempShare1.isConnected());
			assertTrue(smbTempShare2.isConnected());

			smbTempShare1.close();
			assertTrue(smbTempShare1.isConnected());
			assertTrue(smbTempShare2.isConnected());
			assertEquals(1, smbTempShare1.getUseCount());
			assertEquals(1, smbTempShare2.getUseCount());

			smbTempShare2.close();
			assertFalse(smbTempShare1.isConnected());
			assertFalse(smbTempShare2.isConnected());
			assertEquals(0, smbTempShare1.getUseCount());
			assertEquals(0, smbTempShare2.getUseCount());
		}
	}
}
