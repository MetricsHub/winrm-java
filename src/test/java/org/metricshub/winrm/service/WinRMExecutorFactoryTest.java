package org.metricshub.winrm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.light.LightWinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/** Verifies backend selection and the light backend's capability guards (no network required). */
class WinRMExecutorFactoryTest {

	private static WinRMEndpoint endpoint(final WinRMHttpProtocolEnum protocol) {
		return new WinRMEndpoint(protocol, "testhost", null, "user", "pwd".toCharArray(), null);
	}

	@AfterEach
	void clearBackend() {
		System.clearProperty(WinRMExecutorFactory.BACKEND_PROPERTY);
	}

	@Test
	void lightBackendSelectedForHttpNtlm() throws Exception {
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)
		) {
			assertInstanceOf(LightWinRMService.class, executor);
			assertEquals("testhost", executor.getHostname());
		}
	}

	@Test
	void defaultBackendIsCxf() throws Exception {
		// No backend property set -> the mature CXF backend. Building the client does not open a
		// connection (that happens on the first operation), so this stays offline.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)
		) {
			assertInstanceOf(WinRMService.class, executor);
		}
	}

	@Test
	void lightBackendRejectsHttps() {
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		assertThrows(
			WinRMException.class,
			() ->
				WinRMExecutorFactory.createInstance(
					endpoint(WinRMHttpProtocolEnum.HTTPS),
					30000L,
					null,
					List.of(AuthenticationEnum.NTLM)
				)
		);
	}

	@Test
	void lightBackendRejectsKerberosOnly() {
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		assertThrows(
			WinRMException.class,
			() ->
				WinRMExecutorFactory.createInstance(
					endpoint(WinRMHttpProtocolEnum.HTTP),
					30000L,
					null,
					List.of(AuthenticationEnum.KERBEROS)
				)
		);
	}
}
