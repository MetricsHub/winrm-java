package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2026 MetricsHub
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;

public class WindowsRemoteProcessUtils {

	private WindowsRemoteProcessUtils() {}

	private static final String DEFAULT_CODESET = "1252";
	private static final Charset DEFAULT_CHARSET = Charset.forName("windows-1252");

	/**
	 * Windows CodeSet to java.nio.charset Charset Code map.
	 *
	 * @see <a href="https://en.wikipedia.org/wiki/Windows_code_page">Windows code page</a>
	 * @see <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/intl/encoding.doc.html">
	 *      Supported Encodings</a>
	 */
	private static final Map<String, Charset> CODESET_MAP;

	static {
		final Map<String, Charset> map = new HashMap<>();
		map.put("1250", Charset.forName("windows-1250"));
		map.put("1251", Charset.forName("windows-1251"));
		map.put("1252", DEFAULT_CHARSET);
		map.put("1253", Charset.forName("windows-1253"));
		map.put("1254", Charset.forName("windows-1254"));
		map.put("1255", Charset.forName("windows-1255"));
		map.put("1256", Charset.forName("windows-1256"));
		map.put("1257", Charset.forName("windows-1257"));
		map.put("1258", Charset.forName("windows-1258"));
		map.put("874", Charset.forName("x-windows-874"));
		map.put("932", Charset.forName("Shift_JIS"));
		map.put("936", Charset.forName("GBK"));
		map.put("949", Charset.forName("EUC-KR"));
		map.put("950", Charset.forName("Big5"));
		map.put("951", Charset.forName("Big5-HKSCS"));
		map.put("28591", StandardCharsets.ISO_8859_1);
		map.put("20127", StandardCharsets.US_ASCII);
		map.put("65001", StandardCharsets.UTF_8);
		map.put("1200", StandardCharsets.UTF_16LE);
		map.put("1201", StandardCharsets.UTF_16BE);

		CODESET_MAP = Collections.unmodifiableMap(map);
	}

	/**
	 * Get the CharSet from the Win32_OperatingSystem CodeSet. (if not found by default Latin-1 windows-1252)
	 *
	 * @param windowsRemoteExecutor WindowsRemoteExecutor instance
	 * @param timeout Timeout in milliseconds.
	 * @return the encoding charset from Win32_OperatingSystem
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WqlQuerySyntaxException On WQL syntax errors
	 * @throws WindowsRemoteException For any problem encountered on remote
	 * @see <a href="https://docs.microsoft.com/en-us/windows/win32/cimwin32prov/win32-operatingsystem">
	 *      Win32_OperatingSystem class</a>
	 * @deprecated Not the charset of remote command output, and never was: {@code CodeSet} is the
	 *             remote machine's <em>ANSI</em> code page, while {@code cmd.exe} writes its output in
	 *             the <em>console (OEM)</em> code page — the two differ on every non-English locale.
	 *             Command output is now decoded with
	 *             {@link WindowsRemoteExecutor#SHELL_OUTPUT_CHARSET}, the code page the remote shell
	 *             is created with. Use this method only to decode data that a Windows application
	 *             genuinely wrote in the ANSI code page.
	 */
	@Deprecated(since = "2.0.00", forRemoval = false)
	public static Charset getWindowsEncodingCharset(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final long timeout
	) throws TimeoutException, WqlQuerySyntaxException, WindowsRemoteException {
		if (windowsRemoteExecutor == null || timeout < 1) {
			return DEFAULT_CHARSET;
		}

		// Explicitly in ROOT\CIMV2: the executor's default namespace may be a custom one, where
		// Win32_OperatingSystem does not exist.
		final List<Map<String, Object>> result = WmiHelper.executeWqlInCimv2(
			windowsRemoteExecutor,
			"SELECT CodeSet FROM Win32_OperatingSystem",
			timeout
		);

		final String codeSet = result
			.stream()
			.map(row -> (String) row.get("CodeSet"))
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(DEFAULT_CODESET);

		return CODESET_MAP.getOrDefault(codeSet, DEFAULT_CHARSET);
	}

	/**
	 * Builds a new output file name, with 99.9999999% chances of being unique
	 * on the remote system
	 *
	 * @return file name
	 */
	public static String buildNewOutputFileName() {
		return String.format(
			"SEN_%s_%d_%d",
			Utils.getComputerName(),
			Utils.getCurrentTimeMillis(),
			(long) (Math.random() * 1000000)
		);
	}

	/**
	 * Perform a case-insensitive replace of all occurrences of <em>target</em> string with
	 * specified <em>replacement</em>
	 * Similar to <code>String.replace(target, replacement)</code>
	 *
	 * @param string The string to parse
	 * @param target The string to replace
	 * @param replacement The replacement string
	 * @return updated string
	 */
	static String caseInsensitiveReplace(final String string, final String target, final String replacement) {
		return string == null || target == null
			? string
			: Pattern
				.compile(target, Pattern.LITERAL | Pattern.CASE_INSENSITIVE)
				.matcher(string)
				.replaceAll(Matcher.quoteReplacement(replacement == null ? Utils.EMPTY : replacement));
	}
}
