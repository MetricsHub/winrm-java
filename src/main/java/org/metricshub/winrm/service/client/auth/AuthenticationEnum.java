package org.metricshub.winrm.service.client.auth;

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

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum AuthenticationEnum {
	NTLM,
	KERBEROS;

	private static final Map<String, AuthenticationEnum> VALUES_OF = Stream
		.of(values())
		.collect(Collectors.toMap(AuthenticationEnum::name, Function.identity()));

	/**
	 * Get the value of the enum Name regardless the cardinality.
	 *
	 * @param name The enum value name
	 *
	 * @return An optional with the enum value if found empty otherwise
	 */
	public static Optional<AuthenticationEnum> getValueOf(final String name) {
		return name != null ? Optional.ofNullable(VALUES_OF.get(name.trim().toUpperCase())) : Optional.empty();
	}
}
