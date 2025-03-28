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

import java.io.OutputStream;
import org.apache.cxf.interceptor.StaxOutInterceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;

/**
 * Not only encrypts if necessary, but must track the payload and make it available to
 * {@link AsyncHttpEncryptionAwareConduit} in case we need to subsequently encrypt.
 *
 * Code from io.cloudsoft.winrm4j.client.encryption.SignAndEncryptOutInterceptor.SignAndEncryptOutInterceptor
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public class SignAndEncryptOutInterceptor extends AbstractPhaseInterceptor<Message> {

	private static final String APPLIED = SignAndEncryptOutInterceptor.class.getSimpleName() + ".APPLIED";

	public SignAndEncryptOutInterceptor() {
		super(Phase.PRE_STREAM);
		// we need to be set before various other output devices, so they write to us
		addBefore(StaxOutInterceptor.class.getName());
	}

	@Override
	public void handleMessage(final Message message) {
		boolean hasApplied = message.containsKey(APPLIED);
		if (!hasApplied) {
			message.put(APPLIED, Boolean.TRUE);
			final OutputStream outputStream = message.getContent(OutputStream.class);
			final EncryptAndSignOutputStream newOut = new EncryptAndSignOutputStream(message, outputStream);
			message.setContent(OutputStream.class, newOut);
			message.setContent(EncryptAndSignOutputStream.class, newOut);
		}
	}
}
