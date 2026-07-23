package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Verifies the light backend's TLS posture: validate by default, insecure only when opted in. */
class LightTlsTest {

	@AfterEach
	void clearInsecure() {
		System.clearProperty(LightTls.INSECURE_PROPERTY);
	}

	@Test
	void validatesByDefault() {
		assertFalse(LightTls.isInsecure());
		assertTrue(LightTls.verifyHostname());
		assertNotNull(LightTls.socketFactory());
	}

	@Test
	void insecureWhenOptedIn() {
		System.setProperty(LightTls.INSECURE_PROPERTY, "true");
		assertTrue(LightTls.isInsecure());
		assertFalse(LightTls.verifyHostname());
		assertNotNull(LightTls.socketFactory());
	}
}
