package org.metricshub.winrm.service;

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

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.soap.SOAPFaultException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.cxf.Bus;
import org.apache.cxf.Bus.BusState;
import org.apache.cxf.BusFactory;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.transport.http.asyncclient.AsyncHTTPConduit;
import org.apache.cxf.transport.http.asyncclient.AsyncHTTPConduitFactory;
import org.apache.cxf.transport.http.asyncclient.AsyncHTTPConduitFactory.UseAsyncPolicy;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.WmiHelper;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.client.WinRMInvocationHandler;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.service.enumeration.Enumerate;
import org.metricshub.winrm.service.enumeration.EnumerateResponse;
import org.metricshub.winrm.service.enumeration.EnumerationContextType;
import org.metricshub.winrm.service.enumeration.FilterType;
import org.metricshub.winrm.service.enumeration.Pull;
import org.metricshub.winrm.service.enumeration.PullResponse;
import org.metricshub.winrm.service.shell.CommandLine;
import org.metricshub.winrm.service.shell.CommandStateType;
import org.metricshub.winrm.service.shell.DesiredStreamType;
import org.metricshub.winrm.service.shell.Receive;
import org.metricshub.winrm.service.shell.ReceiveResponse;
import org.metricshub.winrm.service.shell.Shell;
import org.metricshub.winrm.service.shell.StreamType;
import org.metricshub.winrm.service.transfer.ResourceCreated;
import org.metricshub.winrm.service.wsman.AnyListType;
import org.metricshub.winrm.service.wsman.CommandResponse;
import org.metricshub.winrm.service.wsman.Delete;
import org.metricshub.winrm.service.wsman.Locale;
import org.metricshub.winrm.service.wsman.MixedDataType;
import org.metricshub.winrm.service.wsman.OptionSetType;
import org.metricshub.winrm.service.wsman.OptionType;
import org.metricshub.winrm.service.wsman.SelectorSetType;
import org.metricshub.winrm.service.wsman.SelectorType;
import org.metricshub.winrm.service.wsman.Signal;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class WinRMService implements WindowsRemoteExecutor {

	public static final List<AuthenticationEnum> DEFAULT_AUTHENTICATION = Collections.singletonList(
		AuthenticationEnum.NTLM
	);

	private static final String STDERR = "stderr";
	private static final String STDOUT = "stdout";

	private static final int MAX_ENVELOPE_SIZE = 153600;

	private static final String ENUMERATION_NAMESPACE = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";

	private static final String WSMAN_URI = "http://schemas.microsoft.com/wbem/wsman/1";

	private static final String DIALECT_WQL = WSMAN_URI + "/WQL";

	private static final String SHELL_URI = WSMAN_URI + "/windows/shell";
	private static final String COMMAND_RESOURCE_URI = SHELL_URI + "/cmd";
	private static final String COMMAND_STATE_DONE = SHELL_URI + "/CommandState/Done";
	private static final String TERMINATE_CODE = SHELL_URI + "/signal/terminate";

	private static final QName WSEN_ITEMS_QNAME = new QName(ENUMERATION_NAMESPACE, "Items");

	private static final QName WSMAN_ITEMS_QNAME = new QName(WinRMInvocationHandler.WSMAN_SCHEMA_NAMESPACE, "Items");

	private static final QName WSMAN_END_OF_SEQUENCE_QNAME = new QName(
		WinRMInvocationHandler.WSMAN_SCHEMA_NAMESPACE,
		"EndOfSequence"
	);

	private static final QName WSEN_END_OF_SEQUENCE_QNAME = new QName(ENUMERATION_NAMESPACE, "EndOfSequence");

	private static final QName WSMAN_XML_FRAGMENT_QNAME = new QName(
		WinRMInvocationHandler.WSMAN_SCHEMA_NAMESPACE,
		"XmlFragment"
	);

	private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();

	/**
	 * If no output is available before the wsman:OperationTimeout expires, the server MUST return a WSManFault with
	 *  the Code attribute equal to "2150858793"
	 * https://msdn.microsoft.com/en-us/library/cc251676.aspx
	 */
	private static final String WSMAN_FAULT_CODE_OPERATION_TIMEOUT_EXPIRED = "2150858793";

	/**
	 * Example response:
	 *   [truncated]The request for the Windows Remote Shell with ShellId xxxx-yyyy-ccc... failed because the shell
	 *   was not found on the server.
	 *   Possible causes are: the specified ShellId is incorrect or the shell no longer exist
	 */
	private static final String WSMAN_FAULT_CODE_SHELL_WAS_NOT_FOUND = "2150858843";

	private static final Locale LOCALE;

	static {
		LOCALE = new Locale();
		LOCALE.setLang(java.util.Locale.US.toLanguageTag());
	}

	private static final OptionSetType OPTION_SET_CREATE;

	static {
		final OptionType optNoProfile = new OptionType();
		optNoProfile.setName("WINRS_NOPROFILE");
		optNoProfile.setValue("true");

		final OptionType optCodepage = new OptionType();
		optCodepage.setName("WINRS_CODEPAGE");
		optCodepage.setValue("437");

		OPTION_SET_CREATE = new OptionSetType();
		OPTION_SET_CREATE.getOption().add(optNoProfile);
		OPTION_SET_CREATE.getOption().add(optCodepage);
	}

	private static final OptionSetType OPTION_SET_COMMAND;

	static {
		final OptionType optConsoleModeStdin = new OptionType();
		optConsoleModeStdin.setName("WINRS_CONSOLEMODE_STDIN");
		optConsoleModeStdin.setValue("true");

		final OptionType optSkipCmdShell = new OptionType();
		optSkipCmdShell.setName("WINRS_SKIP_CMD_SHELL");
		optSkipCmdShell.setValue("false");

		OPTION_SET_COMMAND = new OptionSetType();
		OPTION_SET_COMMAND.getOption().add(optConsoleModeStdin);
		OPTION_SET_COMMAND.getOption().add(optSkipCmdShell);
	}

	private static final ConcurrentHashMap<WinRMEndpoint, WinRMService> CONNECTIONS_CACHE = new ConcurrentHashMap<>();

	private final AtomicInteger useCount = new AtomicInteger(1);

	private final WinRMEndpoint winRMEndpoint;
	private final Bus bus;
	private final WinRMWebService cmdWS;
	private final WinRMWebService wqlWS;
	private final Client cmdClient;
	private final Client wqlClient;
	private final String strTimeout;

	private SelectorSetType shellSelector = null;

	/**
	 * The WinRMService constructor.
	 *
	 * @param winRMEndpoint Endpoint with credentials
	 * @param bus Apache CXF Bus
	 * @param cmdInvocation The WinRM web service for executing commands
	 * @param wqlInvocation The WinRM web service for executing WQL queries
	 * @param timeout Timeout in milliseconds
	 */
	private WinRMService(
		final WinRMEndpoint winRMEndpoint,
		final Bus bus,
		final WinRMInvocationHandler cmdInvocation,
		final WinRMInvocationHandler wqlInvocation,
		final long timeout
	) {
		this.winRMEndpoint = winRMEndpoint;
		this.bus = bus;
		this.cmdWS = createProxyService(cmdInvocation);
		this.wqlWS = createProxyService(wqlInvocation);
		this.cmdClient = cmdInvocation.getClient();
		this.wqlClient = wqlInvocation.getClient();

		final BigDecimal timeoutSec = BigDecimal.valueOf(timeout).divide(BigDecimal.valueOf(1000));
		final DecimalFormat decimalFormat = new DecimalFormat("PT#.###S", new DecimalFormatSymbols(java.util.Locale.ROOT));
		this.strTimeout = decimalFormat.format(timeoutSec);
	}

	/**
	 * Create a WinRMService instance
	 *
	 * @param winRMEndpoint Endpoint with credentials (mandatory)
	 * @param timeout Timeout used for Connection, Connection Request and Receive Request
	 * in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @param ticketCache The Ticket Cache path
	 * @param authentications List of authentications. only NTLM if absent
	 *
	 * @return WinRMService instance
	 *
	 * @throws WinRMException For any problem encountered
	 */
	public static WinRMService createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws WinRMException {
		Utils.checkNonNull(winRMEndpoint, "winRMEndpoint");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		final List<AuthenticationEnum> normalizedAuthentications = authentications == null
			? DEFAULT_AUTHENTICATION
			: authentications.stream().distinct().collect(Collectors.toList());

		try {
			return CONNECTIONS_CACHE.compute(
				winRMEndpoint,
				(key, win) -> {
					if (win == null) {
						final Bus bus = BusFactory.newInstance().createBus();

						// Needed to be async to force the use of Apache HTTP Components client.
						// Details at http://cxf.apache.org/docs/asynchronous-client-http-transport.html.
						// Apache HTTP Components needed to support NTLM authentication.
						bus.getProperties().put(AsyncHTTPConduit.USE_ASYNC, Boolean.TRUE);
						bus.getProperties().put(AsyncHTTPConduitFactory.USE_POLICY, UseAsyncPolicy.ALWAYS);

						final WinRMInvocationHandler cmdInvocation = createWinRMInvocationHandlerInstance(
							winRMEndpoint,
							bus,
							timeout,
							null,
							ticketCache,
							normalizedAuthentications
						);

						final WinRMInvocationHandler wqlInvocation = createWinRMInvocationHandlerInstance(
							winRMEndpoint,
							bus,
							timeout,
							String.format("%s/wmi/%s/*", WSMAN_URI, winRMEndpoint.getNamespace()),
							ticketCache,
							normalizedAuthentications
						);

						return new WinRMService(winRMEndpoint, bus, cmdInvocation, wqlInvocation, timeout);
					} else {
						synchronized (win) {
							win.incrementUseCount();

							return win;
						}
					}
				}
			);
		} catch (final RuntimeException e) {
			if (e.getCause() != null) {
				final String message = e.getMessage() != null
					? String.format(
						"%s\n%s: %s",
						e.getMessage(),
						e.getCause().getClass().getSimpleName(),
						e.getCause().getMessage()
					)
					: String.format("%s: %s", e.getCause().getClass().getSimpleName(), e.getCause().getMessage());
				throw new WinRMException(e.getCause(), message);
			}

			throw new WinRMException(e.getMessage());
		}
	}

	public int getUseCount() {
		return useCount.get();
	}

	/**
	 * @return whether this WbemServices instance is connected and usable
	 */
	public boolean isConnected() {
		return getUseCount() > 0;
	}

	void incrementUseCount() {
		useCount.incrementAndGet();
	}

	/**
	 * Check if it's connected. If not, throw an IllegalStateException.
	 */
	public void checkConnectedFirst() {
		if (!isConnected()) {
			throw new IllegalStateException("This instance has been closed and a new one must be created.");
		}
	}

	@Override
	public void close() {
		if (useCount.decrementAndGet() == 0) {
			CONNECTIONS_CACHE.remove(winRMEndpoint);

			if (shellSelector != null) {
				cmdWS.delete(new Delete(), COMMAND_RESOURCE_URI, MAX_ENVELOPE_SIZE, strTimeout, LOCALE, shellSelector);

				shellSelector = null;
			}

			if (cmdClient != null) {
				cmdClient.destroy();
			}

			if (wqlClient != null) {
				wqlClient.destroy();
			}

			if (bus != null && bus.getState() != BusState.SHUTDOWN) {
				bus.shutdown(true);
			}
		}
	}

	@Override
	public WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Charset charset,
		final long timeout
	) throws WinRMException, TimeoutException {
		Utils.checkNonNull(command, "command");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		checkConnectedFirst();

		try {
			return Utils.execute(
				() -> {
					if (getShellSelector() == null) {
						create(workingDirectory);
					}

					try {
						final StringWriter stdout = new StringWriter();
						final StringWriter stderr = new StringWriter();
						final Charset cs = charset != null ? charset : StandardCharsets.UTF_8;

						final long start = Utils.getCurrentTimeMillis();
						final int statusCode = execute(command, stdout, stderr, cs);
						final float executionTime = (Utils.getCurrentTimeMillis() - start) / 1000.0f;

						return new WindowsRemoteCommandResult(stdout.toString(), stderr.toString(), executionTime, statusCode);
					} catch (final WinRMException e) {
						throw new RuntimeException(e);
					}
				},
				timeout
			);
		} catch (final InterruptedException | ExecutionException e) {
			if (e.getCause() != null) {
				throw new WinRMException(e.getCause(), e.getCause().getMessage());
			}
			throw new WinRMException(e);
		}
	}

	@Override
	public List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout)
		throws WinRMException, WqlQuerySyntaxException, TimeoutException {
		Utils.checkNonNull(wqlQuery, "wqlQuery");
		if (!WmiHelper.isValidWql(wqlQuery)) {
			throw new WqlQuerySyntaxException(wqlQuery);
		}
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		checkConnectedFirst();

		try {
			return Utils.execute(
				() -> {
					final List<Node> nodes = new ArrayList<>();

					final EnumerateResponse enumerateResponse = enumerate(wqlQuery);

					final boolean endOfSequence = getItemsFrom(enumerateResponse, nodes);
					if (!endOfSequence) {
						final String nextContextId = getContextIdFrom(enumerateResponse.getEnumerationContext());
						pull(nextContextId, nodes);
					}

					return nodes.stream().map(WinRMService::convertRow).collect(Collectors.toList());
				},
				timeout
			);
		} catch (final InterruptedException | ExecutionException e) {
			if (e.getCause() != null) {
				throw new WinRMException(e.getCause(), e.getCause().getMessage());
			}
			throw new WinRMException(e);
		}
	}

	public static WinRMInvocationHandler createWinRMInvocationHandlerInstance(
		final WinRMEndpoint winRMEndpoint,
		final Bus bus,
		final long timeout,
		final String resourceUri,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) {
		return new WinRMInvocationHandler(winRMEndpoint, bus, timeout, resourceUri, ticketCache, authentications);
	}

	private static WinRMWebService createProxyService(final WinRMInvocationHandler winRMInvocationHandler) {
		return (WinRMWebService) Proxy.newProxyInstance(
			WinRMWebService.class.getClassLoader(),
			new Class[] { WinRMWebService.class, BindingProvider.class },
			winRMInvocationHandler
		);
	}

	public EnumerateResponse enumerate(final String wqlQuery) {
		final FilterType filterType = new FilterType();
		filterType.setDialect(DIALECT_WQL);
		filterType.getContent().add(wqlQuery);

		final Enumerate body = new Enumerate();
		body.setFilter(filterType);

		return wqlWS.enumerate(body);
	}

	public String pull(final String contextId, final List<Node> nodes) throws WinRMException {
		final EnumerationContextType enumContext = new EnumerationContextType();
		enumContext.getContent().add(contextId);

		final Pull body = new Pull();
		body.setEnumerationContext(enumContext);

		final PullResponse response = wqlWS.pull(body);

		if (response == null) {
			throw new WinRMException(String.format("Pull failed for context id: %s", contextId));
		}

		final boolean endOfSequence = getItemsFrom(response, nodes);
		final String nextContextId = response.getEnumerationContext() == null
			? null // The PullResponse will not contain an EnumerationContext if EndOfSequence is set
			: getContextIdFrom(response.getEnumerationContext());

		return endOfSequence ? nextContextId : pull(nextContextId, nodes); // If we're pulling recursively, and we haven't hit the last element, continue pulling
	}

	public ResourceCreated create(final String workingDirectory) {
		final Shell shell = new Shell();
		shell.getInputStreams().add("stdin");
		shell.getOutputStreams().add(STDOUT);
		shell.getOutputStreams().add(STDERR);

		if (Utils.isNotBlank(workingDirectory)) {
			shell.setWorkingDirectory(workingDirectory);
		}

		final ResourceCreated resourceCreated = cmdWS.create(
			shell,
			COMMAND_RESOURCE_URI,
			MAX_ENVELOPE_SIZE,
			strTimeout,
			LOCALE,
			OPTION_SET_CREATE
		);

		final String shellId = getShellId(resourceCreated);

		shellSelector = new SelectorSetType();
		final SelectorType selectorType = new SelectorType();
		selectorType.setName("ShellId");
		selectorType.getContent().add(shellId);
		shellSelector.getSelector().add(selectorType);

		return resourceCreated;
	}

	public int execute(final String command, final Writer out, final Writer err, final Charset charset)
		throws WinRMException {
		final CommandLine body = new CommandLine();
		body.setCommand(command);

		final CommandResponse commandResponse = cmdWS.command(
			body,
			COMMAND_RESOURCE_URI,
			MAX_ENVELOPE_SIZE,
			strTimeout,
			LOCALE,
			shellSelector,
			OPTION_SET_COMMAND
		);

		final String commandId = commandResponse.getCommandId();

		try {
			return receiveCommand(commandId, out, err, charset);
		} finally {
			try {
				final Signal signal = new Signal();
				signal.setCommandId(commandId);
				signal.setCode(TERMINATE_CODE);

				cmdWS.signal(signal, COMMAND_RESOURCE_URI, MAX_ENVELOPE_SIZE, strTimeout, LOCALE, shellSelector);
			} catch (final SOAPFaultException soapFault) {
				assertFaultCode(soapFault, WSMAN_FAULT_CODE_SHELL_WAS_NOT_FOUND, true);
			}
		}
	}

	private int receiveCommand(final String commandId, final Writer out, final Writer err, final Charset charset)
		throws WinRMException {
		while (true) {
			final DesiredStreamType stream = new DesiredStreamType();
			stream.setCommandId(commandId);
			stream.setValue("stdout stderr");

			final Receive receive = new Receive();
			receive.setDesiredStream(stream);

			try {
				final ReceiveResponse receiveResponse = cmdWS.receive(
					receive,
					COMMAND_RESOURCE_URI,
					MAX_ENVELOPE_SIZE,
					strTimeout,
					LOCALE,
					shellSelector
				);
				getStreams(receiveResponse, out, err, charset);

				final CommandStateType state = receiveResponse.getCommandState();
				if (COMMAND_STATE_DONE.equals(state.getState())) {
					return state.getExitCode().intValue();
				}
			} catch (final SOAPFaultException soapFault) {
				// If such Exception which has a code 2150858793 the client is expected to again trigger immediately
				// a receive request. https://msdn.microsoft.com/en-us/library/cc251676.aspx
				assertFaultCode(soapFault, WSMAN_FAULT_CODE_OPERATION_TIMEOUT_EXPIRED, false);
			}
		}
	}

	private static Map<String, Object> convertRow(final Node node) {
		return IntStream
			.range(0, node.getChildNodes().getLength())
			.mapToObj(node.getChildNodes()::item)
			.filter(Objects::nonNull)
			.collect(HashMap::new, (map, child) -> map.put(child.getLocalName(), child.getTextContent()), HashMap::putAll);
	}

	private static String getShellId(final ResourceCreated resourceCreated) {
		final XPath xpath = XPathFactory.newInstance().newXPath();

		for (final Element element : resourceCreated.getAny()) {
			try {
				final String shellId = xpath.evaluate("//*[local-name()='Selector' and @Name='ShellId']", element);
				if (shellId != null && !shellId.isEmpty()) {
					return shellId;
				}
			} catch (final XPathExpressionException e) {
				throw new IllegalStateException(e);
			}
		}
		throw new IllegalStateException("Shell ID not fount in " + resourceCreated);
	}

	private static void assertFaultCode(final SOAPFaultException soapFault, final String code, final boolean retry) {
		try {
			final NodeList faultDetails = soapFault.getFault().getDetail().getChildNodes();

			for (int i = 0; i < faultDetails.getLength(); i++) {
				final Node item = faultDetails.item(i);

				if ("WSManFault".equals(item.getLocalName())) {
					if (retry && code.equals(item.getAttributes().getNamedItem("Code").getNodeValue())) {
						return;
					}
					throw soapFault;
				}
			}
			throw soapFault;
		} catch (final NullPointerException e) {
			throw soapFault;
		}
	}

	private void getStreams(
		final ReceiveResponse receiveResponse,
		final Writer out,
		final Writer err,
		final Charset charset
	) throws WinRMException {
		final List<StreamType> streams = receiveResponse.getStream();
		for (final StreamType streamType : streams) {
			final byte[] value = streamType.getValue();
			if (value == null) {
				continue;
			}

			writeStd(out, STDOUT, streamType, value, charset);
			writeStd(err, STDERR, streamType, value, charset);
		}
	}

	private void writeStd(
		final Writer std,
		final String name,
		final StreamType streamType,
		final byte[] value,
		final Charset charset
	) throws WinRMException {
		if (std == null || !name.equals(streamType.getName())) {
			return;
		}

		try {
			if (value.length > 0) {
				std.write(new String(value, charset));
				std.flush();
			}

			if (streamType.isEnd() != null && streamType.isEnd().booleanValue()) {
				std.close();
			}
		} catch (final IOException e) {
			throw new WinRMException(e);
		}
	}

	/**
	 * Retrieves the list of items from the given response, adding them to the given
	 * list and returns true if the response contains an 'end-of-sequence' marker.
	 * @throws WinRMException
	 */
	public boolean getItemsFrom(final EnumerateResponse response, final List<Node> items) throws WinRMException {
		for (final Object object : response.getAny()) {
			if (object instanceof JAXBElement) {
				final JAXBElement<?> jaxbElement = (JAXBElement<?>) object;

				if (WSEN_ITEMS_QNAME.equals(jaxbElement.getName()) || WSMAN_ITEMS_QNAME.equals(jaxbElement.getName())) {
					if (jaxbElement.isNil()) {
						// No items
					} else if (jaxbElement.getValue() instanceof AnyListType) {
						// some items
						final AnyListType itemList = (AnyListType) jaxbElement.getValue();
						for (final Object item : itemList.getAny()) {
							final Node node = toNode(item)
								.orElseThrow(() ->
									new WinRMException(
										"Unsupported element of type %s in EnumerateResponse: %s",
										object.getClass(),
										object
									)
								);

							items.add(node);
						}
					} else {
						throw new WinRMException(
							"Unsupported value in EnumerateResponse Items: %s of type: %s",
							jaxbElement.getValue(),
							jaxbElement.getValue().getClass()
						);
					}
				} else if (
					WSEN_END_OF_SEQUENCE_QNAME.equals(jaxbElement.getName()) ||
					WSMAN_END_OF_SEQUENCE_QNAME.equals(jaxbElement.getName())
				) {
					return true;
				} else {
					throw new WinRMException(
						"Unsupported element in EnumerateResponse: %s with name: %s",
						jaxbElement,
						jaxbElement.getName()
					);
				}
			} else if (object instanceof Node) {
				final Node node = (Node) object;

				if (
					(WSEN_END_OF_SEQUENCE_QNAME.getNamespaceURI().equals(node.getNamespaceURI()) &&
						WSEN_END_OF_SEQUENCE_QNAME.getLocalPart().equals(node.getLocalName())) ||
					(WSMAN_END_OF_SEQUENCE_QNAME.getNamespaceURI().equals(node.getNamespaceURI()) &&
						WSMAN_END_OF_SEQUENCE_QNAME.getLocalPart().equals(node.getLocalName()))
				) {
					return true;
				}
				throw new WinRMException(
					"Unsupported node in EnumerateResponse: %s with namespace: %s",
					node.toString(),
					node.getNamespaceURI()
				);
			} else {
				throw new WinRMException(
					"Unsupported element in EnumerateResponse: %s, with type: %s",
					object,
					object != null ? object.getClass() : null
				);
			}
		}

		return false;
	}

	private static boolean getItemsFrom(final PullResponse response, final List<Node> items) throws WinRMException {
		for (final Object item : response.getItems().getAny()) {
			final Node node = toNode(item)
				.orElseThrow(() ->
					new WinRMException(
						"The pull response contains an unsupported item %s of type %s",
						item,
						item != null ? item.getClass() : null
					)
				);

			items.add(node);
		}
		return response.getEndOfSequence() != null;
	}

	private static Optional<Node> toNode(final Object item) throws WinRMException {
		if (item instanceof Node) {
			return Optional.of((Node) item);
		}

		if (item instanceof JAXBElement) {
			final JAXBElement<?> nestedElement = (JAXBElement<?>) item;
			if (
				WSMAN_XML_FRAGMENT_QNAME.equals(nestedElement.getName()) &&
				!nestedElement.isNil() &&
				nestedElement.getValue() instanceof MixedDataType
			) {
				// Create a new document/node that contains the elements within the fragment
				final Document document = createNewDocument();
				final Element rootElement = document.createElementNS(
					WSMAN_XML_FRAGMENT_QNAME.getNamespaceURI(),
					WSMAN_XML_FRAGMENT_QNAME.getLocalPart()
				);
				document.appendChild(rootElement);

				final MixedDataType mixed = (MixedDataType) nestedElement.getValue();
				for (final Object nestedItem : mixed.getContent()) {
					if (nestedItem instanceof String) {
						// Skip over whitespace
					} else if (nestedItem instanceof Node) {
						// Node's can't belong to two different documents, so we need to import it first
						final Node nestedNode = document.importNode((Node) nestedItem, true);
						rootElement.appendChild(nestedNode);
					} else {
						throw new WinRMException(
							"Unsupported element of type %s in XmlFragment: %s",
							nestedItem.getClass(),
							nestedItem
						);
					}
				}
				return Optional.of(rootElement);
			}
		}
		return Optional.empty();
	}

	private static Document createNewDocument() throws WinRMException {
		// The DocumentBuilderFactory provides no guarantees on thread safety
		// so we lock it in order to avoid creating new or separate instances per thread
		synchronized (DOCUMENT_BUILDER_FACTORY) {
			try {
				return DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().newDocument();
			} catch (final ParserConfigurationException e) {
				throw new WinRMException(e);
			}
		}
	}

	public String getContextIdFrom(final EnumerationContextType context) throws WinRMException {
		// The content of the EnumerationContext should contain a single string, the context id
		if (context == null || context.getContent() == null) {
			throw new WinRMException("EnumerationContext %s has no content.", context);
		}

		if (context.getContent().isEmpty()) {
			// The EnumerationContext can be empty if we issue an optimized enumeration
			// and all of the records are immediately returned
			return null;
		}

		if (context.getContent().size() == 1) {
			final Object content = context.getContent().get(0);
			if (content instanceof String) {
				return (String) content;
			}
			throw new WinRMException("Unsupported EnumerationContext content: %s", content);
		}

		throw new WinRMException(
			"EnumerationContext contains too many elements, expected: 1 actual: %d",
			context.getContent().size()
		);
	}

	public SelectorSetType getShellSelector() {
		return shellSelector;
	}

	@Override
	public String getHostname() {
		return winRMEndpoint.getHostname();
	}

	@Override
	public String getUsername() {
		return winRMEndpoint.getRawUsername();
	}

	@Override
	public char[] getPassword() {
		return winRMEndpoint.getPassword();
	}
}
