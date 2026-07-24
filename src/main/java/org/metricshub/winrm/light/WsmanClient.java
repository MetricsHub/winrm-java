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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLSocketFactory;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Dependency-free WinRM/WS-Management client: NTLM (masqueraded as Negotiate) with message
 * encryption over HTTP, templated SOAP envelopes, and JDK-default XML parsing (no Apache CXF,
 * JAX-WS, JAXB, or Woodstox). Supports Identify, WQL queries, and command execution.
 */
final class WsmanClient implements AutoCloseable {

	// If no output is available before the OperationTimeout expires, the server returns this WSMan
	// fault code and the client is expected to immediately re-issue the Receive request.
	private static final String FAULT_OPERATION_TIMEOUT = "2150858793";
	private static final String FAULT_SHELL_NOT_FOUND = "2150858843";

	// WS-Enumeration namespace: the EndOfSequence / EnumerationContext markers live here. Match them by
	// namespace, never by local name alone, so a WMI property that happens to be named "EndOfSequence"
	// or "EnumerationContext" inside <Items> cannot be mistaken for the enumeration control element.
	private static final String WS_ENUMERATION_NS = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";

	// WinRM also emits the Items / EndOfSequence markers in its own WSMan namespace (the wsman:Items /
	// wsman:EndOfSequence variants); the CXF backend accepts both, so the light backend must too.
	private static final String WSMAN_NS = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	private final long timeoutMs;
	private final String url;
	private final String rawUsername;
	private final AuthScheme auth;
	private final HttpTransport transport;

	private String pendingAuthorization;
	private String shellId;

	// A single NTLM connection is a serial channel: one socket, stateful RC4 ciphers with sequence
	// numbers, and a single shellId. Concurrent callers (e.g. a cached SmbTempShare shared across
	// threads) MUST NOT interleave, or they read each other's responses and desync the cipher streams.
	// Every high-level operation (wql/executeCommand) runs while holding this lock; close() only
	// tries it, so it can still hard-close the transport to unblock an abandoned, timed-out worker.
	private final ReentrantLock operationLock = new ReentrantLock();

	WsmanClient(
		final String host,
		final int port,
		final long timeoutMs,
		final SSLSocketFactory sslSocketFactory,
		final boolean verifyHostname,
		final AuthScheme auth,
		final String rawUsername
	) {
		this.timeoutMs = timeoutMs;
		// A non-null socket factory selects HTTPS: TLS wraps the transport and the SOAP travels plaintext.
		this.url = (sslSocketFactory != null ? "https" : "http") + "://" + host + ":" + port + "/wsman";
		this.rawUsername = rawUsername;
		this.auth = auth;
		this.transport = new HttpTransport(host, port, toSocketTimeoutMillis(timeoutMs), sslSocketFactory, verifyHostname);
	}

	/**
	 * Convert the public {@code long} timeout to the {@code int} milliseconds a {@link java.net.Socket}
	 * accepts. Clamp so a large but valid timeout never narrows to a negative/garbage value, leaving
	 * headroom for the extra read-timeout seconds {@link HttpTransport} adds. The full {@code long}
	 * remains authoritative for the WSMan OperationTimeout and the wall-clock deadline in the service.
	 */
	private static int toSocketTimeoutMillis(final long millis) {
		return (int) Math.min(millis, Integer.MAX_VALUE - 10_000L);
	}

	/** A decrypted WSMan response: HTTP status plus the (decrypted) SOAP body. */
	private static final class Decoded {

		final int status;
		final Document document;

		Decoded(final int status, final Document document) {
			this.status = status;
			this.document = document;
		}
	}

	/** Run a WQL query and return the rows as ordered property maps. */
	List<Map<String, String>> wql(final String namespace, final String query) throws Exception {
		// Serialize the whole enumeration (Enumerate + all Pulls) against any other operation sharing
		// this connection; see operationLock.
		operationLock.lock();
		try {
			// WMI namespaces are case-insensitive, but preserve the caller's case to match the CXF backend.
			final String ns = namespace.replace('\\', '/');
			final List<Map<String, String>> rows = new ArrayList<>();

			Document doc = expectOk(Envelopes.enumerateWql(url, ns, query, timeoutMs), "Enumerate");
			collectItems(doc, rows);

			// Pull until the server signals EndOfSequence (matching the CXF backend). The aggregate
			// timeout in LightWinRMService bounds a misbehaving server that never ends the sequence.
			boolean endOfSequence = hasEnumerationElement(doc, "EndOfSequence");
			String context = endOfSequence ? null : textNS(doc, WS_ENUMERATION_NS, "EnumerationContext");
			while (!endOfSequence && context != null && !context.isEmpty()) {
				doc = expectOk(Envelopes.pull(url, ns, context, timeoutMs), "Pull");
				collectItems(doc, rows);
				endOfSequence = hasEnumerationElement(doc, "EndOfSequence");
				context = endOfSequence ? null : textNS(doc, WS_ENUMERATION_NS, "EnumerationContext");
			}
			return rows;
		} finally {
			operationLock.unlock();
		}
	}

