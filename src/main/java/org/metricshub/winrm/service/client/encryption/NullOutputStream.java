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
import java.io.OutputStream;

/**
 *  This OutputStream writes all data to the famous <b>/dev/null</b>.
 *
 *  This output stream has no destination (file/socket etc.) and all
 *  bytes written to it are ignored and lost.
 *
 *
 * PS: from common-io/NullOutputStream. Embedded to avoid to include common-io. 2.11.0
 */
class NullOutputStream extends OutputStream {

	private NullOutputStream() {}

	/**
	 * A singleton.
	 */
	static final NullOutputStream NULL_OUTPUT_STREAM = new NullOutputStream();

	/**
	 * Does nothing - output to <code>/dev/null</code>.
	 * @param b The bytes to write
	 * @param off The start offset
	 * @param len The number of bytes to write
	 */
	@Override
	public void write(final byte[] b, final int off, final int len) {
		//to /dev/null
	}

	/**
	 * Does nothing - output to <code>/dev/null</code>.
	 * @param b The byte to write
	 */
	@Override
	public void write(final int b) {
		//to /dev/null
	}

	/**
	 * Does nothing - output to <code>/dev/null</code>.
	 * @param b The bytes to write
	 * @throws IOException never
	 */
	@Override
	public void write(final byte[] b) throws IOException {
		//to /dev/null
	}
}
