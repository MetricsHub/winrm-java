// copy of code from apache-httpclient 4.5.13 package org.apache.http.impl.auth
// changes:
// - package name, this header, imports
// - expose Type3 message (package-private) so keys can be gathered

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

import org.apache.http.impl.auth.NTLMEngineException;

/**
 * Abstract NTLM authentication engine. The engine can be used to
 * generate Type1 messages and Type3 messages in response to a Type2 challenge.
 *
 * Code from io.cloudsoft.winrm4j.client.ntlm.forks.httpclient.NTLMEngine
 * release 0.12.3 @link https://github.com/cloudsoft/winrm4j
 * io.cloudsoft.winrm4j.client.ntlm.forks.httpclient is a fork of apache-httpclient 4.5.13
 */
public interface NTLMEngine {
	/**
	 * Generates a Type1 message given the domain and workstation.
	 *
	 * @param domain Optional Windows domain name. Can be {@code null}.
	 * @param workstation Optional Windows workstation name. Can be
	 *  {@code null}.
	 * @return Type1 message
	 * @throws NTLMEngineException
	 */
	String generateType1Msg(final String domain, final String workstation) throws NTLMEngineException;

	/**
	 * Generates a Type3 message given the user credentials and the
	 * authentication challenge.
	 *
	 * @param username Windows user name
	 * @param password Password
	 * @param domain Windows domain name
	 * @param workstation Windows workstation name
	 * @param challenge Type2 challenge.
	 * @return Type3 response.
	 * @throws NTLMEngineException
	 */
	String generateType3Msg(
		final String username,
		final String password,
		final String domain,
		final String workstation,
		final String challenge
	) throws NTLMEngineException;

	Type3Message generateType3MsgObject(
		final String username,
		final String password,
		final String domain,
		final String workstation,
		final String challenge
	) throws NTLMEngineException;
}
