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
	void kerberosOnlyOverHttpRejected() {
		// Kerberos requires HTTPS (no message encryption over plain HTTP, matching CXF) and there is no
		// other scheme to fall back to, so a Kerberos-only request over HTTP is rejected.
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
	void mixedKerberosNtlmFallsBackToNtlmOverHttp() throws Exception {
		// Ordered fallback: [KERBEROS, NTLM] over HTTP cannot use Kerberos (HTTPS-only), so it falls back
		// to NTLM and constructs successfully. Building the client opens no connection, so this is offline.
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.KERBEROS, AuthenticationEnum.NTLM)
			)
		) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void kerberosOverHttpsAccepted() throws Exception {
		// Kerberos is supported over HTTPS; the login/handshake happen on the first operation, so
		// constructing the executor stays offline.
		System.setProperty(WinRMExecutorFactory.BACKEND_PROPERTY, "light");
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTPS),
				30000L,
				null,
				List.of(AuthenticationEnum.KERBEROS)
			)
		) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
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
		final IllegalStateException e = assertThrows(
			IllegalStateException.class,
			() -> executor.executeWql("SELECT Name FROM Win32_OperatingSystem", 30000L)
		);
		// Same message as the CXF backend (part of the exception-surface parity, issue #106).
		assertEquals("This instance has been closed and a new one must be created.", e.getMessage());
	}
}
