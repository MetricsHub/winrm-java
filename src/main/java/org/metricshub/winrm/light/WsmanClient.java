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

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

	// Type 1 flags: engine defaults + SIGN | SEAL | KEY_EXCH (matches NtlmMasqAsSpnegoScheme).
	private static final int TYPE1_FLAGS = (int) (Type1Message.getDefaultFlags() |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_SIGN |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_SEAL |
		NTLMEngineUtils.NTLMSSP_NEGOTIATE_KEY_EXCH);

	private static final String SOAP_CONTENT_TYPE = "application/soap+xml;charset=UTF-8";
	private static final byte[] PRE_AUTH_BOGUS = "AWAITING_ENCRYPTION_KEYS".getBytes(StandardCharsets.US_ASCII);

	// If no output is available before the OperationTimeout expires, the server returns this WSMan
	// fault code and the client is expected to immediately re-issue the Receive request.
	private static final String FAULT_OPERATION_TIMEOUT = "2150858793";
	private static final String FAULT_SHELL_NOT_FOUND = "2150858843";

	// A WWW-Authenticate value may list several challenges ("Negotiate <b64>, NTLM ...") and a server
	// or proxy may split them across multiple header lines. Match the Negotiate scheme only at a
	// challenge boundary (start of value or right after a comma) and capture just its base64 token.
	private static final Pattern NEGOTIATE_TOKEN = Pattern.compile("(?i)(?:^|,)\\s*Negotiate\\s+([A-Za-z0-9+/=]+)");

	// WS-Enumeration namespace: the EndOfSequence / EnumerationContext markers live here. Match them by
	// namespace, never by local name alone, so a WMI property that happens to be named "EndOfSequence"
	// or "EnumerationContext" inside <Items> cannot be mistaken for the enumeration control element.
	private static final String WS_ENUMERATION_NS = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";

	private final long timeoutMs;
	private final String url;
	private final WinRMSession session;
	private final HttpTransport transport;

	private String pendingAuthorization;
	private String shellId;

	// True while a SOAP request is on the wire. close() consults this to avoid racing an abandoned
	// worker (e.g. a Receive left blocked on the socket after a command timeout) on the shared
	// socket and stateful RC4 session.
	private final AtomicBoolean requestInFlight = new AtomicBoolean(false);

	WsmanClient(
		final String host,
		final int port,
		final String domain,
		final String username,
		final String password,
		final long timeoutMs
	) {
		this.timeoutMs = timeoutMs;
		this.url = "http://" + host + ":" + port + "/wsman";
		// Uppercase the domain: NTOWFv2 (and thus the NTLM session key) is computed over it, the
		// Type 3 DomainName field goes on the wire uppercased, and the server derives its session
		// key from the uppercased value. A lowercase domain here passes authentication but fails
		// message integrity (server-side seal mismatch → HTTP 400).
		// Workstation is left empty in the Type 3 message, matching the reference client.
		final String upperDomain = domain == null ? null : domain.toUpperCase(Locale.ROOT);
		this.session = new WinRMSession(upperDomain, null, username, password);
		this.transport = new HttpTransport(host, port, (int) timeoutMs);
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
		final StringBuilder stdout = new StringBuilder();
		final StringBuilder stderr = new StringBuilder();
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
			collectStreams(resp.document, stdout, stderr, charset);
			final Integer exitCode = doneExitCode(resp.document);
			if (exitCode != null) {
				return new CommandOutput(stdout.toString(), stderr.toString(), exitCode);
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

	/** Send one SOAP request (authenticating the connection on first use) and decrypt the response. */
	private Decoded request(final String soap) throws Exception {
		requestInFlight.set(true);
		try {
			// If the connection was dropped (e.g. the server sent "Connection: close"), the NTLM session
			// bound to it is dead — re-handshake on the fresh connection rather than sending unauthenticated.
			if (session.isAuthenticated() && !transport.isConnected()) {
				session.reset();
			}
			if (!session.isAuthenticated()) {
				authenticate();
			}
			// The Type 3 authorization accompanies the first encrypted payload; later requests on the
			// already-authenticated connection carry no Authorization header.
			final String authorization = pendingAuthorization;
			pendingAuthorization = null;

			final byte[] encrypted = NtlmCrypto.encryptAndSign(session, soap.getBytes(StandardCharsets.UTF_8));
			final HttpTransport.Response resp = transport.post(
				"/wsman",
				encrypted,
				NtlmCrypto.ENCRYPTED_CONTENT_TYPE,
				authorization
			);

			// 200 = success, 500 = SOAP fault (both bodies are encrypted). Anything else is a protocol
			// or authentication failure whose body is not a usable WSMan response.
			if (resp.status != 200 && resp.status != 500) {
				throw new IllegalStateException("WSMan request failed: HTTP " + resp.status);
			}
			return new Decoded(resp.status, decryptResponse(resp));
		} finally {
			requestInFlight.set(false);
		}
	}

	private void authenticate() throws Exception {
		// Request 0: unauthenticated probe (bogus body), mirroring the reference client.
		transport.post("/wsman", PRE_AUTH_BOGUS, SOAP_CONTENT_TYPE, null);

		// Request A: Type 1 under the Negotiate header. No keys yet, so send the bogus placeholder.
		final String type1 = new Type1Message(null, null, TYPE1_FLAGS).getResponse();
		final HttpTransport.Response challenge = transport.post(
			"/wsman",
			PRE_AUTH_BOGUS,
			SOAP_CONTENT_TYPE,
			"Negotiate " + type1
		);
		if (challenge.status != 401) {
			throw new IllegalStateException("Expected HTTP 401 with an NTLM challenge, got HTTP " + challenge.status);
		}
		final String type2 = extractNegotiateToken(challenge);
		if (type2 == null) {
			throw new IllegalStateException(
				"No Negotiate challenge token in response: " + challenge.allHeaders("www-authenticate")
			);
		}

		final Type2Message challengeMessage = new Type2Message(type2);
		final Type3Message type3Message = new Type3Message(
			session.getDomain(),
			session.getWorkstation(),
			session.getUsername(),
			session.getPassword(),
			challengeMessage.getChallenge(),
			challengeMessage.getFlags(),
			challengeMessage.getTarget(),
			challengeMessage.getTargetInfo()
		);
		final String type3 = type3Message.getResponse();
		session.applyKeys(type3Message);
		pendingAuthorization = "Negotiate " + type3;
	}

	/**
	 * Extract the Negotiate/NTLM challenge token from the 401 response, scanning every
	 * {@code WWW-Authenticate} header (order-independent) and tolerating combined challenges.
	 *
	 * @return the base64 token, or {@code null} if no Negotiate challenge carries one
	 */
	private static String extractNegotiateToken(final HttpTransport.Response response) {
		for (final String value : response.allHeaders("www-authenticate")) {
			final Matcher matcher = NEGOTIATE_TOKEN.matcher(value);
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		return null;
	}

	private Document decryptResponse(final HttpTransport.Response resp) throws Exception {
		final String contentType = resp.firstHeader("content-type");
		// Once the NTLM session is authenticated, the seal is the ONLY thing protecting response
		// integrity over plaintext HTTP. A non-encrypted body (from a proxy, a misconfigured server,
		// or an on-path attacker returning a forged HTTP 200/500) has not passed the HMAC check, so it
		// must never be parsed as a trusted WSMan response. request() only reaches here after the
		// handshake, so an encrypted content type is always required.
		if (contentType == null || !contentType.startsWith("multipart/encrypted")) {
			throw new IllegalStateException(
				"Refusing to parse an unencrypted WSMan response after authentication (Content-Type: " + contentType + ")"
			);
		}
		return parse(NtlmCrypto.decrypt(session, resp.body));
	}

	// --- XML helpers --------------------------------------------------------

	private static Document parse(final byte[] xml) throws Exception {
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

	/** Whether the document contains the given WS-Enumeration control element (namespace-scoped). */
	private static boolean hasEnumerationElement(final Document doc, final String localName) {
		return doc.getElementsByTagNameNS(WS_ENUMERATION_NS, localName).getLength() > 0;
	}

	/** First text content of an element matched by both namespace and local name. */
	private static String textNS(final Document doc, final String namespace, final String localName) {
		final NodeList nodes = doc.getElementsByTagNameNS(namespace, localName);
		return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
	}

	private static void collectItems(final Document doc, final List<Map<String, String>> rows) {
		final NodeList items = doc.getElementsByTagNameNS("*", "Items");
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
		final StringBuilder stdout,
		final StringBuilder stderr,
		final Charset charset
	) {
		final NodeList streams = doc.getElementsByTagNameNS("*", "Stream");
		for (int i = 0; i < streams.getLength(); i++) {
			final Element stream = (Element) streams.item(i);
			final String value = stream.getTextContent();
			if (value == null || value.isEmpty()) {
				continue;
			}
			final String decoded = new String(Base64.getDecoder().decode(value), charset);
			if ("stdout".equals(stream.getAttribute("Name"))) {
				stdout.append(decoded);
			} else if ("stderr".equals(stream.getAttribute("Name"))) {
				stderr.append(decoded);
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

	private static String faultSummary(final Decoded resp) {
		final String reason = text(resp.document, "Text");
		final String code = wsmanFaultCode(resp.document);
		return (
			"HTTP " +
			resp.status +
			(code == null ? "" : " (WSManFault " + code + ")") +
			(reason == null ? "" : ": " + reason.trim())
		);
	}

	@Override
	public void close() {
		final String shell = shellId;
		shellId = null;
		// If a request is still in flight, another thread is blocked on this socket — e.g. a command
		// that exceeded its timeout, where Utils.execute abandons the worker mid-Receive while
		// try-with-resources calls close() here. Issuing a graceful shell Delete now would start a
		// second SOAP exchange over the SAME socket and stateful RC4 session, racing the worker
		// (cipher-sequence corruption, crossed responses, or a stall until the socket read timeout).
		// In that case, just hard-close the transport below: it unblocks the worker's read, and the
		// abandoned shell is reaped by the server's IdleTimeout.
		if (shell != null && !requestInFlight.get()) {
			try {
				request(Envelopes.deleteShell(url, shell, timeoutMs));
			} catch (final Exception ignore) {
				// best-effort shell cleanup
			}
		}
		transport.close();
	}
}
