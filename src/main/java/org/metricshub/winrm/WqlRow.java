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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of a WQL query result: an immutable, ordered view of the instance properties.
 * Property lookup is case-insensitive, matching WMI semantics.
 */
public final class WqlRow {

	private final Map<String, Object> values;

	/**
	 * Create a row over the given property map (copied defensively, order preserved).
	 *
	 * @param values the property name/value map
	 */
	WqlRow(final Map<String, Object> values) {
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	/**
	 * Get the value of a property. The lookup first tries the exact property name, then falls
	 * back to a case-insensitive match — WMI property names are case-insensitive.
	 *
	 * @param property the property name
	 * @return the property value, or {@code null} when the property is absent or null
	 */
	public Object get(final String property) {
		Utils.checkNonNull(property, "property");
		if (values.containsKey(property)) {
			return values.get(property);
		}
		for (final Map.Entry<String, Object> entry : values.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(property)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Get the value of a property as a string. Same lookup semantics as {@link #get(String)}.
	 *
	 * @param property the property name
	 * @return the property value as a string, or {@code null} when the property is absent or null
	 */
	public String string(final String property) {
		final Object value = get(property);
		return value != null ? value.toString() : null;
	}

	/**
	 * Get all properties of the row, in the order the server returned them.
	 *
	 * @return an unmodifiable ordered map of property names to values
	 */
	public Map<String, Object> asMap() {
		return values;
	}

	@Override
	public String toString() {
		return values.toString();
	}
}
