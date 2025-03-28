package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2024 Metricshub
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;

public class WqlQuery {

	/**
	 * Pattern to parse a WQL query
	 * <ul>
	 * <li>group(1) = SELECT ... FROM
	 * <li>group(2) = Properties in the SELECT statement (but not '*')
	 * <li>group(3) = ASSOCIATORS OF { object ID }
	 * <li>group(4) = class in the FROM statement
	 * <li>group(5) = Rest of the WQL statement (WHERE, etc.)
	 */
	private static final Pattern WQL_PATTERN = Pattern.compile(
		"^\\s*(SELECT\\s+(?:\\*|([a-z0-9._]+(?:\\s*,\\s*[a-z0-9._]+)*))\\s+FROM\\s+)?(?:((?:ASSOCIATORS|REFERENCES)\\s+OF\\s+\\{.*\\})|([a-z0-9_]+))(\\s+WHERE\\s*+.+)?\\s*$",
		Pattern.CASE_INSENSITIVE | Pattern.DOTALL
	);

	private String wql;
	private List<String> selectedProperties;
	private Map<String, Set<String>> subPropertiesMap;
	private String cleanWql;

	private WqlQuery(
		String wql,
		List<String> selectedProperties,
		Map<String, Set<String>> subPropertiesMap,
		String cleanWql
	) {
		this.wql = wql;
		this.selectedProperties = selectedProperties;
		this.subPropertiesMap = subPropertiesMap;
		this.cleanWql = cleanWql;
	}

	/**
	 * Parses the specified WQL query and returns a new instance of WqlQuery
	 *
	 * Supported WQL syntaxes:
	 * <ul>
	 * <li>SELECT * FROM Class
	 * <li>SELECT PropA, PropB FROM Class
	 * <li>SELECT PropA, PropB FROM Class WHERE condition
	 * <li>ASSOCIATORS OF { objectId }
	 * <li>ASSOCIATORS OF { objectId } WHERE condition
	 * <li>SELECT * FROM ASSOCIATORS OF { objectId } WHERE condition
	 * <li>SELECT PropA, PropB FROM ASSOCIATORS OF { objectId } WHERE condition
	 * </ul>
	 * @param wql The WQL query to parse
	 * @return a new WqlQuery instance
	 * @throws WqlQuerySyntaxException when the specified WQL is invalid and cannot be parsed
	 * @throws IllegalArgumentException if wql is null
	 */
	public static WqlQuery newInstance(CharSequence wql) throws WqlQuerySyntaxException {
		Utils.checkNonNull(wql, "wql");

		Matcher wqlMatcher = WQL_PATTERN.matcher(wql);

		// No match: invalid WQL
		if (!wqlMatcher.find()) {
			throw new WqlQuerySyntaxException(wql.toString());
		}

		// Extract the different fragments of the parsed WQL
		String selectFragment = wqlMatcher.group(1);
		String propertiesFragment = wqlMatcher.group(2);
		String associatorsFragment = wqlMatcher.group(3);
		String classFragment = wqlMatcher.group(4);
		String restFragment = wqlMatcher.group(5);

		// If there is no `SELECT` and no `ASSOCIATORS OF`, it's no valid WQL
		if (selectFragment == null && associatorsFragment == null) {
			throw new WqlQuerySyntaxException(wql.toString());
		}

		List<String> properties = buildSelectedProperties(propertiesFragment);
		Map<String, Set<String>> subPropertiesMap = buildSupPropertiesMap(properties);
		String cleanWql = buildCleanWql(associatorsFragment, subPropertiesMap, classFragment, restFragment);

		return new WqlQuery(wql.toString(), properties, subPropertiesMap, cleanWql);
	}

	/**
	 * Note: All properties are converted to lower case
	 * @param propertiesFragment Comma-separated list of properties
	 * @return a cleaned-up array of the properties
	 */
	static List<String> buildSelectedProperties(String propertiesFragment) {
		if (Utils.isNotBlank(propertiesFragment)) {
			return Arrays.asList(propertiesFragment.trim().toLowerCase().split("\\s*,\\s*"));
		}
		return new ArrayList<>();
	}

