package org.sentrysoftware.winrm.service.client.encryption;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 Sentry Software
 * ჻჻჻჻჻჻
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * ╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱
 */

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.message.Message;
import org.apache.http.auth.Credentials;

import org.sentrysoftware.winrm.service.client.auth.ntlm.NTCredentialsWithEncryption;

/**
 * Code from io.cloudsoft.winrm4j.client.encryption.SignAndEncryptOutInterceptor.EncryptAndSignOutputStream
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 */
class EncryptAndSignOutputStream extends CachedOutputStream {

	private final CachedOutputStream unencrypted;
	private ContentWithType unencryptedResult = null;
	private ContentWithType encrypted = null;
	private final Message message;

	private OutputStream wrapped;

	private NTCredentialsWithEncryption credentials;

	public EncryptAndSignOutputStream(final Message message, final OutputStream outputStream) {
		super();

		this.message = message;
		wrapped = outputStream;
		unencrypted = new CachedOutputStream();

		final Object creds = message.get(Credentials.class.getName());
		if (creds instanceof NTCredentialsWithEncryption) {
			credentials = (NTCredentialsWithEncryption) creds;
		}
	}

	@Override
	public void resetOut(final OutputStream outputStream, final boolean copyOldContent) throws IOException {
		super.resetOut(outputStream, copyOldContent);
	}

	@Override
	public void close() throws IOException {
		super.close();
		unencrypted.write(getBytes());
		currentStream = NullOutputStream.NULL_OUTPUT_STREAM;

		if (wrapped!=null) {
			processAndShip(wrapped);
			wrapped.close();
		}
	}

	private synchronized ContentWithType getEncrypted() {
		try {
			if (encrypted == null) {
				final byte[] bytesEncryptedAndSigned = NtlmEncryptionUtils.of(credentials)
						.encryptAndSign(message, unencrypted.getBytes());

				encrypted = ContentWithType.of(message, bytesEncryptedAndSigned);
			}
			return encrypted;

		} catch (final IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] getUnencrypted() {
		try {
			return unencrypted.getBytes();

		} catch (final IOException e) {
			throw new IllegalStateException(e);
		}
	}

	synchronized ContentWithType getAppropriate() {
		if (unencryptedResult == null) {
			unencryptedResult = ContentWithType.of(message, null);
		}

		if (credentials == null || !credentials.isAuthenticated()) {
			if (encrypted != null) {
				// clear any previous encryption if no longer valid
				encrypted = null;
			}

			return credentials != null && !credentials.isAuthenticated() ?
					unencryptedResult.with(AsyncHttpEncryptionAwareConduit.PRE_AUTH_BOGUS_PAYLOAD) :
					unencryptedResult.with(getUnencrypted());
		}

		return getEncrypted();
	}

	private void processAndShip(final OutputStream output) throws IOException {
		output.write(getAppropriate().getPayload());
		output.close();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
				+ Objects.hash(credentials, encrypted, message, unencrypted, unencryptedResult, wrapped);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (!(obj instanceof EncryptAndSignOutputStream))
			return false;
		EncryptAndSignOutputStream other = (EncryptAndSignOutputStream) obj;
		return Objects.equals(credentials, other.credentials) && Objects.equals(encrypted, other.encrypted)
				&& Objects.equals(message, other.message) && Objects.equals(unencrypted, other.unencrypted)
				&& Objects.equals(unencryptedResult, other.unencryptedResult) && Objects.equals(wrapped, other.wrapped);
	}
}
