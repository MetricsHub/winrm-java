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
import jakarta.xml.bind.JAXBException;
import java.util.List;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.binding.soap.interceptor.SoapPreProtocolOutInterceptor;
import org.apache.cxf.headers.Header;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.phase.Phase;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.service.wsman.AttributableURI;
import org.metricshub.winrm.service.wsman.ObjectFactory;

/**
 * Code from org.opennms.core.wsman.cxf.WSManHeaderInterceptor
 * release 1.2.3 @link https://github.com/OpenNMS/wsman
 */
public class WSManHeaderInterceptor extends AbstractSoapInterceptor {

	private static final JAXBDataBinding ATTRIBUTABLE_URI_JAXB_DATA_BINDING;

	static {
		try {
			ATTRIBUTABLE_URI_JAXB_DATA_BINDING = new JAXBDataBinding(AttributableURI.class);
		} catch (final JAXBException e) {
			throw new RuntimeException("Failed to create JAXBDataBinding for: AttributableURI" + AttributableURI.class, e);
		}
	}

	private final String resourceUri;

	public WSManHeaderInterceptor(final String resourceUri) {
		super(Phase.POST_LOGICAL);
		addAfter(SoapPreProtocolOutInterceptor.class.getName());

		Utils.checkNonNull(resourceUri, "resourceUri");

		this.resourceUri = resourceUri;
	}

	@Override
	public void handleMessage(final SoapMessage message) throws Fault {
		final JAXBElement<String> resourceURI = new ObjectFactory().createResourceURI(resourceUri);

		final List<Header> headers = message.getHeaders();
		headers.add(new Header(resourceURI.getName(), resourceURI, ATTRIBUTABLE_URI_JAXB_DATA_BINDING));

		message.put(Header.HEADER_LIST, headers);
	}
}