	/** The result of running a command in the remote shell. */
	static final class CommandOutput {

		final String stdout;
		final String stderr;
		final int exitCode;

		CommandOutput(final String stdout, final String stderr, final int exitCode) {
			this.stdout = stdout;
			this.stderr = stderr;
			this.exitCode = exitCode;
		}
	}

	/** Execute a command in the remote command shell, creating the shell on first use. */
	CommandOutput executeCommand(final String commandLine, final String workingDirectory, final Charset charset)
		throws Exception {
		// Serialize the whole shell lifecycle (Create + Command + Receive loop + Signal) against any
		// other operation sharing this connection and the shellId field; see operationLock.
		operationLock.lock();
		try {
			if (shellId == null) {
				createShell(workingDirectory);
			}
			final Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
			final String commandId = startCommand(commandLine);
			try {
				return receiveLoop(commandId, cs);
			} finally {
				terminate(commandId);
			}
		} finally {
			operationLock.unlock();
		}
	}

	private void createShell(final String workingDirectory) throws Exception {
		final Document doc = expectOk(Envelopes.createShell(url, workingDirectory, timeoutMs), "Create shell");
		final NodeList selectors = doc.getElementsByTagNameNS("*", "Selector");
		for (int i = 0; i < selectors.getLength(); i++) {
			final Element selector = (Element) selectors.item(i);
			if ("ShellId".equals(selector.getAttribute("Name"))) {
				shellId = selector.getTextContent();
				return;
			}
		}
		throw new IllegalStateException("Shell ID not found in Create response");
	}

	private String startCommand(final String commandLine) throws Exception {
		final Document doc = expectOk(Envelopes.command(url, shellId, commandLine, timeoutMs), "Command");
		final String commandId = text(doc, "CommandId");
		if (commandId == null) {
			throw new IllegalStateException("No CommandId in Command response");
		}
		return commandId;
	}

	private CommandOutput receiveLoop(final String commandId, final Charset charset) throws Exception {
		// Accumulate the raw stream BYTES and decode once at the end: a multibyte character (e.g. UTF-8)
		// can be split across Stream elements or Receive responses, and decoding each chunk independently
		// would corrupt the boundary bytes into replacement characters.
		final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		while (true) {
			final Decoded resp = request(Envelopes.receive(url, shellId, commandId, timeoutMs));
			if (resp.status != 200) {
				final String faultCode = wsmanFaultCode(resp.document);
				// No output before OperationTimeout → re-issue Receive immediately.
				if (FAULT_OPERATION_TIMEOUT.equals(faultCode)) {
					continue;
				}
				throw new IllegalStateException("Receive failed: " + faultSummary(resp));
			}
			collectStreams(resp.document, stdout, stderr);
			final Integer exitCode = doneExitCode(resp.document);
			if (exitCode != null) {
				return new CommandOutput(
					new String(stdout.toByteArray(), charset),
					new String(stderr.toByteArray(), charset),
					exitCode
				);
			}
		}
	}

	private void terminate(final String commandId) throws Exception {
		final Decoded resp = request(Envelopes.signal(url, shellId, commandId, timeoutMs));
		// A missing shell is fine here — the command already finished and the shell may be gone.
		if (resp.status != 200 && !FAULT_SHELL_NOT_FOUND.equals(wsmanFaultCode(resp.document))) {
			throw new IllegalStateException("Signal failed: " + faultSummary(resp));
		}
	}

	// --- transport / crypto -------------------------------------------------

	/** Send a request, expecting HTTP 200; throw with the WSMan fault detail otherwise. */
	private Document expectOk(final String soap, final String operation) throws Exception {
		final Decoded resp = request(soap);
		if (resp.status != 200) {
			throw new IllegalStateException(operation + " failed: " + faultSummary(resp));
		}
		return resp.document;
	}

