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

import org.apache.cxf.message.Message;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.SignAndEncryptOutInterceptor.ContentWithType
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
class ContentWithType {

	private final String contentType;
	private final byte[] payload;

	private ContentWithType(final String contentType, final byte[] payload) {
		this.contentType = contentType;
		this.payload = payload;
	}

	static ContentWithType of(final Message message, final byte[] payload) {
		return new ContentWithType((String) message.get(Message.CONTENT_TYPE), payload);
	}

	ContentWithType with(final byte[] payload) {
		return new ContentWithType(contentType, payload);
	}

	String getContentType() {
		return contentType;
	}

	byte[] getPayload() {
		return payload;
	}
}
