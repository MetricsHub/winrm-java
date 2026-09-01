package org.metricshub.winrm.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.metricshub.winrm.Utils.EMPTY;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTP;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.service.WinRMEndpoint.buildEndpointUrl;
import static org.metricshub.winrm.service.WinRMEndpoint.buildNamespace;
import static org.metricshub.winrm.service.WinRMEndpoint.getEndpointPort;

import org.junit.jupiter.api.Test;

class WinRMEndpointTest {

	private static final String HOSTNAME = "host";
	private static final int PORT = 443;
	private static final String USER = "user";
	private static final char[] PASSWORD = { 'p', 'a', 's', 's' };

	@Test
	void testWinRMEndpoint() {
		// check arguments
		assertThrows(IllegalArgumentException.class, () -> new WinRMEndpoint(HTTP, null, 5985, USER, PASSWORD, null));

		assertThrows(IllegalArgumentException.class, () -> new WinRMEndpoint(HTTP, HOSTNAME, 5985, null, PASSWORD, null));

		assertThrows(IllegalArgumentException.class, () -> new WinRMEndpoint(HTTP, HOSTNAME, 5985, USER, null, null));

		// Case user name in form of domain\\user
		{
			final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(
				null,
				" \t\r\n host \t\r\n ",
				null,
				" \t\r\n domain \t\r\n \\ \t\r\n user \t\r\n ",
				PASSWORD,
				null
			);

			assertEquals(HOSTNAME, winRMEndpoint.getHostname());
			assertEquals("http://host:5985/wsman", winRMEndpoint.getEndpoint());
			assertEquals("domain", winRMEndpoint.getDomain());
			assertEquals(USER, winRMEndpoint.getUsername());
			// The raw account is whitespace-stripped too, so a protocol that uses it (Basic)
			// sends the same normalized account as the domain/username pair above.
			assertEquals("domain\\user", winRMEndpoint.getRawUsername());
			assertArrayEquals(PASSWORD, winRMEndpoint.getPassword());
			assertEquals("ROOT/CIMV2", winRMEndpoint.getNamespace());
			assertEquals(HTTP, winRMEndpoint.getProtocol());
		}
		{
			final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(HTTPS, HOSTNAME, PORT, "domain\\user", PASSWORD, "root");

			assertEquals(HOSTNAME, winRMEndpoint.getHostname());
			assertEquals("https://host:443/wsman", winRMEndpoint.getEndpoint());
			assertEquals("domain", winRMEndpoint.getDomain());
			assertEquals(USER, winRMEndpoint.getUsername());
			assertEquals("root", winRMEndpoint.getNamespace());
			assertArrayEquals(PASSWORD, winRMEndpoint.getPassword());
			assertEquals(HTTPS, winRMEndpoint.getProtocol());
		}

		// Case user name without domain
		{
			final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(null, HOSTNAME, null, USER, PASSWORD, "root/cimv2");

			assertEquals(HOSTNAME, winRMEndpoint.getHostname());
			assertEquals("http://host:5985/wsman", winRMEndpoint.getEndpoint());
			assertNull(winRMEndpoint.getDomain());
			assertEquals(USER, winRMEndpoint.getUsername());
			assertEquals("root/cimv2", winRMEndpoint.getNamespace());
			assertArrayEquals(PASSWORD, winRMEndpoint.getPassword());
			assertEquals(HTTP, winRMEndpoint.getProtocol());
		}
		{
			final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(HTTP, HOSTNAME, PORT, USER, PASSWORD, "SRV\\Microsoft");

			assertEquals(HOSTNAME, winRMEndpoint.getHostname());
			assertEquals("http://host:443/wsman", winRMEndpoint.getEndpoint());
			assertNull(winRMEndpoint.getDomain());
			assertEquals(USER, winRMEndpoint.getUsername());
			assertEquals("SRV/Microsoft", winRMEndpoint.getNamespace());
			assertArrayEquals(PASSWORD, winRMEndpoint.getPassword());
			assertEquals(HTTP, winRMEndpoint.getProtocol());
		}
	}

	@Test
	void testBuildEndpointUrl() {
		assertEquals("HTTP://host:5985", buildEndpointUrl(HTTP, HOSTNAME, null));
		assertEquals("HTTPS://host:5986", buildEndpointUrl(HTTPS, HOSTNAME, null));
		assertEquals("HTTP://host:443", buildEndpointUrl(HTTP, HOSTNAME, PORT));
		assertEquals("HTTPS://host:443", buildEndpointUrl(HTTPS, HOSTNAME, PORT));
	}

	@Test
	void testGetEndPointPort() {
		// case port provided
		assertEquals(PORT, getEndpointPort(null, PORT));
		assertEquals(PORT, getEndpointPort(HTTPS, PORT));
		assertEquals(PORT, getEndpointPort(HTTP, PORT));

		assertEquals(5985, getEndpointPort(null, null));
		assertEquals(5985, getEndpointPort(HTTP, null));
		assertEquals(5986, getEndpointPort(HTTPS, null));
	}

	@Test
	void testGetPort() {
		assertEquals(5985, new WinRMEndpoint(HTTP, HOSTNAME, null, USER, PASSWORD, null).getPort());
		assertEquals(5986, new WinRMEndpoint(HTTPS, HOSTNAME, null, USER, PASSWORD, null).getPort());
		assertEquals(PORT, new WinRMEndpoint(HTTP, HOSTNAME, PORT, USER, PASSWORD, null).getPort());
		assertEquals(PORT, new WinRMEndpoint(HTTPS, HOSTNAME, PORT, USER, PASSWORD, null).getPort());
	}

	@Test
	void testUnderscoreHostnameEndpoint() {
		// Regression guard for the light backend default: WinRMEndpoint accepts NetBIOS-style names with
		// underscores and exposes a usable hostname/port, whereas java.net.URI cannot classify such a host
		// (URI.create(endpoint).getHost() is null). The light backend must rely on these accessors.
		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(HTTP, "server_name", 5999, USER, PASSWORD, null);
		assertEquals("server_name", winRMEndpoint.getHostname());
		assertEquals(5999, winRMEndpoint.getPort());
		assertNull(java.net.URI.create(winRMEndpoint.getEndpoint()).getHost());
	}

	@Test
	void testBuildNamespace() {
		assertEquals("ROOT/CIMV2", buildNamespace(null));
		assertEquals("ROOT/CIMV2", buildNamespace(EMPTY));
		assertEquals("ROOT/CIMV2", buildNamespace(" \t\r\n "));

		// case namespace provided
		assertEquals("root", buildNamespace("root"));
		assertEquals("root/cimv2", buildNamespace(" \t\r\n root \t\r\n / \t\r\n cimv2 \t\r\n "));
		assertEquals("Srv/StandardCimv2", buildNamespace("Srv\\StandardCimv2"));
	}
}
