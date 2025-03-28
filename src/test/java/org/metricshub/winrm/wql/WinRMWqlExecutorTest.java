package org.metricshub.winrm.wql;

import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.service.WinRMService.createInstance;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.wql.WinRMWqlExecutor.executeWql;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.mockito.MockedStatic;

class WinRMWqlExecutorTest {

	@Test
	void testExecute() throws Exception {
		final String wqlQuery = "Select Name,Path from Win32_Share";
		final String hostname = "host";
		final String username = "user";
		final char[] password = "pass".toCharArray();
		final long timeout = 30 * 1000L;
		final Path ticketCache = get("path");
		final List<AuthenticationEnum> authentications = singletonList(NTLM);

		// check arguments
		assertThrows(
			IllegalArgumentException.class,
			() -> executeWql(HTTPS, null, 5986, username, password, null, wqlQuery, timeout, ticketCache, authentications)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> executeWql(HTTPS, hostname, 5986, null, password, null, wqlQuery, timeout, ticketCache, authentications)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> executeWql(HTTPS, hostname, 5986, username, null, null, wqlQuery, timeout, ticketCache, authentications)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> executeWql(HTTPS, hostname, 5986, username, password, null, null, timeout, ticketCache, authentications)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> executeWql(HTTPS, hostname, 5986, username, password, null, wqlQuery, -1L, ticketCache, authentications)
		);

		assertThrows(
			IllegalArgumentException.class,
			() -> executeWql(HTTPS, hostname, 5986, username, password, null, wqlQuery, 0L, ticketCache, authentications)
		);

		try (final MockedStatic<WinRMService> mockedWinRMService = mockStatic(WinRMService.class)) {
			final WinRMService winRMService = mock(WinRMService.class);

			final List<Map<String, Object>> result = new ArrayList<>();
			{
				final Map<String, Object> row = new HashMap<>();
				row.put("Name", "C$");
				row.put("Path", "C:\\");
				result.add(row);
			}
			{
				final Map<String, Object> row = new HashMap<>();
				row.put("Name", "SEN_ShareFor_PC$");
				row.put("Path", "C:\\Windows\\Temp\\SEN_ShareFor_PC$");
				result.add(row);
			}

			mockedWinRMService
				.when(() -> createInstance(any(WinRMEndpoint.class), anyLong(), isNull(), isNull()))
				.thenReturn(winRMService);

			doReturn(result).when(winRMService).executeWql(eq(wqlQuery), anyLong());

			final WinRMWqlExecutor actual = executeWql(
				null,
				hostname,
				null,
				username,
				password,
				null,
				wqlQuery,
				timeout,
				null,
				null
			);

			assertEquals(asList("Name", "Path"), actual.getHeaders());

			final List<List<String>> rows = asList(
				asList("C$", "C:\\"),
				asList("SEN_ShareFor_PC$", "C:\\Windows\\Temp\\SEN_ShareFor_PC$")
			);

			assertEquals(rows, actual.getRows());
		}
	}
}
