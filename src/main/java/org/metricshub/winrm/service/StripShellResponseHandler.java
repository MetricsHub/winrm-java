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

import static jakarta.xml.ws.handler.MessageContext.WSDL_OPERATION;

import jakarta.xml.soap.SOAPBody;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPEnvelope;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.handler.soap.SOAPHandler;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import javax.xml.namespace.QName;

/**
 * Code from io.cloudsoft.winrm4j.client.StripShellResponseHandler
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class StripShellResponseHandler implements SOAPHandler<SOAPMessageContext> {

	@Override
	public boolean handleMessage(final SOAPMessageContext context) {
		final Boolean messageOutbound = (Boolean) context.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY);
		if (messageOutbound != null && messageOutbound.booleanValue()) {
			return true;
		}

		final QName action = (QName) context.get(WSDL_OPERATION);
		if (action != null && !"Create".equals(action.getLocalPart())) {
			return true;
		}

		final Iterator<?> childIterator = getBodyChildren(context);
		while (childIterator.hasNext()) {
			final Object node = childIterator.next();

			if (node instanceof SOAPElement) {
				final SOAPElement soapElement = (SOAPElement) node;
				if ("Shell".equals(soapElement.getLocalName())) {
					childIterator.remove();
				}
			}
		}

		return true;
	}

	private Iterator<?> getBodyChildren(final SOAPMessageContext context) {
		try {
			final SOAPEnvelope envelope = context.getMessage().getSOAPPart().getEnvelope();
			final SOAPBody body = envelope.getBody();

			return body.getChildElements();
		} catch (final SOAPException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public boolean handleFault(final SOAPMessageContext context) {
		return true;
	}

	@Override
	public void close(final MessageContext context) {
		// Do nothing
	}

	@Override
	public Set<QName> getHeaders() {
		return Collections.emptySet();
	}
}
