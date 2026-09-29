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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WmiHelperTest {

	@Test
	void isValidWqlAcceptsSingleLineQueries() {
		assertTrue(WmiHelper.isValidWql("SELECT * FROM Win32_Service"));
		assertTrue(WmiHelper.isValidWql("SELECT Name FROM Win32_Service WHERE State = 'Running' AND StartMode = 'Auto'"));
	}

	@Test
	void isValidWqlAcceptsLineBreaksBeforeWhere() {
		assertTrue(WmiHelper.isValidWql("SELECT Name FROM Win32_Service\nWHERE State = 'Running'"));
	}

	@Test
	void isValidWqlAcceptsMultiLineWhereClause() {
		assertTrue(
			WmiHelper.isValidWql("SELECT Name FROM Win32_Service\nWHERE State = 'Running'\n  AND StartMode = 'Auto'\n")
		);
		assertTrue(
			WmiHelper.isValidWql(
				"SELECT Name, ProcessId\r\nFROM Win32_Service\r\nWHERE State = 'Running'\r\n  AND StartMode = 'Auto'"
			)
		);
	}

	@Test
	void isValidWqlRejectsNonSelectQueries() {
		assertFalse(WmiHelper.isValidWql("ASSOCIATORS OF {Win32_Service.Name='W32Time'}"));
		assertFalse(WmiHelper.isValidWql("REFERENCES OF {Win32_Service.Name='W32Time'}"));
		assertFalse(
			WmiHelper
				.isValidWql("SELECT * FROM __InstanceModificationEvent WITHIN 1\nWHERE TargetInstance ISA 'Win32_Service'")
		);
		assertFalse(WmiHelper.isValidWql("SELECT Name FROM Win32_Service\nSELECT Name FROM Win32_Process"));
		assertFalse(WmiHelper.isValidWql("SELECT Name FROM Win32_Service WHERE"));
	}
}
