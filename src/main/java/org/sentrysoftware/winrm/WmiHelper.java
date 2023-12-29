package org.sentrysoftware.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 Sentry Software
 * ჻჻჻჻჻჻
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * ╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱
 */

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import org.sentrysoftware.winrm.exceptions.WqlQuerySyntaxException;

public abstract class WmiHelper {


	/**
	 * Private constructor, as this class cannot be instantiated (it's pure static)
	 */
	private WmiHelper() {}

	public static final String DEFAULT_NAMESPACE = "ROOT\\CIMV2";

	/**
	 * Pattern to detect a simple WQL select query.
	 */
	private static final Pattern WQL_SIMPLE_SELECT_PATTERN = Pattern.compile(
			"^\\s*SELECT\\s+(\\*|(?!SELECT|FROM|WHERE)[a-z0-9._]+|((?!SELECT|FROM|WHERE)[a-z0-9._]+\\s*,\\s*)+((?!SELECT|FROM|WHERE)[a-z0-9._]+))\\s+FROM\\s+((?!WHERE|FROM)\\w+)\\s*(WHERE\\s+.*)?$",
			Pattern.CASE_INSENSITIVE
	);

	/**
	 * Check if the WQL Query respect the simple syntax in the form of
	 * <code>Select * from (where)</code> or <code>Select a,b,c from (where)</code>
	 * is valid.
	 *
	 * @param wqlQuery
	 * @return whether specified WQL query's syntax is valid or not
	 */
	public static boolean isValidWql(final String wqlQuery) {
		return WQL_SIMPLE_SELECT_PATTERN.matcher(wqlQuery).find();
	}

	/**
	 * The "network resource" is either just the namespace (for localhost), or \\hostname\\namespace.
	 *
	 * @param hostname Host to connect to.
	 * @param namespace The Namespace.
	 * @return resource
	 */
	public static String createNetworkResource(
			final String hostname,
			final String namespace) {
		Utils.checkNonNull(namespace, "namespace");
		return hostname == null || hostname.isEmpty() ?
				namespace :
				String.format("\\\\%s\\%s", hostname, namespace);
	}

	/**
	 * @param networkResource Network resource string to test
	 * @return whether specified networkResource is local or not
	 */
	public static boolean isLocalNetworkResource(final String networkResource) {
		Utils.checkNonNull(networkResource, "networkResource");
		return !networkResource.startsWith("\\\\") ||
				networkResource.startsWith("\\\\localhost\\") ||
				networkResource.startsWith("\\\\127.0.0.1\\") ||
				networkResource.startsWith("\\\\0:0:0:0:0:0:0:1\\") ||
				networkResource.startsWith("\\\\::1\\") ||
				networkResource.startsWith("\\\\0000:0000:0000:0000:0000:0000:0000:0001\\") ||
				networkResource.toLowerCase().startsWith("\\\\" + Utils.getComputerName().toLowerCase() + "\\");
	}

	/**
	 * Extract the exact name of the properties from a WMI result.
	 * 
	 * The interest is to retrieve the exact case of the property names, instead of
	 * the lowercase that we have at this stage.
	 * 
	 * @param resultRows The result whose first row will be parsed
	 * @param wql The WQL query that was used (so we make sure to return the properties in the same order)
	 * @return a list of property names
	 * @throws IllegalStateException if the specified WQL is invalid
	 */
	public static List<String> extractPropertiesFromResult(final List<Map<String, Object>> resultRows, final String wql) {

		try {
			return extractPropertiesFromResult(resultRows, WqlQuery.newInstance(wql));
		} catch (WqlQuerySyntaxException e) {
			throw new IllegalStateException(e);
		}

	}

	/**
	 * Extract the exact name of the properties from a WMI result.
	 * 
	 * The interest is to retrieve the exact case of the property names, instead of
	 * the lowercase that we have at this stage.
	 * 
	 * Note: The exact case cannot be retrieved if result is empty, in which case all
	 * names are reported in lower case
	 * 
	 * @param resultRows The result whose first row will be parsed
	 * @param wqlQuery The WQL query that was used (so we make sure to return the properties in the same order)
	 * @return a list of property names
	 */
	public static List<String> extractPropertiesFromResult(final List<Map<String, Object>> resultRows, final WqlQuery wqlQuery) {

		// If resultRows is empty, we won't be able to retrieve the actual property names
		// with the correct case. So, we simply return the list of specified properties in the
		// WQL query
		if (resultRows.isEmpty()) {
			return wqlQuery.getSelectedProperties();
		}

		// Extract the actual property names
		final String[] resultPropertyArray = resultRows.get(0).keySet().toArray(new String[0]);

		// First case: we don't have any specified properties in the WQL Query, so we just
		// return the properties from the result set in alphabetical order
		if (wqlQuery.getSelectedProperties().isEmpty()) {
			Arrays.sort(resultPropertyArray, String.CASE_INSENSITIVE_ORDER);
			return Arrays.asList(resultPropertyArray);
		}

		// Create a new list based on queryPropertyArray (with its order), but with the values
		// from resultPropertyArray
		final List<String> queryProperties = wqlQuery.getSelectedProperties();
		final Map<String, String> resultProperties = Arrays.asList(resultPropertyArray).stream()
				.collect(Collectors.toMap(String::toLowerCase, property -> property));
		return queryProperties.stream()
				.map(property -> resultProperties.getOrDefault(property.toLowerCase(), property))
				.collect(Collectors.toList());

	}

}
