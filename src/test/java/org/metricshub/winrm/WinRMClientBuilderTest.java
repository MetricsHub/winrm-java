package org.metricshub.winrm;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.metricshub.winrm.exceptions.WinRMClientException;

/**
 * Validation and defaults of {@link WinRMClient.Builder}, the per-operation request builders,
 * and the result value objects — no network involved: {@code build()} does not connect.
 */
class WinRMClientBuilderTest {

	private static WinRMClient.Builder validBuilder() {
		return WinRMClient.builder("host").credentials("DOMAIN\\user", "secret".toCharArray());
	}

	@Test
	void hostnameIsRequired() {
		assertThrows(IllegalArgumentException.class, () -> WinRMClient.builder(null));
		assertThrows(IllegalArgumentException.class, () -> WinRMClient.builder("  "));
	}

	@Test
	void credentialsAreRequired() {
		final WinRMClient.Builder builder = WinRMClient.builder("host");
		assertThrows(IllegalStateException.class, builder::build);
		assertThrows(IllegalArgumentException.class, () -> builder.credentials(null, "x".toCharArray()));
		assertThrows(IllegalArgumentException.class, () -> builder.credentials("user", null));
	}

	@Test
	void incompleteDomainQualifiedUsernamesAreRejected() {
		final char[] password = "x".toCharArray();
		final WinRMClient.Builder builder = WinRMClient.builder("host");
		// A trailing or leading backslash means an empty user or domain part.
		assertThrows(IllegalArgumentException.class, () -> builder.credentials("DOMAIN\\", password));
		assertThrows(IllegalArgumentException.class, () -> builder.credentials("\\user", password));
		assertThrows(IllegalArgumentException.class, () -> builder.credentials("  ", password));
		// The valid forms still pass.
		builder.credentials("user", password);
		builder.credentials("DOMAIN\\user", password);
	}

	@Test
	void portMustBeValid() {
		assertThrows(IllegalArgumentException.class, () -> validBuilder().port(0));
		assertThrows(IllegalArgumentException.class, () -> validBuilder().port(65536));
	}

	@Test
	void timeoutMustBeAtLeastOneMillisecond() {
		assertThrows(IllegalArgumentException.class, () -> validBuilder().timeout(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> validBuilder().timeout(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class, () -> validBuilder().timeout(null));
		// A positive sub-millisecond duration truncates to 0 ms and must be rejected, not silently dropped.
		assertThrows(IllegalArgumentException.class, () -> validBuilder().timeout(Duration.ofNanos(1)));
	}

	@Test
	void retriesMustBeNonNegativeWithANonNegativeDelay() {
		assertThrows(IllegalArgumentException.class, () -> validBuilder().retries(-1, Duration.ofSeconds(5)));
		assertThrows(IllegalArgumentException.class, () -> validBuilder().retries(1, null));
		assertThrows(IllegalArgumentException.class, () -> validBuilder().retries(1, Duration.ofSeconds(-1)));
		// 0 retries (the default) and a zero delay are both valid: build() must accept them.
		try (WinRMClient client = validBuilder().retries(0, Duration.ZERO).build()) {
			assertEquals("host", client.hostname());
		}
		try (WinRMClient client = validBuilder().retries(2, Duration.ofSeconds(5)).build()) {
			assertEquals("host", client.hostname());
		}
	}

	@Test
	void authenticationMustNotBeEmpty() {
		assertThrows(IllegalArgumentException.class, () -> validBuilder().authentication());
		assertThrows(IllegalArgumentException.class, () -> validBuilder().authentication((AuthScheme) null));
	}

	@Test
	void sslContextAndTrustAllAreMutuallyExclusive() throws NoSuchAlgorithmException {
		final WinRMClient.Builder builder = validBuilder()
			.https()
			.sslContext(SSLContext.getDefault())
			.trustAllCertificates();
		assertThrows(IllegalStateException.class, builder::build);
	}

	@Test
	void kerberosOverHttpIsRejectedAtBuildTime() {
		final WinRMClient.Builder builder = validBuilder().authentication(AuthScheme.KERBEROS);
		final WinRMClientException e = assertThrows(WinRMClientException.class, builder::build);
		assertTrue(e.getMessage().contains("HTTPS"), e.getMessage());
	}

	@Test
	void buildSucceedsWithoutConnecting() {
		// The fluent one-liner shape: build() must not reach out to the (nonexistent) host.
		try (WinRMClient client = validBuilder().https().port(5987).namespace("root\\custom").build()) {
			assertEquals("host", client.hostname());
		}
	}

	@Test
	void wqlRequestValidatesItsOptions() {
		try (WinRMClient client = validBuilder().build()) {
			assertThrows(IllegalArgumentException.class, () -> client.wql("  "));
			final WqlRequest request = client.wql("SELECT Name FROM Win32_Service");
			assertThrows(IllegalArgumentException.class, () -> request.pageSize(0));
			assertThrows(IllegalArgumentException.class, () -> request.namespace(" "));
			assertThrows(IllegalArgumentException.class, () -> request.timeout(Duration.ZERO));
			assertThrows(IllegalArgumentException.class, () -> request.pullTimeout(Duration.ofSeconds(-3)));
			assertThrows(IllegalArgumentException.class, () -> request.pullTimeout(Duration.ofNanos(500)));
		}
	}

	@Test
	void commandRequestValidatesItsOptions() {
		try (WinRMClient client = validBuilder().build()) {
			assertThrows(IllegalArgumentException.class, () -> client.command(" "));
			final CommandRequest request = client.command("ipconfig");
			assertThrows(IllegalArgumentException.class, () -> request.workingDirectory(" "));
			assertThrows(IllegalArgumentException.class, () -> request.environment(" ", "value"));
			assertThrows(IllegalArgumentException.class, () -> request.environment("NAME", null));
			assertThrows(IllegalArgumentException.class, () -> request.timeout(Duration.ZERO));
			assertThrows(IllegalArgumentException.class, () -> request.charset(null));
			assertThrows(IllegalArgumentException.class, () -> request.upload((java.nio.file.Path) null));
		}
	}

	@Test
	void wqlRowLooksUpPropertiesCaseInsensitively() {
		final Map<String, Object> values = new LinkedHashMap<>();
		values.put("Name", "Spooler");
		values.put("State", null);
		final WqlRow row = new WqlRow(values);

		assertEquals("Spooler", row.get("Name"));
		assertEquals("Spooler", row.string("NAME"));
		assertNull(row.string("State"));
		assertNull(row.get("NoSuchProperty"));
		assertThrows(UnsupportedOperationException.class, () -> row.asMap().put("x", "y"));
		assertEquals("[Name, State]", row.asMap().keySet().toString());
	}
}
