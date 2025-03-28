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

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.protocol.HTTP;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.AsyncHttpEncryptionAwareConduit.EncryptionAwareHttpEntity
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
public abstract class EncryptionAwareHttpEntity extends BasicHttpEntity {

	public void refreshHeaders(final HttpEntityEnclosingRequest request) {
		final ContentWithType appropriate = getAppropriate();
		setContentLength(appropriate.getPayload().length);

		request.setHeader(HTTP.CONTENT_LEN, String.valueOf(appropriate.getPayload().length));
		request.setHeader(HTTP.CONTENT_TYPE, appropriate.getContentType());
	}

	protected abstract ContentWithType getAppropriate();
}
