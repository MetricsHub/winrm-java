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

import java.util.Base64;

/**
 * Canned WSMan SOAP response bodies — and helpers that enqueue whole protocol exchanges — for
 * end-to-end tests against {@link FakeWsmanServer}: WQL enumeration results, the command shell
 * lifecycle, and WSMan faults. Shared by the protocol tests in this package and the executor and
 * CLI tests that exercise the full stack from the public API down to the wire.
 */
public final class FakeWsmanResponses {

	/** Shell resource id used by the scripted shell-lifecycle responses. */
	public static final String SHELL_ID = "SHELL-1";

	/** Command id used by the scripted command exchanges. */
	public static final String COMMAND_ID = "CMD-1";

	private static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";
	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";
	private static final String RSP = "http://schemas.microsoft.com/wbem/wsman/1/windows/shell";
	private static final String FAULT_NS = "http://schemas.microsoft.com/wbem/wsman/1/wsmanfault";
	private static final String WMI_NS_PREFIX = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/";

	private FakeWsmanResponses() {}

	// --- SOAP body builders --------------------------------------------------

	/**
	 * Wrap a body in a SOAP 1.2 envelope.
	 *
	 * @param body the body content
	 * @return the complete envelope
	 */
	public static String envelope(final String body) {
		return "<s:Envelope xmlns:s=\"" + SOAP_NS + "\"><s:Header/><s:Body>" + body + "</s:Body></s:Envelope>";
	}

	/**
	 * One WMI instance as WinRM serializes it in an enumeration, e.g.
	 * {@code instance("Win32_Service", "Name", "Spooler", "State", "Running")}.
	 *
	 * @param className the WMI class name
	 * @param properties alternating property names and values
	 * @return the instance element
	 */
	public static String instance(final String className, final String... properties) {
		final StringBuilder xml = new StringBuilder();
		xml.append("<p:").append(className).append(" xmlns:p=\"").append(WMI_NS_PREFIX).append(className).append("\">");
		for (int i = 0; i + 1 < properties.length; i += 2) {
			xml
				.append("<p:")
				.append(properties[i])
				.append('>')
				.append(properties[i + 1])
				.append("</p:")
				.append(properties[i])
				.append('>');
		}
		return xml.append("</p:").append(className).append('>').toString();
	}

	/**
	 * A complete single-page WQL result: an optimized EnumerateResponse carrying the items and the
	 * end-of-sequence marker, so no Pull follows.
	 *
	 * @param instances the serialized WMI instances (see {@link #instance(String, String...)})
	 * @return the EnumerateResponse body
	 */
	public static String enumerationDone(final String... instances) {
		final StringBuilder xml = new StringBuilder();
		xml
			.append("<wsen:EnumerateResponse xmlns:wsen=\"")
			.append(WSEN)
			.append("\" xmlns:wsman=\"")
			.append(WSMAN)
			.append("\"><wsman:Items>");
		for (final String item : instances) {
			xml.append(item);
		}
		return xml.append("</wsman:Items><wsman:EndOfSequence/></wsen:EnumerateResponse>").toString();
	}

	/**
	 * The ResourceCreated body answering a shell Create request.
	 *
	 * @param shellId the shell id the response designates
	 * @return the ResourceCreated body
	 */
	public static String resourceCreated(final String shellId) {
		return ("<x:ResourceCreated xmlns:x=\"http://schemas.xmlsoap.org/ws/2004/09/transfer\"" +
			" xmlns:wsa=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:wsman=\"" +
			WSMAN +
			"\">" +
			"<wsa:Address>http://127.0.0.1/wsman</wsa:Address>" +
			"<wsa:ReferenceParameters>" +
			"<wsman:ResourceURI>" +
			RSP +
			"/cmd</wsman:ResourceURI>" +
			"<wsman:SelectorSet><wsman:Selector Name=\"ShellId\">" +
			shellId +
			"</wsman:Selector></wsman:SelectorSet>" +
			"</wsa:ReferenceParameters></x:ResourceCreated>");
	}

	/**
	 * The CommandResponse body answering a Command request.
	 *
	 * @param commandId the command id the response designates
	 * @return the CommandResponse body
	 */
	public static String commandResponse(final String commandId) {
		return ("<rsp:CommandResponse xmlns:rsp=\"" +
			RSP +
			"\"><rsp:CommandId>" +
			commandId +
			"</rsp:CommandId></rsp:CommandResponse>");
	}

	/**
	 * A ReceiveResponse body carrying the given streams and, optionally, the final command state.
	 *
	 * @param streams the concatenated Stream elements (see {@link #stream(String, String, byte[])})
	 * @param commandState the CommandState element (see {@link #done(String, int)}), or null while
	 *        the command is still running
	 * @return the ReceiveResponse body
	 */
	public static String receiveResponse(final String streams, final String commandState) {
		return ("<rsp:ReceiveResponse xmlns:rsp=\"" +
			RSP +
			"\">" +
			streams +
			(commandState == null ? "" : commandState) +
			"</rsp:ReceiveResponse>");
	}

