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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WqlQueryTest {

	@Test
	void gettersReturnUnmodifiableViews() throws Exception {
		final WqlQuery query = WqlQuery.newInstance("SELECT PropA, PropB.Sub1, PropB.Sub2 FROM Win32_Class");

		final List<String> selectedProperties = query.getSelectedProperties();
		assertEquals(List.of("propa", "propb.sub1", "propb.sub2"), selectedProperties);
		assertThrows(UnsupportedOperationException.class, () -> selectedProperties.set(0, "other"));

		// The map view is unmodifiable down to each subproperty set
		final Map<String, Set<String>> subPropertiesMap = query.getSubPropertiesMap();
		assertEquals(Set.of("sub1", "sub2"), subPropertiesMap.get("propb"));
		assertThrows(UnsupportedOperationException.class, () -> subPropertiesMap.remove("propa"));
		assertThrows(UnsupportedOperationException.class, () -> subPropertiesMap.get("propb").clear());

		// The failed mutation attempts did not alter the parsed query
		assertEquals(List.of("propa", "propb.sub1", "propb.sub2"), query.getSelectedProperties());
		assertEquals(Set.of("sub1", "sub2"), query.getSubPropertiesMap().get("propb"));
	}
}
