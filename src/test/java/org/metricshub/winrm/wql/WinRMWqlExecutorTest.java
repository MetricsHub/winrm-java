package org.metricshub.winrm.wql;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2026 MetricsHub
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

import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.light.FakeWsmanResponses.enqueueEnumeration;
import static org.metricshub.winrm.light.FakeWsmanResponses.instance;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.wql.WinRMWqlExecutor.executeWql;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.light.FakeWsmanServer;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

class WinRMWqlExecutorTest {

	@Test
	void resultCollectionsAreDefensivelyCopiedAndUnmodifiable() {
		final List<String> headers = new ArrayList<>(asList("Name", "Path"));
		final List<List<String>> rows = new ArrayList<>();
		rows.add(new ArrayList<>(asList("C$", "C:\\")));

		final WinRMWqlExecutor result = new WinRMWqlExecutor(42L, headers, rows);

		// Mutating the source collections (including a retained inner row) after construction
		// must not affect the result
		headers.add("Extra");
		rows.get(0).set(0, "hacked");
		rows.add(new ArrayList<>());
		assertEquals(asList("Name", "Path"), result.getHeaders());
		assertEquals(singletonList(asList("C$", "C:\\")), result.getRows());

		// The returned collections are unmodifiable, down to each row
		assertThrows(UnsupportedOperationException.class, () -> result.getHeaders().add("x"));
		assertThrows(UnsupportedOperationException.class, () -> result.getRows().add(asList("x")));
		assertThrows(UnsupportedOperationException.class, () -> result.getRows().get(0).set(0, "x"));
	}

	@Test
	void testExecuteArgumentChecks() {
		final String wqlQuery = "Select Name,Path from Win32_Share";
		final String hostname = "host";
		final String username = "user";
		final char[] password = "pass".toCharArray();
		final long timeout = 30 * 1000L;
		final Path ticketCache = get("path");
		final List<AuthenticationEnum> authentications = singletonList(NTLM);

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
	}

	@Test
	void executesTheQueryThroughTheRealProtocolStack() throws Exception {
		final String wqlQuery = "Select Name,Path from Win32_Share";

		// End to end: executeWql -> WinRMExecutorFactory -> LightWinRMService -> WsmanClient ->
		// real NTLM handshake and message encryption against the in-process WSMan server.
		try (FakeWsmanServer server = new FakeWsmanServer("FAKE", "user", "s3cret-Passw0rd")) {
			enqueueEnumeration(
				server,
				instance("Win32_Share", "Name", "C$", "Path", "C:\\"),
				instance("Win32_Share", "Name", "SEN_ShareFor_PC$", "Path", "C:\\Windows\\Temp\\SEN_ShareFor_PC$")
			);

			final WinRMWqlExecutor actual = executeWql(
				null,
				"127.0.0.1",
				server.port(),
				"FAKE\\user",
				"s3cret-Passw0rd".toCharArray(),
				null,
				wqlQuery,
				30 * 1000L,
				null,
				null
			);

			// Headers keep the order of the WQL SELECT clause, and each row is mapped onto it
			assertEquals(asList("Name", "Path"), actual.getHeaders());
			assertEquals(
				asList(asList("C$", "C:\\"), asList("SEN_ShareFor_PC$", "C:\\Windows\\Temp\\SEN_ShareFor_PC$")),
				actual.getRows()
			);
			assertTrue(actual.getExecutionTime() >= 0);

			// The query the factory-created executor put on the wire is the caller's, verbatim
			final List<String> requests = server.decryptedRequests();
			assertEquals(1, requests.size(), () -> String.join("\n---\n", requests));
			assertTrue(requests.get(0).contains(wqlQuery), requests.get(0));
		}
	}
}
