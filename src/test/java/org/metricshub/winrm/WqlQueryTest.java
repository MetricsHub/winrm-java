package org.metricshub.winrm;

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
