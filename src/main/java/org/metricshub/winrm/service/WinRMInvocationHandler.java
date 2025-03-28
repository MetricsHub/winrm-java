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

import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.WebServiceException;
import jakarta.xml.ws.handler.Handler;
import jakarta.xml.ws.soap.SOAPFaultException;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.TrustManager;
import javax.xml.namespace.QName;
import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapBindingConstants;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.message.Message;
import org.apache.cxf.service.model.ServiceInfo;
import org.apache.cxf.transport.http.HTTPConduitFactory;
import org.apache.cxf.transport.http.asyncclient.AsyncHTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.addressing.WSAddressingFeature;
import org.apache.cxf.ws.addressing.WSAddressingFeature.AddressingResponses;
import org.apache.cxf.ws.addressing.policy.MetadataConstants;
import org.apache.cxf.ws.policy.PolicyConstants;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.KerberosSchemeFactory;
import org.apache.neethi.Policy;
import org.apache.neethi.builders.PrimitiveAssertion;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.service.client.auth.TrustAllX509Manager;
import org.metricshub.winrm.service.client.auth.kerberos.KerberosUtils;
import org.metricshub.winrm.service.client.auth.ntlm.NTCredentialsWithEncryption;
import org.metricshub.winrm.service.client.auth.ntlm.NtlmMasqAsSpnegoSchemeFactory;
import org.metricshub.winrm.service.client.encryption.AsyncHttpEncryptionAwareConduitFactory;
import org.metricshub.winrm.service.client.encryption.DecryptAndVerifyInInterceptor;
import org.metricshub.winrm.service.client.encryption.SignAndEncryptOutInterceptor;

public class WinRMInvocationHandler implements InvocationHandler {

	public static final String WSMAN_SCHEMA_NAMESPACE = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	private static final long PAUSE_TIME_MILLISECONDS = 500;
	private static final int MAX_RETRY = 3;

	private static final URL WSDL_LOCATION_URL =
		WinRMWebServiceClient.class.getClassLoader().getResource("wsdl/WinRM.wsdl");

	private static final QName SERVICE = new QName(WSMAN_SCHEMA_NAMESPACE, "WinRMWebServiceClient");

	private static final QName PORT = new QName(WSMAN_SCHEMA_NAMESPACE, "WinRMPort");

	private static final List<String> CONTENT_TYPE_LIST = Collections.singletonList("application/soap+xml;charset=UTF-8");

	@SuppressWarnings("rawtypes")
	private static final List<Handler> HANDLER_CHAIN = Arrays.asList(new StripShellResponseHandler());

	private static final Registry<AuthSchemeProvider> AUTH_SCHEME_REGISTRY = RegistryBuilder
		.<AuthSchemeProvider>create()
		.register(AuthSchemes.SPNEGO, new NtlmMasqAsSpnegoSchemeFactory())
		.register(AuthSchemes.KERBEROS, new KerberosSchemeFactory(true))
		.build();

	private static final Policy POLICY;

	static {
		POLICY = new Policy();
		POLICY.addAssertion(new PrimitiveAssertion(MetadataConstants.USING_ADDRESSING_2004_QNAME));
	}

	private static final WSAddressingFeature WS_ADDRESSING_FEATURE;

	static {
		WS_ADDRESSING_FEATURE = new WSAddressingFeature();
		WS_ADDRESSING_FEATURE.setResponses(AddressingResponses.ANONYMOUS);
	}

	private static final TLSClientParameters TLS_CLIENT_PARAMETERS;

	static {
		TLS_CLIENT_PARAMETERS = new TLSClientParameters();
		TLS_CLIENT_PARAMETERS.setDisableCNCheck(true);
		// Accept all certificates
		TLS_CLIENT_PARAMETERS.setTrustManagers(new TrustManager[] { new TrustAllX509Manager() });
	}

	private static final Map<CredentialsMapKey, Credentials> CREDENTIALS = new ConcurrentHashMap<>();

	private final WinRMWebService winRMWebService;
	private final WinRMEndpoint winRMEndpoint;
	private final long timeout;
	private final String resourceUri;
	private final Path ticketCache;
	private final Queue<AuthenticationEnum> authenticationsQueue;
	private AuthenticationEnum authentication;
	private Client wsClient;