	/**
	 * Build a Map of subproperties to retrieve inside properties
	 *
	 * Example:
	 *
	 * Input:
	 * <code>PropA, PropB.Sub1, PropB.Sub2</code>
	 *
	 * Output:
	 * <ul>
	 * <li>PropA => emptySet()
	 * <li>PropB => { "Sub1", "Sub2" }
	 * </ul>
	 *
	 * @param properties Selected properties (that may include subproperties)
	 * @return The map as described above
	 */
	static Map<String, Set<String>> buildSupPropertiesMap(final List<String> properties) {
		// Empty or null?
		if (properties == null || properties.isEmpty()) {
			return new HashMap<>();
		}

		Map<String, Set<String>> subPropertiesMap = new LinkedHashMap<>();
		properties
			.stream()
			.filter(Utils::isNotBlank)
			.forEachOrdered(property -> {
				// Split the property into fragments:
				// propA => ["propA"]
				// propA.subProp => ["propA", "subProp"]
				String[] propertyFragmentArray = property.toLowerCase().split("\\.", 2);
				String mainProperty = propertyFragmentArray[0];
				String subProperty = propertyFragmentArray.length == 2 ? propertyFragmentArray[1] : null;

				// Add this entry to the map
				subPropertiesMap.compute(
					mainProperty,
					(key, subPropertiesSet) -> {
						if (subPropertiesSet == null) {
							subPropertiesSet = new HashSet<>();
						}
						if (subProperty != null) {
							subPropertiesSet.add(subProperty);
						}
						return subPropertiesSet;
					}
				);
			});

		return subPropertiesMap;
	}

	/**
	 * Build a strict WQL query from the "dirty" one we have
	 *
	 * By <em>strict</em> we mean a syntax that can be executed by the WMI provider. <br>
	 * By <em>dirty</em> we mean the extra sugar-coated syntax we're allowing in Metricshub products,
	 * like subproperties, and <code>SELECT prop FROM ASSOCIATORS OF...</code>
	 *
	 * Examples:
	 * <ul>
	 * <li><code>SELECT PropA.Name FROM Win32_Class</code><br>
	 *  => <b>SELECT PropA FROM Win32_Class</b>
	 * <li><code>SELECT Temperature FROM ASSOCIATORS OF { Win32_Class.Id=1 }</code><br>
	 *  => <b>ASSOCIATORS OF { Win32_Class.Id=1 }</b>
	 * </ul>
	 * @param associatorsFragment The extracted ASSOCIATORS OF... fragment
	 * @param subPropertiesMap The map built with {@link WqlQuery#buildSupPropertiesMap(String[])}
	 * @param classFragment The extracted class fragment
	 * @param restFragment The rest (WHERE...)
	 * @return a clean and strict WQL statement
	 */
	static String buildCleanWql(
		String associatorsFragment,
		Map<String, Set<String>> subPropertiesMap,
		String classFragment,
		String restFragment
	) {
		String cleanWql;

		if (associatorsFragment == null) {
			if (subPropertiesMap.keySet().isEmpty()) {
				cleanWql = "SELECT * FROM " + classFragment;
			} else {
				cleanWql =
					String.format(
						"SELECT %s FROM %s",
						subPropertiesMap.keySet().stream().collect(Collectors.joining(",")),
						classFragment
					);
			}
		} else {
			cleanWql = associatorsFragment;
		}
		if (restFragment != null) {
			cleanWql = cleanWql + restFragment;
		}
		return cleanWql;
	}

	public List<String> getSelectedProperties() {
		return selectedProperties;
	}

	public Map<String, Set<String>> getSubPropertiesMap() {
		return subPropertiesMap;
	}

	public String getCleanWql() {
		return cleanWql;
	}

	@Override
	public String toString() {
		return wql;
	}
}
