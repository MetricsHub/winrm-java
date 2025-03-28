package org.metricshub.winrm.service.client.encryption;

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

import java.io.IOException;
import java.util.Map;
import org.apache.cxf.Bus;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transport.http.asyncclient.AsyncHTTPConduitFactory;
import org.apache.cxf.ws.addressing.EndpointReferenceType;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.AsyncHttpEncryptionAwareConduitFactory
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class AsyncHttpEncryptionAwareConduitFactory extends AsyncHTTPConduitFactory {

	public AsyncHttpEncryptionAwareConduitFactory() {
		super((Map<String, Object>) null);
	}

	@Override
	public HTTPConduit createConduit(final Bus bus, final EndpointInfo localInfo, final EndpointReferenceType target)
		throws IOException {
		return isShutdown() ? null : new AsyncHttpEncryptionAwareConduit(bus, localInfo, target, this);
	}
}