	/**
	 * Send one SOAP request (authenticating the connection on first use via the {@link AuthScheme})
	 * and decode the response. The caller must hold {@link #operationLock}; every path here is reached
	 * from a locked wql/executeCommand/close, so requests never interleave on the stateful connection.
	 */
	private Decoded request(final String soap) throws Exception {
		// If the connection was dropped (e.g. the server sent "Connection: close"), the session bound
		// to it is dead — re-handshake on the fresh connection rather than sending unauthenticated.
		if (auth.isAuthenticated() && !transport.isConnected()) {
			auth.reset();
		}
		final byte[] body = soap.getBytes(StandardCharsets.UTF_8);
		while (true) {
			if (!auth.isAuthenticated()) {
				pendingAuthorization = auth.authenticate(transport);
			}
			// The handshake's Authorization accompanies the first real request; later requests on the
			// already-authenticated connection carry no Authorization header.
			final String authorization = pendingAuthorization;
			pendingAuthorization = null;

			final HttpTransport.Response resp = transport.post(
				"/wsman",
				auth.wrap(body),
				auth.wrapContentType(),
				authorization
			);

			// HTTP 401 = the server rejected the credentials/token. For NTLM and Kerberos this can only
			// surface here — the Type 3 / AP-REQ rides the first real request, not the handshake — so we
			// must NOT keep the "authenticated" connection (it would loop resending with no Authorization
			// header, wedging the executor). Drop it and, for an ordered fallback list, retry the next
			// scheme once on a fresh connection. A 401'd request was rejected before processing, so
			// re-sending it is safe.
			if (resp.status == 401) {
				transport.close();
				auth.reset();
				if (auth.advance()) {
					continue;
				}
				// Same message format as the CXF backend's credential-rejection path — callers (and their
				// operators) match on it.
				throw new IllegalStateException(
					String.format("Authentication error on %s with user name \"%s\"", url, rawUsername)
				);
			}

			// 200 = success, 500 = SOAP fault. Anything else is a protocol failure whose body is not a
			// usable WSMan response.
			if (resp.status != 200 && resp.status != 500) {
				throw new IllegalStateException("WSMan request failed: HTTP " + resp.status);
			}
			return new Decoded(resp.status, parse(auth.unwrap(resp)));
		}
	}

	// --- XML helpers --------------------------------------------------------

	static Document parse(final byte[] xml) throws Exception {
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
		factory.setNamespaceAware(true);
		// Harden against XXE: a malicious/compromised WinRM endpoint must not be able to make us
		// resolve external entities (local file read, SSRF, entity-expansion DoS). WSMan responses
		// never carry a DOCTYPE, so rejecting it outright is the strongest and safest defence.
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		factory.setXIncludeAware(false);
		factory.setExpandEntityReferences(false);
		final DocumentBuilder builder = factory.newDocumentBuilder();
		// Throw parse errors instead of letting the default handler print them to stderr — a request
		// abandoned by the timeout may parse a truncated response on a soon-to-die background thread.
		builder.setErrorHandler(
			new org.xml.sax.helpers.DefaultHandler() {
				@Override
				public void error(final org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
					throw e;
				}

				@Override
				public void fatalError(final org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
					throw e;
				}
			}
		);
		return builder.parse(new ByteArrayInputStream(xml));
	}

	private static String text(final Document doc, final String localName) {
		final NodeList nodes = doc.getElementsByTagNameNS("*", localName);
		return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
	}

	/**
	 * Whether the document contains the given enumeration control element (namespace-scoped).
	 * WinRM emits these markers either in the WS-Enumeration namespace or in its own WSMan namespace
	 * (e.g. {@code wsen:EndOfSequence} vs {@code wsman:EndOfSequence}); accept both, like the CXF
	 * backend does.
	 */
	static boolean hasEnumerationElement(final Document doc, final String localName) {
		return (doc.getElementsByTagNameNS(WS_ENUMERATION_NS, localName).getLength() > 0
			||
			doc.getElementsByTagNameNS(WSMAN_NS, localName).getLength() > 0);
	}

	/** First text content of an element matched by both namespace and local name. */
	private static String textNS(final Document doc, final String namespace, final String localName) {
		final NodeList nodes = doc.getElementsByTagNameNS(namespace, localName);
		return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
	}

	static void collectItems(final Document doc, final List<Map<String, String>> rows) {
		// The Items wrapper comes in the WS-Enumeration namespace (EnumerateResponse) or the WSMan
		// namespace (PullResponse) depending on the operation; accept both, like the CXF backend, and
		// nothing else — a WMI property or class named "Items" must not be mistaken for the wrapper.
		collectItems(doc.getElementsByTagNameNS(WS_ENUMERATION_NS, "Items"), rows);
		collectItems(doc.getElementsByTagNameNS(WSMAN_NS, "Items"), rows);
	}

	private static void collectItems(final NodeList items, final List<Map<String, String>> rows) {
		for (int i = 0; i < items.getLength(); i++) {
			final NodeList instances = items.item(i).getChildNodes();
			for (int j = 0; j < instances.getLength(); j++) {
				final Node instance = instances.item(j);
				if (instance.getNodeType() != Node.ELEMENT_NODE) {
					continue;
				}
				final Map<String, String> row = new LinkedHashMap<>();
				final NodeList props = instance.getChildNodes();
				for (int k = 0; k < props.getLength(); k++) {
					final Node prop = props.item(k);
					if (prop.getNodeType() == Node.ELEMENT_NODE) {
						row.put(((Element) prop).getLocalName(), prop.getTextContent());
					}
				}
				if (!row.isEmpty()) {
					rows.add(row);
				}
			}
		}
	}

