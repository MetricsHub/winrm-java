package org.metricshub.winrm.light;

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

import java.util.UUID;

/**
 * WS-Management SOAP envelope templates — the only "WSDL" the light client needs.
 * Covers Identify, WQL Enumerate/Pull, and the command shell lifecycle
 * (Create / Command / Receive / Signal / Delete).
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
	private static final String ACTION_CREATE = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Create";
	private static final String ACTION_DELETE = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Delete";
	private static final String ACTION_COMMAND = RSP + "/Command";
	private static final String ACTION_RECEIVE = RSP + "/Receive";
	private static final String ACTION_SIGNAL = RSP + "/Signal";

	static final String SHELL_RESOURCE_URI = RSP + "/cmd";
	static final String TERMINATE_CODE = RSP + "/signal/terminate";
	static final String COMMAND_STATE_DONE = RSP + "/CommandState/Done";

	private static final int MAX_ENVELOPE_SIZE = 153600;

	private Envelopes() {}

	// --- WQL ---------------------------------------------------------------

	static String enumerateWql(final String url, final String namespace, final String wql, final long timeoutMs) {
		return (
			envelopeOpen(false) +
			header(url, wmiResourceUri(namespace), ACTION_ENUMERATE, timeoutMs, null, null) +
			"<s:Body><wsen:Enumerate>" +
			"<wsman:OptimizeEnumeration/>" +
			"<wsman:MaxElements>32000</wsman:MaxElements>" +
			"<wsman:Filter Dialect=\"http://schemas.microsoft.com/wbem/wsman/1/WQL\">" +
			escape(wql) +
			"</wsman:Filter>" +
			"</wsen:Enumerate></s:Body></s:Envelope>"
		);
	}

	static String pull(final String url, final String namespace, final String context, final long timeoutMs) {
		return (
			envelopeOpen(false) +
			header(url, wmiResourceUri(namespace), ACTION_PULL, timeoutMs, null, null) +
			"<s:Body><wsen:Pull>" +
			"<wsen:EnumerationContext>" +
			escape(context) +
			"</wsen:EnumerationContext>" +
			"<wsen:MaxElements>32000</wsen:MaxElements>" +
			"</wsen:Pull></s:Body></s:Envelope>"
		);
	}

	// --- Command shell -----------------------------------------------------

	static String createShell(final String url, final String workingDirectory, final long timeoutMs) {
		final String optionSet =
			"<wsman:OptionSet>" +
			"<wsman:Option Name=\"WINRS_NOPROFILE\">TRUE</wsman:Option>" +
			"<wsman:Option Name=\"WINRS_CODEPAGE\">437</wsman:Option>" +
			"</wsman:OptionSet>";
		final String workingDir = (workingDirectory == null || workingDirectory.trim().isEmpty())
			? ""
			: "<rsp:WorkingDirectory>" + escape(workingDirectory) + "</rsp:WorkingDirectory>";
		return (
			envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_CREATE, timeoutMs, null, optionSet) +
			"<s:Body><rsp:Shell>" +
			"<rsp:InputStreams>stdin</rsp:InputStreams>" +
			"<rsp:OutputStreams>stdout stderr</rsp:OutputStreams>" +
			workingDir +
			"</rsp:Shell></s:Body></s:Envelope>"
		);
	}

	static String command(final String url, final String shellId, final String commandLine, final long timeoutMs) {
		final String optionSet =
			"<wsman:OptionSet>" +
			"<wsman:Option Name=\"WINRS_CONSOLEMODE_STDIN\">TRUE</wsman:Option>" +
			"<wsman:Option Name=\"WINRS_SKIP_CMD_SHELL\">FALSE</wsman:Option>" +
			"</wsman:OptionSet>";
		return (
			envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_COMMAND, timeoutMs, shellSelector(shellId), optionSet) +
			"<s:Body><rsp:CommandLine><rsp:Command>" +
			escape(commandLine) +
			"</rsp:Command></rsp:CommandLine></s:Body></s:Envelope>"
		);
	}

	static String receive(final String url, final String shellId, final String commandId, final long timeoutMs) {
		return (
			envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_RECEIVE, timeoutMs, shellSelector(shellId), null) +
			"<s:Body><rsp:Receive><rsp:DesiredStream CommandId=\"" +
			escape(commandId) +
			"\">stdout stderr</rsp:DesiredStream></rsp:Receive></s:Body></s:Envelope>"
		);
	}

	static String signal(final String url, final String shellId, final String commandId, final long timeoutMs) {
		return (
			envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_SIGNAL, timeoutMs, shellSelector(shellId), null) +
			"<s:Body><rsp:Signal CommandId=\"" +
			escape(commandId) +
			"\"><rsp:Code>" +
			TERMINATE_CODE +
			"</rsp:Code></rsp:Signal></s:Body></s:Envelope>"
		);
	}

	static String deleteShell(final String url, final String shellId, final long timeoutMs) {
		return (
			envelopeOpen(true) +
			header(url, SHELL_RESOURCE_URI, ACTION_DELETE, timeoutMs, shellSelector(shellId), null) +
			"<s:Body/></s:Envelope>"
		);
	}

	// --- helpers -----------------------------------------------------------

	private static String wmiResourceUri(final String namespace) {
		return "http://schemas.microsoft.com/wbem/wsman/1/wmi/" + namespace + "/*";
	}

	private static String shellSelector(final String shellId) {
		return (
			"<wsman:SelectorSet><wsman:Selector Name=\"ShellId\">" + escape(shellId) + "</wsman:Selector></wsman:SelectorSet>"
		);
	}

	private static String header(
		final String url,
		final String resourceUri,
		final String action,
		final long timeoutMs,
		final String selectorSet,
		final String optionSet
	) {
		final long seconds = Math.max(1, timeoutMs / 1000);
		return (
			"<s:Header>" +
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
			"<wsman:OperationTimeout>PT" +
			seconds +
			"S</wsman:OperationTimeout>" +
			"</s:Header>"
		);
	}

	private static String envelopeOpen(final boolean shell) {
		return (
			"<s:Envelope xmlns:s=\"" +
			SOAP +
			"\" xmlns:wsa=\"" +
			WSA +
			"\" xmlns:wsman=\"" +
			WSMAN +
			"\" xmlns:wsen=\"" +
			WSEN +
			"\"" +
			(shell ? " xmlns:rsp=\"" + RSP + "\"" : "") +
			">"
		);
	}

	private static String escape(final String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
