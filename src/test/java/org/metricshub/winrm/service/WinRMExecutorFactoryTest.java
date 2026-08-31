package org.metricshub.winrm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.light.LightWinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/** Verifies executor creation and the client's capability guards (no network required). */
class WinRMExecutorFactoryTest {

	private static WinRMEndpoint endpoint(final WinRMHttpProtocolEnum protocol) {
		return new WinRMEndpoint(protocol, "testhost", null, "user", "pwd".toCharArray(), null);
	}

	@Test
	void createsLightExecutorOverHttp() throws Exception {
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
			assertEquals("testhost", executor.getHostname());
		}
	}

	@Test
	void createsLightExecutorOverHttps() throws Exception {
		// The client supports HTTPS (TLS + plaintext SOAP). Building it does not open a connection
		// (or a TLS handshake), so this stays offline.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTPS),
				30000L,
				null,
				List.of(AuthenticationEnum.NTLM)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void kerberosOnlyOverHttpRejected() {
		// Kerberos requires HTTPS (no message encryption over plain HTTP, matching CXF) and there is no
		// other scheme to fall back to, so a Kerberos-only request over HTTP is rejected.
		assertThrows(
			WinRMException.class,
			() -> WinRMExecutorFactory.createInstance(
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
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.KERBEROS, AuthenticationEnum.NTLM)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void kerberosOverHttpsAccepted() throws Exception {
		// Kerberos is supported over HTTPS; the login/handshake happen on the first operation, so
		// constructing the executor stays offline.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTPS),
				30000L,
				null,
				List.of(AuthenticationEnum.KERBEROS)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void basicAcceptedOverHttpAndHttps() throws Exception {
		// Basic is stateless and rides the Authorization header, so it is supported over both
		// transports. Constructing the executor opens no connection, so this stays offline.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.BASIC)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTPS),
				30000L,
				null,
				List.of(AuthenticationEnum.BASIC)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void basicFallsBackWithOtherSchemesOverHttp() throws Exception {
		// Ordered fallback: [BASIC, NTLM] over HTTP is a valid candidate list (both are supported
		// over HTTP), so it constructs successfully.
		try (
			final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
				endpoint(WinRMHttpProtocolEnum.HTTP),
				30000L,
				null,
				List.of(AuthenticationEnum.BASIC, AuthenticationEnum.NTLM)
			)) {
			assertInstanceOf(LightWinRMService.class, executor);
		}
	}

	@Test
	void closedLightExecutorRejectsOperations() throws Exception {
		// close() must release the executor for good: a later operation is rejected, not silently served
		// by a fresh reconnect/handshake.
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