	private static void collectStreams(
		final Document doc,
		final ByteArrayOutputStream stdout,
		final ByteArrayOutputStream stderr
	) {
		final NodeList streams = doc.getElementsByTagNameNS("*", "Stream");
		for (int i = 0; i < streams.getLength(); i++) {
			final Element stream = (Element) streams.item(i);
			final String value = stream.getTextContent();
			if (value == null || value.isEmpty()) {
				continue;
			}
			final byte[] bytes = Base64.getDecoder().decode(value);
			if ("stdout".equals(stream.getAttribute("Name"))) {
				stdout.write(bytes, 0, bytes.length);
			} else if ("stderr".equals(stream.getAttribute("Name"))) {
				stderr.write(bytes, 0, bytes.length);
			}
		}
	}

	/** Return the exit code if the response carries a CommandState of Done, otherwise null. */
	private static Integer doneExitCode(final Document doc) {
		final NodeList states = doc.getElementsByTagNameNS("*", "CommandState");
		for (int i = 0; i < states.getLength(); i++) {
			final Element state = (Element) states.item(i);
			if (Envelopes.COMMAND_STATE_DONE.equals(state.getAttribute("State"))) {
				final NodeList exit = state.getElementsByTagNameNS("*", "ExitCode");
				return exit.getLength() > 0 ? Integer.valueOf(exit.item(0).getTextContent().trim()) : 0;
			}
		}
		return null;
	}

	private static String wsmanFaultCode(final Document doc) {
		final NodeList faults = doc.getElementsByTagNameNS("*", "WSManFault");
		return faults.getLength() > 0 ? ((Element) faults.item(0)).getAttribute("Code") : null;
	}

	/**
	 * The detailed WSManFault Message text, or null. This is where WinRM puts the provider-level
	 * detail — notably the WMI error mnemonics (WBEM_E_INVALID_CLASS, WBEM_E_INVALID_NAMESPACE,
	 * WBEM_E_NOT_FOUND, ...) that callers match on to tell a bad query from a broken connection.
	 * {@code getTextContent()} also flattens any nested ProviderFault detail into the message.
	 */
	private static String wsmanFaultMessage(final Document doc) {
		final NodeList faults = doc.getElementsByTagNameNS("*", "WSManFault");
		if (faults.getLength() == 0) {
			return null;
		}
		final NodeList messages = ((Element) faults.item(0)).getElementsByTagNameNS("*", "Message");
		return messages.getLength() > 0 ? messages.item(0).getTextContent() : null;
	}

	static String faultSummary(final int status, final Document doc) {
		final String reason = trimToNull(text(doc, "Text"));
		final String detail = trimToNull(wsmanFaultMessage(doc));
		final String code = wsmanFaultCode(doc);
		final StringBuilder summary = new StringBuilder("HTTP ").append(status);
		if (code != null && !code.isEmpty()) {
			summary.append(" (WSManFault ").append(code).append(')');
		}
		if (reason != null) {
			summary.append(": ").append(reason);
		}
		// Append the detailed fault message when it adds anything beyond the Reason text: the WMI
		// mnemonics it carries are part of the exception-message contract inherited from CXF.
		if (detail != null && (reason == null || !reason.contains(detail))) {
			summary.append(reason == null ? ": " : " - ").append(detail);
		}
		return summary.toString();
	}

	private static String faultSummary(final Decoded resp) {
		return faultSummary(resp.status, resp.document);
	}

	private static String trimToNull(final String s) {
		if (s == null) {
			return null;
		}
		final String trimmed = s.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@Override
	public void close() {
		// Only attempt a graceful shell Delete if no operation is currently using the connection: a
		// blocking tryLock (never a lock()) keeps close() from waiting on an abandoned, timed-out worker
		// still holding operationLock while blocked on a socket read. When we cannot acquire the lock,
		// or a request would otherwise race the worker, we skip the Delete and just hard-close the
		// transport below — which unblocks that worker's read; the shell is reaped by the server IdleTimeout.
		final boolean locked = operationLock.tryLock();
		try {
			final String shell = shellId;
			shellId = null;
			if (locked) {
				if (shell != null) {
					try {
						request(Envelopes.deleteShell(url, shell, timeoutMs));
					} catch (final Exception ignore) {
						// best-effort shell cleanup
					}
				}
				// Release the connection-bound auth state — notably the Kerberos GSSContext, whose only
				// disposal path is reset(). Skipped when not locked: another (timed-out) worker still owns
				// the auth scheme, and the transport hard-close below unblocks it.
				auth.reset();
			}
		} finally {
			if (locked) {
				operationLock.unlock();
			}
			transport.close();
		}
	}
}
