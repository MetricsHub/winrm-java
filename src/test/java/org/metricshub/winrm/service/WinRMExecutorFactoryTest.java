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
	void defaultBackendIsLight() throws Exception {
		// No backend property set -> the dependency-free light backend. Building the client does not
		// open a connection (that happens on the first operation), so this stays offline.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)
		) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void cxfBackendSelectedViaProperty() throws Exception {
		// The CXF backend stays reachable via the property while light matures. Building the client
		// does not open a connection, so this stays offline.
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "cxf");
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
	void defaultBackendAcceptsHttps() throws Exception {
		// Light now supports HTTPS (TLS + plaintext SOAP), so the default backend accepts it. Building
		// the client does not open a connection (or a TLS handshake), so this stays offline.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTPS),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)
		) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void lightBackendAcceptsHttps() throws Exception {
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTPS),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)
		) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
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

	@Test
	void lightBackendRejectsMixedKerberosNtlm() {
		// A fallback list like [KERBEROS, NTLM] must be rejected, not silently downgraded to NTLM: the
		// light backend cannot honour the preferred Kerberos scheme, so it points at the CXF escape hatch.
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		assertThrows(
			WinRMException.class,
			() ->
				WinRMExecutorFactory.createInstance(
					endpoint(WinRMHttpProtocolEnum.HTTP),
					30000L,
					null,
					List.of(AuthenticationEnum.KERBEROS, AuthenticationEnum.NTLM)
				)
		);
	}

	@Test
	void unsupportedBackendValueRejected() {
		// A typo or unknown value must fail loudly instead of silently falling through to a backend the
		// operator did not request.
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "cxff");
		assertThrows(
			WinRMException.class,
			() ->
				WinRMExecutorFactory.createInstance(
					endpoint(WinRMHttpProtocolEnum.HTTP),
					30000L,
					null,
					List.of(AuthenticationEnum.NTLM)
				)
		);
	}

	@Test
	void closedLightExecutorRejectsOperations() throws Exception {
		// close() must release the executor for good: a later operation is rejected, not silently served
		// by a fresh reconnect/handshake.
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
			endpoint(WinRMHttpProtocolEnum.HTTP),
			30000L,
			null,
			List.of(AuthenticationEnum.NTLM)
		);
		executor.close();
		assertThrows(
			IllegalStateException.class,
			() -> executor.executeWql("SELECT Name FROM Win32_OperatingSystem", 30000L)
		);
	}
}