	/**
	 * WinRMInvocationHandler constructor
	 *
	 * @param winRMEndpoint Endpoint with credentials (mandatory)
	 * @param bus Apache CXF Bus (mandatory)
	 * @param timeout Timeout used for Connection, Connection Request and Receive Request in milliseconds
	 * @param resourceUri The enumerate resource URI
	 * @param ticketCache The Ticket Cache path
	 * @param authentications List of authentications. (mandatory)
	 */
	public WinRMInvocationHandler(
		final WinRMEndpoint winRMEndpoint,
		final Bus bus,
		final long timeout,
		final String resourceUri,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) {
		Utils.checkNonNull(winRMEndpoint, "winRMEndpoint");
		Utils.checkNonNull(bus, "bus");
		Utils.checkNonNull(authentications, "authentications");

		this.winRMEndpoint = winRMEndpoint;
		this.timeout = timeout;
		this.resourceUri = resourceUri;
		this.ticketCache = ticketCache;
		authenticationsQueue = authentications.stream().collect(Collectors.toCollection(LinkedList::new));

		winRMWebService = createWinRMWebService(winRMEndpoint, bus);

		final AuthCredentials authCredentials = computeCredentials(winRMEndpoint, ticketCache, authenticationsQueue);

		authentication = authCredentials.getAuthentication();

		wsClient =
			getWebServiceClient(winRMEndpoint, timeout, resourceUri, winRMWebService, authCredentials.getCredentials());
	}

	public Client getClient() {
		return wsClient;
	}

	@Override
	public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
		Utils.checkNonNull(method, "method");