	/**
	 * One base64-encoded output Stream element of a ReceiveResponse.
	 *
	 * @param name the stream name (stdout or stderr)
	 * @param commandId the command the stream belongs to
	 * @param content the raw stream bytes
	 * @return the Stream element
	 */
	public static String stream(final String name, final String commandId, final byte[] content) {
		return ("<rsp:Stream Name=\"" +
			name +
			"\" CommandId=\"" +
			commandId +
			"\">" +
			Base64.getEncoder().encodeToString(content) +
			"</rsp:Stream>");
	}

	/**
	 * The CommandState element reporting command completion.
	 *
	 * @param commandId the finished command
	 * @param exitCode the command exit code
	 * @return the CommandState element
	 */
	public static String done(final String commandId, final int exitCode) {
		return ("<rsp:CommandState CommandId=\"" +
			commandId +
			"\" State=\"" +
			RSP +
			"/CommandState/Done\"><rsp:ExitCode>" +
			exitCode +
			"</rsp:ExitCode></rsp:CommandState>");
	}

	/**
	 * The SignalResponse body answering the terminate Signal.
	 *
	 * @return the SignalResponse body
	 */
	public static String signalResponse() {
		return "<rsp:SignalResponse xmlns:rsp=\"" + RSP + "\"/>";
	}

	/**
	 * The SendResponse body answering a stdin Send.
	 *
	 * @return the SendResponse body
	 */
	public static String sendResponse() {
		return "<rsp:SendResponse xmlns:rsp=\"" + RSP + "\"/>";
	}

	/**
	 * A complete WSMan fault envelope (not to be wrapped in {@link #envelope(String)}).
	 *
	 * @param code the WSManFault code
	 * @param reason the fault reason text
	 * @return the fault envelope
	 */
	public static String fault(final String code, final String reason) {
		return fault(code, reason, null);
	}

	/**
	 * A complete WSMan fault envelope (not to be wrapped in {@link #envelope(String)}) with a
	 * provider-level detail message.
	 *
	 * @param code the WSManFault code
	 * @param reason the fault reason text
	 * @param detailMessage the provider-level detail, or null to repeat the reason
	 * @return the fault envelope
	 */
	public static String fault(final String code, final String reason, final String detailMessage) {
		return ("<s:Envelope xmlns:s=\"" +
			SOAP_NS +
			"\"><s:Body><s:Fault>" +
			"<s:Code><s:Value>s:Receiver</s:Value></s:Code>" +
			"<s:Reason><s:Text xml:lang=\"en-US\">" +
			reason +
			"</s:Text></s:Reason>" +
			"<s:Detail><f:WSManFault xmlns:f=\"" +
			FAULT_NS +
			"\" Code=\"" +
			code +
			"\" Machine=\"fake\">" +
			"<f:Message>" +
			(detailMessage == null ? reason : detailMessage) +
			"</f:Message></f:WSManFault></s:Detail>" +
			"</s:Fault></s:Body></s:Envelope>");
	}

	// --- whole-exchange enqueue helpers ---------------------------------------

	/**
	 * Enqueue a complete single-page WQL result: the Enumerate request is answered with the items
	 * and the end-of-sequence marker, so the client issues no Pull.
	 *
	 * @param server the fake server to script
	 * @param instances the serialized WMI instances (see {@link #instance(String, String...)})
	 */
	public static void enqueueEnumeration(final FakeWsmanServer server, final String... instances) {
		server.enqueue(200, envelope(enumerationDone(instances)));
	}

	/**
	 * Enqueue the shell-creation response that answers the Create request preceding the first
	 * command executed on a fresh executor.
	 *
	 * @param server the fake server to script
	 */
	public static void enqueueShellCreation(final FakeWsmanServer server) {
		server.enqueue(200, envelope(resourceCreated(SHELL_ID)));
	}

	/**
	 * Enqueue a complete command exchange: the CommandResponse, one ReceiveResponse carrying the
	 * whole output and the final state, and the SignalResponse to the terminate Signal.
	 *
	 * @param server the fake server to script
	 * @param stdout the raw stdout bytes (as the remote code page encodes them)
	 * @param stderr the raw stderr bytes
	 * @param exitCode the command exit code
	 */
	public static void enqueueCommandExchange(
		final FakeWsmanServer server,
		final byte[] stdout,
		final byte[] stderr,
		final int exitCode
	) {
		final StringBuilder streams = new StringBuilder();
		if (stdout.length > 0) {
			streams.append(stream("stdout", COMMAND_ID, stdout));
		}
		if (stderr.length > 0) {
			streams.append(stream("stderr", COMMAND_ID, stderr));
		}
		server
			.enqueue(200, envelope(commandResponse(COMMAND_ID)))
			.enqueue(200, envelope(receiveResponse(streams.toString(), done(COMMAND_ID, exitCode))))
			.enqueue(200, envelope(signalResponse()));
	}

	/**
	 * Enqueue the response to the shell Delete that {@code close()} sends once a shell was created.
	 *
	 * @param server the fake server to script
	 */
	public static void enqueueShellDeletion(final FakeWsmanServer server) {
		server.enqueue(200, envelope("<x:DeleteResponse xmlns:x=\"http://schemas.xmlsoap.org/ws/2004/09/transfer\"/>"));
	}
}
