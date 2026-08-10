package org.metricshub.winrm.light;

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

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * WS-Management SOAP envelope templates — the only "WSDL" the light client needs.
 * Covers Identify, WQL enumeration (Enumerate / Pull / Release), and the command shell lifecycle
 * (Create / Command / Send / Receive / Signal / Delete).
 */
final class Envelopes {

	private static final String SOAP = "http://www.w3.org/2003/05/soap-envelope";
	private static final String WSA = "http://schemas.xmlsoap.org/ws/2004/08/addressing";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";
	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String RSP = "http://schemas.microsoft.com/wbem/wsman/1/windows/shell";
	private static final String ANONYMOUS = "http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous";

	private static final String ACTION_ENUMERATE = "http://schemas.xmlsoap.org/ws/2004/09/enumeration/Enumerate";
	private static final String ACTION_PULL = "http://schemas.xmlsoap.org/ws/2004/09/enumeration/Pull";
	private static final String ACTION_RELEASE = "http://schemas.xmlsoap.org/ws/2004/09/enumeration/Release";
	private static final String ACTION_CREATE = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Create";
	private static final String ACTION_DELETE = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete";
	private static final String ACTION_COMMAND = RSP + "/Command";
	private static final String ACTION_RECEIVE = RSP + "/Receive";
	private static final String ACTION_SIGNAL = RSP + "/Signal";
	private static final String ACTION_SEND = RSP + "/Send";

	static final String SHELL_RESOURCE_URI = RSP + "/cmd";
	static final String TERMINATE_CODE = RSP + "/signal/terminate";

	/**
	 * The WSMan equivalent of a console Ctrl+C: it interrupts the command's child process without
	 * terminating the command (and its shell) the way {@link #TERMINATE_CODE} does.
	 */
	static final String CTRL_C_CODE = RSP + "/signal/ctrl_c";

	static final String COMMAND_STATE_DONE = RSP + "/CommandState/Done";

	static final int MAX_ENVELOPE_SIZE = 153600;

	/**
	 * Largest stdin payload carried by a single Send, in raw bytes before base64. The whole envelope
	 * must stay under {@link #MAX_ENVELOPE_SIZE}: base64 inflates by 4/3, and the SOAP header,
	 * selectors and element markup around the payload need a few hundred bytes — 96 KiB of raw input
	 * becomes 128 KiB of base64, leaving comfortable room inside the 150 KiB limit.
	 */
	static final int MAX_STDIN_CHUNK = 96 * 1024;

	/**
	 * Console code page of the remote shell: UTF-8. The shell's output charset must be one the
	 * client knows without asking, and it must be able to carry every locale's characters — an OEM
	 * page like 437 can encode neither {@code é} on a French host nor anything CJK at all.
	 */
	private static final String CODEPAGE_UTF8 = "65001";

	private Envelopes() {}

	// --- WQL ---------------------------------------------------------------

	static String enumerateWql(
		final String url,
		final String namespace,
		final String wql,
		final long timeoutMs,
		final int maxElements
	) {
		return envelopeOpen(false) +
			header(url, wmiResourceUri(namespace), ACTION_ENUMERATE, timeoutMs, null, null) +
			"<s:Body><wsen:Enumerate>" +
			"<wsman:OptimizeEnumeration/>" +
			"<wsman:MaxElements>" +
			maxElements +
			"</wsman:MaxElements>" +
			"<wsman:Filter Dialect=\"http://schemas.microsoft.com/wbem/wsman/1/WQL\">" +
			escape(wql) +
			"</wsman:Filter>" +
			"</wsen:Enumerate></s:Body></s:Envelope>";
	}

	static String pull(
		final String url,
		final String namespace,
		final String context,
		final long timeoutMs,
		final int maxElements,
		final long maxTimeMs
	) {
		// Per WS-Enumeration, MaxTime precedes MaxElements inside Pull. MaxTime bounds how long the
		// server may hold this single Pull open before answering with the rows it has; 0 omits it and
		// the OperationTimeout header applies alone.
		return envelopeOpen(false) +
			header(url, wmiResourceUri(namespace), ACTION_PULL, timeoutMs, null, null) +
			"<s:Body><wsen:Pull>" +
			"<wsen:EnumerationContext>" +
			escape(context) +
			"</wsen:EnumerationContext>" +
			(maxTimeMs > 0 ? "<wsen:MaxTime>" + operationTimeout(maxTimeMs) + "</wsen:MaxTime>" : "") +
			"<wsen:MaxElements>" +
			maxElements +
			"</wsen:MaxElements>" +
			"</wsen:Pull></s:Body></s:Envelope>";
	}

