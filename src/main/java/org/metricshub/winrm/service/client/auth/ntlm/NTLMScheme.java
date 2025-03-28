// copy of code from apache-httpclient 4.5.13 package org.apache.http.impl.auth
// changes:
// - package name, this header, imports
// - gather NTLM signing key and attach to context

/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License");
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
package org.metricshub.winrm.service.client.auth.ntlm;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.InvalidCredentialsException;
import org.apache.http.auth.MalformedChallengeException;
import org.apache.http.auth.NTCredentials;
import org.apache.http.impl.auth.AuthSchemeBase;
import org.apache.http.message.BufferedHeader;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.CharArrayBuffer;
import org.metricshub.winrm.Utils;

/**
 * NTLM is a proprietary authentication scheme developed by Microsoft
 * and optimized for Windows platforms.
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMScheme
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public class NTLMScheme extends AuthSchemeBase {

	private enum State {
		UNINITIATED,
		CHALLENGE_RECEIVED,
		MSG_TYPE1_GENERATED,
		MSG_TYPE2_RECEVIED,
		MSG_TYPE3_GENERATED,
		FAILED
	}

	private final NTLMEngine engine;

	private State state;
	private String challenge;

	public NTLMScheme(final NTLMEngine engine) {
		super();
		Utils.checkNonNull(engine, "engine");
		this.engine = engine;
		state = State.UNINITIATED;
		challenge = null;
	}

	@Override
	public String getSchemeName() {
		return "ntlm";
	}

	@Override
	public String getParameter(final String name) {
		// String parameters not supported
		return null;
	}

	@Override
	public String getRealm() {
		// NTLM does not support the concept of an authentication realm
		return null;
	}

	@Override
	public boolean isConnectionBased() {
		return true;
	}

	@Override
	protected void parseChallenge(final CharArrayBuffer buffer, final int beginIndex, final int endIndex)
		throws MalformedChallengeException {
		challenge = buffer.substringTrimmed(beginIndex, endIndex);
		if (challenge.isEmpty()) {
			if (state == State.UNINITIATED) {
				state = State.CHALLENGE_RECEIVED;
			} else {
				state = State.FAILED;
			}
		} else {
			if (state.compareTo(State.MSG_TYPE1_GENERATED) < 0) {
				state = State.FAILED;
				throw new MalformedChallengeException("Out of sequence NTLM response message");
			} else if (state == State.MSG_TYPE1_GENERATED) {
				state = State.MSG_TYPE2_RECEVIED;
			}
		}
	}

	@Override
	public Header authenticate(final Credentials credentials, final HttpRequest request) throws AuthenticationException {
		NTCredentials ntcredentials = null;
		try {
			ntcredentials = (NTCredentials) credentials;
		} catch (final ClassCastException e) {
			throw new InvalidCredentialsException(
				"Credentials cannot be used for NTLM authentication: " + credentials.getClass().getName()
			);
		}
		String response = null;
		if (state == State.FAILED) {
			throw new AuthenticationException("NTLM authentication failed");
		} else if (state == State.CHALLENGE_RECEIVED) {
			response = this.engine.generateType1Msg(ntcredentials.getDomain(), ntcredentials.getWorkstation());
			state = State.MSG_TYPE1_GENERATED;

			if (credentials instanceof NTCredentialsWithEncryption) {
				((NTCredentialsWithEncryption) credentials).resetEncryption(request);
			}
		} else if (state == State.MSG_TYPE2_RECEVIED) {
			final Type3Message responseO = engine.generateType3MsgObject(
				ntcredentials.getUserName(),
				ntcredentials.getPassword(),
				ntcredentials.getDomain(),
				ntcredentials.getWorkstation(),
				challenge
			);

			response = responseO.getResponse();
			state = State.MSG_TYPE3_GENERATED;
			if (credentials instanceof NTCredentialsWithEncryption) {
				((NTCredentialsWithEncryption) credentials).initEncryption(responseO, request);
			}
		} else {
			throw new AuthenticationException("Unexpected state: " + state);
		}
		final CharArrayBuffer buffer = new CharArrayBuffer(32);
		if (isProxy()) {
			buffer.append(AUTH.PROXY_AUTH_RESP);
		} else {
			buffer.append(AUTH.WWW_AUTH_RESP);
		}
		buffer.append(": NTLM ");
		buffer.append(response);
		return new BufferedHeader(buffer);
	}

	@Override
	public boolean isComplete() {
		return state == State.MSG_TYPE3_GENERATED || state == State.FAILED;
	}

	@Override
	public Header authenticate(final Credentials credentials, final HttpRequest request, final HttpContext context)
		throws AuthenticationException {
		return authenticate(credentials, request);
	}
}