		try {
			return invokeMethod(method, args);
		} catch (final RetryTgtExpirationException e) {
			// retry with a new TGT in case of current TGT expiration
			authentication = null;

			Credentials credentials;
			try {
				credentials =
					KerberosUtils.createCredentials(winRMEndpoint.getUsername(), winRMEndpoint.getPassword(), ticketCache);

				CREDENTIALS.put(new CredentialsMapKey(winRMEndpoint, ticketCache, AuthenticationEnum.KERBEROS), credentials);
				// Normally that should not happen as any other exception on KERBEROs should had been throw
				// at the first KERBEROS call
			} catch (final Exception e1) {
				if (continueToRetry()) {
					final AuthCredentials authCredentials = computeCredentials(winRMEndpoint, ticketCache, authenticationsQueue);

					authentication = authCredentials.getAuthentication();
					credentials = authCredentials.getCredentials();
				} else {
					throw e1;
				}
			}

			wsClient = getWebServiceClient(winRMEndpoint, timeout, resourceUri, winRMWebService, credentials);

			return invoke(proxy, method, args);
		} catch (final RetryAuthenticationException e) {
			if (continueToRetry()) {
				final AuthCredentials authCredentials = computeCredentials(winRMEndpoint, ticketCache, authenticationsQueue);

				authentication = authCredentials.getAuthentication();

				wsClient =
					getWebServiceClient(winRMEndpoint, timeout, resourceUri, winRMWebService, authCredentials.getCredentials());

				return invoke(proxy, method, args);
			}

			// No more retries
			final Throwable cause = e.getCause();
			if (cause instanceof SOAPFaultException) {
				throw new RuntimeException("KERBEROS with encryption over HTTP is not implemented.", cause);
			}
			throw cause;
		}
	}

	// this function is only needed for the unit testing
	boolean continueToRetry() {
		return !authenticationsQueue.isEmpty();
	}

	Object invokeMethod(final Method method, final Object[] args)
		throws IllegalAccessException, RetryAuthenticationException {
		Throwable firstEx = null;
		int retry = 0;

		while (retry < MAX_RETRY) {
			retry++;

			try {
				return method.invoke(winRMWebService, args);
			} catch (final InvocationTargetException ite) {
				final Throwable targetEx = ite.getTargetException();

				if (targetEx instanceof SOAPFaultException) {
					// Could retry with a different authentication than NTLM
					// because it could be a "WstxEOFException: Unexpected EOF in prolog"
					// due to a KERBEROS with HTTP and AllowUnencrypted=false
					if (winRMEndpoint.getProtocol() == WinRMHttpProtocolEnum.HTTP && authentication != AuthenticationEnum.NTLM) {
						throw new RetryAuthenticationException(targetEx);
					}
					throw (SOAPFaultException) targetEx;
				}

				if (!(targetEx instanceof WebServiceException)) {
					throw new IllegalStateException("Failure when calling " + createCallInfos(method, args), targetEx);
				}

				final WebServiceException wsEx = (WebServiceException) targetEx;

				if (!(wsEx.getCause() instanceof IOException)) {
					throw new RuntimeException(
						"Exception occurred while making WinRM WebService call " + createCallInfos(method, args),
						wsEx
					);
				}

				if (
					wsEx.getCause().getMessage() != null &&
					wsEx.getCause().getMessage().startsWith("Authorization loop detected on Conduit")
				) {
					final RuntimeException authEx = new RuntimeException(
						String.format(
							"Authentication error on %s with user name \"%s\"",
							winRMEndpoint.getEndpoint(),
							winRMEndpoint.getRawUsername()
						)
					);

					// Could be due to a TGT expiration
					if (authentication == AuthenticationEnum.KERBEROS) {
						throw new RetryTgtExpirationException(authEx);
					}
					// Could retry with a different authentication
					throw new RetryAuthenticationException(authEx);
				}

				if (firstEx == null) {
					firstEx = wsEx;
				}

				if (retry < MAX_RETRY) {
					try {
						Utils.sleep(PAUSE_TIME_MILLISECONDS);
					} catch (final InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException(
							"Exception occured while making WinRM WebService call " + createCallInfos(method, args),
							ie
						);
					}
				}
			}
		}

		throw new RuntimeException(
			String.format("failed task \"%s\" after %d attempts", createCallInfos(method, args), MAX_RETRY),
			firstEx
		);
	}

	static String createCallInfos(final Method method, final Object[] args) {
		final String name = method != null && method.getName() != null ? method.getName() : Utils.EMPTY;
		return args == null
			? name
			: Stream
				.concat(Stream.of(name), Stream.of(args))
				.filter(Objects::nonNull)
				.map(Object::toString)
				.collect(Collectors.joining(" "));
	}

	static Credentials createCredentials(
		final WinRMEndpoint winRMEndpoint,
		final AuthenticationEnum authentication,
		final Path ticketCache
	) {
		switch (authentication) {
			case KERBEROS:
				return KerberosUtils.createCredentials(winRMEndpoint.getUsername(), winRMEndpoint.getPassword(), ticketCache);
			case NTLM:
			default:
				final String password = String.valueOf(winRMEndpoint.getPassword());
				return winRMEndpoint.getProtocol() == WinRMHttpProtocolEnum.HTTP
					? new NTCredentialsWithEncryption(winRMEndpoint.getUsername(), password, null, winRMEndpoint.getDomain())
					: new NTCredentials(winRMEndpoint.getUsername(), password, null, winRMEndpoint.getDomain());
		}
	}

	static AuthCredentials computeCredentials(
		final WinRMEndpoint winRMEndpoint,
		final Path ticketCache,
		final Queue<AuthenticationEnum> authenticationsQueue
	) {
		try {
			final AuthenticationEnum authenticationEnum = authenticationsQueue.remove();

			final Credentials credentials = CREDENTIALS.compute(
				new CredentialsMapKey(winRMEndpoint, ticketCache, authenticationEnum),
				(user, cred) -> cred != null ? cred : createCredentials(winRMEndpoint, authenticationEnum, ticketCache)
			);

			return new AuthCredentials(authenticationEnum, credentials);
		} catch (final Exception e) {
			// if there's still retry
			if (!authenticationsQueue.isEmpty()) {
				return computeCredentials(winRMEndpoint, ticketCache, authenticationsQueue);
			}
			throw e;
		}
	}

	static WinRMWebService createWinRMWebService(final WinRMEndpoint winRMEndpoint, final Bus bus) {
		final JaxWsProxyFactoryBean jaxWsProxyFactoryBean = new JaxWsProxyFactoryBean();
		jaxWsProxyFactoryBean.setServiceName(SERVICE);
		jaxWsProxyFactoryBean.setEndpointName(PORT);
		jaxWsProxyFactoryBean.setBus(bus);
		jaxWsProxyFactoryBean.setServiceClass(WinRMWebService.class);
		jaxWsProxyFactoryBean.setAddress(winRMEndpoint.getEndpoint());
		jaxWsProxyFactoryBean.getFeatures().add(WS_ADDRESSING_FEATURE);
		jaxWsProxyFactoryBean.setBindingId(SoapBindingConstants.SOAP12_BINDING_ID);
		jaxWsProxyFactoryBean.getClientFactoryBean().getServiceFactory().setWsdlURL(WSDL_LOCATION_URL);

		return jaxWsProxyFactoryBean.create(WinRMWebService.class);
	}

	static Client getWebServiceClient(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final String enumerateResourceUri,
		final WinRMWebService winRMWebService,
		final Credentials credentials
	) {
		final Client client = ClientProxy.getClient(winRMWebService);

		if (enumerateResourceUri != null) {
			final WSManHeaderInterceptor interceptor = new WSManHeaderInterceptor(enumerateResourceUri);
			client.getOutInterceptors().add(interceptor);
		}

		client.getInInterceptors().add(new DecryptAndVerifyInInterceptor());
		client.getOutInterceptors().add(new SignAndEncryptOutInterceptor());

		// this is different to endpoint properties
		client
			.getEndpoint()
			.getEndpointInfo()
			.setProperty(HTTPConduitFactory.class.getName(), new AsyncHttpEncryptionAwareConduitFactory());

		final ServiceInfo serviceInfo = client.getEndpoint().getEndpointInfo().getService();
		serviceInfo.setProperty("soap.force.doclit.bare", true);

		final BindingProvider bindingProvider = (BindingProvider) winRMWebService;
		bindingProvider.getBinding().setHandlerChain(HANDLER_CHAIN);
		bindingProvider.getRequestContext().put(PolicyConstants.POLICY_OVERRIDE, POLICY);
		bindingProvider.getRequestContext().put("http.autoredirect", true);

		bindingProvider.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, winRMEndpoint.getEndpoint());

		final Map<String, List<String>> headers = new HashMap<>();
		headers.put("Content-Type", CONTENT_TYPE_LIST);

		bindingProvider.getRequestContext().put(Message.PROTOCOL_HEADERS, headers);

		// Setup timeouts
		final HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
		httpClientPolicy.setConnectionTimeout(timeout);
		httpClientPolicy.setConnectionRequestTimeout(timeout);
		httpClientPolicy.setReceiveTimeout(timeout);
		httpClientPolicy.setAllowChunking(false);

		bindingProvider.getRequestContext().put(Credentials.class.getName(), credentials);
		bindingProvider.getRequestContext().put(AuthSchemeProvider.class.getName(), AUTH_SCHEME_REGISTRY);

		final AsyncHTTPConduit asyncHTTPConduit = (AsyncHTTPConduit) client.getConduit();
		asyncHTTPConduit.setClient(httpClientPolicy);
		asyncHTTPConduit.getClient().setAutoRedirect(true);
		asyncHTTPConduit.setTlsClientParameters(TLS_CLIENT_PARAMETERS);

		return client;
	}

	static class RetryAuthenticationException extends Exception {

		private static final long serialVersionUID = 1L;

		RetryAuthenticationException(final Throwable throwable) {
			super(throwable);
		}
	}

	static class RetryTgtExpirationException extends RetryAuthenticationException {

		private static final long serialVersionUID = 1L;

		RetryTgtExpirationException(final Throwable throwable) {
			super(throwable);
		}
	}

	static class AuthCredentials {

		private final AuthenticationEnum authentication;
		private final Credentials credentials;

		AuthCredentials(final AuthenticationEnum authentication, final Credentials credentials) {
			this.authentication = authentication;
			this.credentials = credentials;
		}

		public AuthenticationEnum getAuthentication() {
			return authentication;
		}

		public Credentials getCredentials() {
			return credentials;
		}

		@Override
		public int hashCode() {
			return Objects.hash(authentication, credentials);
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (!(obj instanceof AuthCredentials)) {
				return false;
			}
			final AuthCredentials other = (AuthCredentials) obj;
			return authentication == other.authentication && Objects.equals(credentials, other.credentials);
		}
	}

	static class CredentialsMapKey {

		private final String canonizedRawUsername;
		private final char[] password;
		private final Path ticketCache;
		private final AuthenticationEnum authentication;

		CredentialsMapKey(
			final WinRMEndpoint winRMEndpoint,
			final Path ticketCache,
			final AuthenticationEnum authentication
		) {
			this.ticketCache = ticketCache;
			this.authentication = authentication;

			password = winRMEndpoint.getPassword();
			canonizedRawUsername =
				winRMEndpoint.getRawUsername() != null
					? winRMEndpoint.getRawUsername().replaceAll("\\s", Utils.EMPTY).toUpperCase()
					: null;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + Arrays.hashCode(password);
			result = prime * result + Objects.hash(authentication, canonizedRawUsername, ticketCache);
			return result;
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (!(obj instanceof CredentialsMapKey)) {
				return false;
			}
			final CredentialsMapKey other = (CredentialsMapKey) obj;
			return (
				authentication == other.authentication &&
				Objects.equals(canonizedRawUsername, other.canonizedRawUsername) &&
				Arrays.equals(password, other.password) &&
				Objects.equals(ticketCache, other.ticketCache)
			);
		}
	}
}