	static String release(final String url, final String namespace, final String context, final long timeoutMs) {
		// WS-Enumeration Release: tells the server to discard an enumeration context that will not be
		// pulled to its end, freeing the server-side operation slot immediately instead of waiting for
		// its idle timeout.
		return envelopeOpen(false) +
			header(url, wmiResourceUri(namespace), ACTION_RELEASE, timeoutMs, null, null) +
			"<s:Body><wsen:Release>" +
			"<wsen:EnumerationContext>" +
			escape(context) +
			"</wsen:EnumerationContext>" +
			"</wsen:Release></s:Body></s:Envelope>";
	}

	// --- Command shell -----------------------------------------------------

	/**
	 * Create a command shell.
	 *
	 * @param environment environment variables of the shell, in insertion order; {@code null} or
	 *        empty omits the {@code rsp:Environment} block
	 * @param codePage the console code page of the shell ({@code WINRS_CODEPAGE}); 0 uses
	 *        {@link #CODEPAGE_UTF8}, the default that makes every command's output UTF-8
	 */
	static String createShell(
		final String url,
		final String workingDirectory,
		final Map<String, String> environment,
		final long timeoutMs,
		final int codePage
	) {
		final String optionSet = "<wsman:OptionSet>" +
			"<wsman:Option Name=\"WINRS_NOPROFILE\">TRUE</wsman:Option>" +
			"<wsman:Option Name=\"WINRS_CODEPAGE\">" + (codePage > 0 ? String.valueOf(codePage) : CODEPAGE_UTF8)
			+ "</wsman:Option>" +
			"</wsman:OptionSet>";
		final String workingDir = (workingDirectory == null || workingDirectory.trim().isEmpty())
			? ""
			: "<rsp:WorkingDirectory>" + escape(workingDirectory) + "</rsp:WorkingDirectory>";
		// The MS-WSMV Shell_Type schema is a sequence: Environment, then WorkingDirectory, then the
		// stream declarations — the order of the protocol's own Create example.
		return envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_CREATE, timeoutMs, null, optionSet) +
			"<s:Body><rsp:Shell>" +
			environmentBlock(environment) +
			workingDir +
			"<rsp:InputStreams>stdin</rsp:InputStreams>" +
			"<rsp:OutputStreams>stdout stderr</rsp:OutputStreams>" +
			"</rsp:Shell></s:Body></s:Envelope>";
	}

	/** The {@code rsp:Environment} block of a Create request, or an empty string for no variables. */
	private static String environmentBlock(final Map<String, String> environment) {
		if (environment == null || environment.isEmpty()) {
			return "";
		}
		final StringBuilder block = new StringBuilder("<rsp:Environment>");
		for (final Map.Entry<String, String> variable : environment.entrySet()) {
			block
				.append("<rsp:Variable Name=\"")
				.append(escape(variable.getKey()))
				.append("\">")
				.append(escape(variable.getValue()))
				.append("</rsp:Variable>");
		}
		return block.append("</rsp:Environment>").toString();
	}

	/**
	 * Start a command in an existing shell.
	 *
	 * @param consoleModeStdin value of the {@code WINRS_CONSOLEMODE_STDIN} option: {@code TRUE} makes
	 *        the remote stdin behave like a console (what an interactive session and a command that
	 *        never reads input want), {@code FALSE} makes it an ordinary pipe, which is what a
	 *        command fed programmatic input needs — a console-mode stdin never reaches EOF for tools
	 *        like {@code sort} or {@code findstr}
	 */
	static String command(
		final String url,
		final String shellId,
		final String commandLine,
		final long timeoutMs,
		final boolean consoleModeStdin
	) {
		final String optionSet = "<wsman:OptionSet>" +
			"<wsman:Option Name=\"WINRS_CONSOLEMODE_STDIN\">" + (consoleModeStdin ? "TRUE" : "FALSE")
			+ "</wsman:Option>" +
			"<wsman:Option Name=\"WINRS_SKIP_CMD_SHELL\">FALSE</wsman:Option>" +
			"</wsman:OptionSet>";
		return envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_COMMAND, timeoutMs, shellSelector(shellId), optionSet) +
			"<s:Body><rsp:CommandLine><rsp:Command>" +
			escape(commandLine) +
			"</rsp:Command></rsp:CommandLine></s:Body></s:Envelope>";
	}

	/**
	 * Feed one chunk of standard input to a running command.
	 *
	 * @param base64Data the chunk's bytes, base64-encoded (may be empty on a pure end-of-input Send)
	 * @param end {@code true} to mark this chunk as the last one — the remote stdin then reaches EOF
	 */
	static String send(
		final String url,
		final String shellId,
		final String commandId,
		final String base64Data,
		final boolean end,
		final long timeoutMs
	) {
		return envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_SEND, timeoutMs, shellSelector(shellId), null) +
			"<s:Body><rsp:Send><rsp:Stream Name=\"stdin\" CommandId=\"" +
			escape(commandId) +
			"\"" +
			(end ? " End=\"true\"" : "") +
			">" +
			base64Data +
			"</rsp:Stream></rsp:Send></s:Body></s:Envelope>";
	}

	static String receive(final String url, final String shellId, final String commandId, final long timeoutMs) {
		return envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_RECEIVE, timeoutMs, shellSelector(shellId), null) +
			"<s:Body><rsp:Receive><rsp:DesiredStream CommandId=\"" +
			escape(commandId) +
			"\">stdout stderr</rsp:DesiredStream></rsp:Receive></s:Body></s:Envelope>";
	}

	/**
	 * Signal a running command.
	 *
	 * @param code the signal code — {@link #TERMINATE_CODE} to stop the command, {@link #CTRL_C_CODE}
	 *        to interrupt its child process the way a console Ctrl+C would
	 */
	static String signal(
		final String url,
		final String shellId,
		final String commandId,
		final String code,
		final long timeoutMs
	) {
		return envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_SIGNAL, timeoutMs, shellSelector(shellId), null) +
			"<s:Body><rsp:Signal CommandId=\"" +
			escape(commandId) +
			"\"><rsp:Code>" +
			code +
			"</rsp:Code></rsp:Signal></s:Body></s:Envelope>";
	}

	static String deleteShell(final String url, final String shellId, final long timeoutMs) {
		return envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_DELETE, timeoutMs, shellSelector(shellId), null) +
			"<s:Body/></s:Envelope>";
	}

	// --- helpers -----------------------------------------------------------

	private static String wmiResourceUri(final String namespace) {
		return "http://schemas.microsoft.com/wbem/wsman/1/wmi/" + namespace + "/*";
	}

	private static String shellSelector(final String shellId) {
		return "<wsman:SelectorSet><wsman:Selector Name=\"ShellId\">" + escape(shellId)
			+ "</wsman:Selector></wsman:SelectorSet>";
	}

	/**
	 * Format the WSMan OperationTimeout exactly like the CXF backend: {@code PT#.###S} with ROOT
	 * locale symbols (always a {@code .} decimal separator, up to millisecond precision, no trailing
	 * zeros) — e.g. 30000 ms → {@code PT30S}, 1500 ms → {@code PT1.5S}, 1234 ms → {@code PT1.234S}.
	 */
	private static String operationTimeout(final long timeoutMs) {
		final BigDecimal seconds = BigDecimal.valueOf(timeoutMs).divide(BigDecimal.valueOf(1000));
		return new DecimalFormat("PT#.###S", new DecimalFormatSymbols(Locale.ROOT)).format(seconds);
	}

	private static String header(
		final String url,
		final String resourceUri,
		final String action,
		final long timeoutMs,
		final String selectorSet,
		final String optionSet
	) {
		return "<s:Header>" +
			"<wsa:To>" +
			url +
			"</wsa:To>" +
			"<wsman:ResourceURI s:mustUnderstand=\"true\">" +
			resourceUri +
			"</wsman:ResourceURI>" +
			"<wsa:ReplyTo><wsa:Address s:mustUnderstand=\"true\">" +
			ANONYMOUS +
			"</wsa:Address></wsa:ReplyTo>" +
			"<wsa:Action s:mustUnderstand=\"true\">" +
			action +
			"</wsa:Action>" +
			"<wsman:MaxEnvelopeSize s:mustUnderstand=\"true\">" +
			MAX_ENVELOPE_SIZE +
			"</wsman:MaxEnvelopeSize>" +
			"<wsa:MessageID>uuid:" +
			UUID.randomUUID().toString().toUpperCase() +
			"</wsa:MessageID>" +
			"<wsman:Locale xml:lang=\"en-US\" s:mustUnderstand=\"false\"/>" +
			(selectorSet == null ? "" : selectorSet) +
			(optionSet == null ? "" : optionSet) +
			"<wsman:OperationTimeout>" +
			operationTimeout(timeoutMs) +
			"</wsman:OperationTimeout>" +
			"</s:Header>";
	}

	private static String envelopeOpen(final boolean shell) {
		return "<s:Envelope xmlns:s=\"" +
			SOAP +
			"\" xmlns:wsa=\"" +
			WSA +
			"\" xmlns:wsman=\"" +
			WSMAN +
			"\" xmlns:wsen=\"" +
			WSEN +
			"\"" +
			(shell ? " xmlns:rsp=\"" + RSP + "\"" : "") +
			">";
	}

	private static String escape(final String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
