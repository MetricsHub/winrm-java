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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UtilsTest {

	@Test
	void sanitizesComputerNames() {
		// Real host names pass through unchanged
		assertEquals("MY-PC", Utils.sanitizeComputerName("MY-PC"));
		assertEquals("host.domain.example.net", Utils.sanitizeComputerName("host.domain.example.net"));
		assertEquals("host_01", Utils.sanitizeComputerName("host_01"));

		// The name may come from an unconstrained environment variable and ends up embedded in
		// remote shell commands: cmd metacharacters must never survive
		assertEquals("bad---del--q-c--", Utils.sanitizeComputerName("bad & del /q c:\\"));
		assertEquals("a-b-c--d-e-f-g", Utils.sanitizeComputerName("a\"b c^%d|e>f<g"));

		// Nothing safe left, or blank: fall back to a neutral name
		assertEquals("localhost", Utils.sanitizeComputerName("&&&"));
		assertEquals("localhost", Utils.sanitizeComputerName("   "));
		assertEquals("localhost", Utils.sanitizeComputerName("-.-"));

		// Overlong values are truncated
		assertTrue(Utils.sanitizeComputerName("x".repeat(200)).length() <= 64);
	}
}
