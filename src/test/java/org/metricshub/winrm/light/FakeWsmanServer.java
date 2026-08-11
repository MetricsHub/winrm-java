package org.metricshub.winrm.light;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright 2023 - 2026 MetricsHub
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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process WSMan server for protocol tests (issue #107): speaks the real NTLM handshake
 * (fixed server challenge, NTLMv2 verification against a configured password, session-key
 * recovery from the wire Type 3) and real NTLM message encryption — by reusing the light
 * client's own crypto primitives with a mirrored {@link WinRMSession} — then serves scripted
 * SOAP response bodies. This exercises the client's full protocol path (transport, handshake
 * orchestration, sealing, multipart framing, decryption, XML handling) without a Windows host.
 * <p>
 * The NTLMv2 verification is real: a client that derives a wrong hash (e.g. a domain-case
 * regression) fails authentication here just like against a real host.
 */
public final class FakeWsmanServer implements AutoCloseable {

	/** One scripted HTTP response: status code and the plaintext SOAP body to encrypt and serve. */
	static final class Scripted {

		/** Sentinel status: close the connection after reading the request, without responding. */
		static final int DROP = -1;

		final int status;
		final String soapBody;
		final long delayMillis;

		Scripted(final int status, final String soapBody) {
			this(status, soapBody, 0L);
		}

		Scripted(final int status, final String soapBody, final long delayMillis) {
			this.status = status;
			this.soapBody = soapBody;
			this.delayMillis = delayMillis;
		}
	}

	private static final Charset UTF16LE = StandardCharsets.UTF_16LE;

	// Fixed 8-byte server challenge — "recorded exchange" determinism.
	private static final byte[] SERVER_CHALLENGE = {
			0x01,
			0x23,
			0x45,
			0x67,
			(byte) 0x89,
			(byte) 0xab,
			(byte) 0xcd,
			(byte) 0xef
	};

	// Type 2 flags: UNICODE | SIGN | SEAL | EXTENDED_SESSIONSECURITY | TARGETINFO | 128 | KEY_EXCH —
	// what a real WinRM host negotiates for encrypted HTTP, and what drives the client down the
	// NTLMv2 + explicit-key-exchange + extended-session-security path.
	private static final int TYPE2_FLAGS = 0x00000001
		| 0x00000010
		| 0x00000020
		| 0x00080000
		| 0x00800000
		| 0x20000000
		| 0x40000000;

	private final String expectedDomain;
	private final String expectedUser;
	private final String expectedPassword;

	private final ServerSocket serverSocket;
	private final Thread acceptThread;
	private final List<Thread> connectionThreads = new CopyOnWriteArrayList<>();

	private final Deque<Scripted> script = new ArrayDeque<>();
	private final List<String> decryptedRequests = new CopyOnWriteArrayList<>();
	private final java.util.concurrent.atomic.AtomicInteger connectionsToDrop = new java.util.concurrent.atomic.AtomicInteger();
	private volatile boolean closed;
	private volatile boolean chunkedResponses;

	/**
	 * Start the fake server on an ephemeral local port.
	 *
	 * @param domain the NetBIOS domain the client is expected to authenticate with
	 * @param user the user name the client is expected to authenticate with
	 * @param password the password the client's NTLMv2 proof is verified against
	 * @throws IOException when the listening socket cannot be opened
	 */
	public FakeWsmanServer(final String domain, final String user, final String password) throws IOException {
		this.expectedDomain = domain.toUpperCase(Locale.ROOT);
		this.expectedUser = user;
		this.expectedPassword = password;
		this.serverSocket = new ServerSocket(0);
		this.acceptThread = new Thread(this::acceptLoop, "fake-wsman-accept");
		this.acceptThread.setDaemon(true);
		this.acceptThread.start();
	}

	/**
	 * @return the local port the server listens on
	 */
	public int port() {
		return serverSocket.getLocalPort();
	}

	/**
	 * Queue the next scripted response (served in order, one per decrypted request).
	 *
	 * @param status the HTTP status code to respond with
	 * @param soapBody the plaintext SOAP body to encrypt and serve
	 * @return this server, for chaining
	 */
	public FakeWsmanServer enqueue(final int status, final String soapBody) {
		synchronized (script) {
			script.addLast(new Scripted(status, soapBody));
		}
		return this;
	}

	/**
	 * Queue the next scripted response with an artificial delay before it is served — to test
	 * client-side timeouts deterministically.
	 *
	 * @param status the HTTP status code to respond with
	 * @param soapBody the plaintext SOAP body to encrypt and serve
	 * @param delayMillis how long to wait before serving the response
	 * @return this server, for chaining
	 */
	public FakeWsmanServer enqueueDelayed(final int status, final String soapBody, final long delayMillis) {
		synchronized (script) {
			script.addLast(new Scripted(status, soapBody, delayMillis));
		}
		return this;
	}

	/**
	 * Queue a scripted connection drop: after reading (and decrypting) the request, the connection
	 * is closed without any response — simulating a transient network failure on a request that
	 * DID reach the server.
	 *
	 * @return this server, for chaining
	 */
	public FakeWsmanServer enqueueDrop() {
		synchronized (script) {
			script.addLast(new Scripted(Scripted.DROP, null));
		}
		return this;
	}

	/**
	 * Drop the next {@code count} incoming TCP connections as soon as they are accepted, before
	 * reading anything — simulating a transient failure while the connection is being established
	 * and authenticated, where the client's request provably never reached the server.
	 *
	 * @param count how many connections to drop
	 * @return this server, for chaining
	 */
	public FakeWsmanServer dropNextConnections(final int count) {
		connectionsToDrop.set(count);
		return this;
	}

	/**
	 * Serve the scripted bodies with {@code Transfer-Encoding: chunked} — several chunks, a chunk
	 * extension, and trailer fields after the terminating chunk — instead of {@code Content-Length},
	 * like a real WinRM host does. A client that mis-reads the framing (e.g. leaves the trailers in
	 * the socket) desyncs the kept-alive connection and fails on the NEXT request.
	 *
	 * @return this server, for chaining
	 */
	public FakeWsmanServer withChunkedResponses() {
		chunkedResponses = true;
		return this;
	}

	/**
	 * The plaintext SOAP request bodies received so far, in order (after decryption).
	 *
	 * @return a copy of the decrypted request bodies
	 */
	public List<String> decryptedRequests() {
		return new ArrayList<>(decryptedRequests);
	}

	/** One stdin chunk carried by a WSMan Send request: its decoded bytes and its End flag. */
	public static final class StdinChunk {

		private final byte[] data;
		private final boolean end;

		private StdinChunk(final byte[] data, final boolean end) {
			this.data = data;
			this.end = end;
		}

		/**
		 * @return the decoded stdin bytes of this chunk
		 */
		public byte[] data() {
			return data.clone();
		}

		/**
		 * @return whether the chunk was flagged as the end of input
		 */
		public boolean end() {
			return end;
		}
	}

	/**
	 * The stdin chunks received so far through WSMan Send requests, in order: base64-decoded
	 * content and End flag, extracted from the decrypted request bodies.
	 *
	 * @return the stdin chunks, in the order they were received
	 */
	public List<StdinChunk> stdinChunks() {
		final List<StdinChunk> chunks = new ArrayList<>();
		final java.util.regex.Pattern stream = java.util.regex.Pattern.compile(
			"<rsp:Send><rsp:Stream Name=\"stdin\"([^>]*)>([^<]*)</rsp:Stream></rsp:Send>"
		);
		for (final String request : decryptedRequests) {
			final java.util.regex.Matcher matcher = stream.matcher(request);
			while (matcher.find()) {
				chunks.add(
					new StdinChunk(
						Base64.getDecoder().decode(matcher.group(2)),
						matcher.group(1).contains("End=\"true\"")
					)
				);
			}
		}
		return chunks;
	}

	@Override
	public void close() {
		closed = true;
		try {
			serverSocket.close();
		} catch (final IOException ignore) {
			// shutting down
		}
		for (final Thread t : connectionThreads) {
			t.interrupt();
		}
	}

	// --- connection handling --------------------------------------------------

	private void acceptLoop() {
		while (!closed) {
			try {
				final Socket socket = serverSocket.accept();
				final Thread t = new Thread(() -> handleConnection(socket), "fake-wsman-conn");
				t.setDaemon(true);
				connectionThreads.add(t);
				t.start();
			} catch (final IOException e) {
				return; // server socket closed
			}
		}
	}

	private void handleConnection(final Socket socket) {
		// NTLM state is bound to the TCP connection, exactly like a real WinRM host.
		WinRMSession serverSession = null;
		try (socket) {
			if (connectionsToDrop.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
				return; // scripted connection drop: close without reading anything
			}
			socket.setTcpNoDelay(true);
			final BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
			final OutputStream out = socket.getOutputStream();
			while (!closed) {
				final HttpRequest request = HttpRequest.read(in);
				if (request == null) {
					return; // client closed the connection
				}
				final String authorization = request.header("authorization");
				if (serverSession == null || authorization != null) {
					final byte[] token = negotiateToken(authorization);
					if (token == null) {
						respond(out, 401, "WWW-Authenticate: Negotiate", null, null);
						continue;
					}
					final int messageType = NTLMMessage.readULong(token, 8);
					if (messageType == 1) {
						respond(
							out,
							401,
							"WWW-Authenticate: Negotiate " + Base64.getEncoder().encodeToString(buildType2()),
							null,
							null
						);
						continue;
					}
					if (messageType != 3) {
						respond(out, 401, "WWW-Authenticate: Negotiate", null, null);
						continue;
					}
					serverSession = authenticate(token);
					if (serverSession == null) {
						// Bad credentials: reject like a real host — 401 on the request carrying the Type 3.
						respond(out, 401, "WWW-Authenticate: Negotiate", null, null);
						continue;
					}
					// fall through: the request that carried the Type 3 also carries the first sealed body
				}
				if (!serveScripted(out, serverSession, request.body)) {
					return; // scripted mid-exchange drop: close without responding
				}
			}
		} catch (final IOException | RuntimeException e) {
			// connection torn down (client close, test shutdown) — nothing to do
		}
	}

	/**
	 * Serve the next scripted response for one decrypted request.
	 *
	 * @return {@code true} to keep the connection alive, {@code false} when the script asked for a
	 *         connection drop instead of a response
	 */
	private boolean serveScripted(final OutputStream out, final WinRMSession session, final byte[] sealedBody)
		throws IOException {
		final byte[] plaintext = NtlmCrypto.decrypt(session, sealedBody);
		decryptedRequests.add(new String(plaintext, StandardCharsets.UTF_8));

		Scripted next;
		synchronized (script) {
			next = script.pollFirst();
		}
		if (next != null && next.status == Scripted.DROP) {
			return false;
		}
		if (next == null) {
			// Loud, decryptable failure so an over-consuming test fails on an assertion, not a hang.
			next = new Scripted(
				500,
				"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><s:Fault>" +
					"<s:Reason><s:Text xml:lang=\"en-US\">FakeWsmanServer: no scripted response left</s:Text></s:Reason>" +
					"</s:Fault></s:Body></s:Envelope>"
			);
		}
		if (next.delayMillis > 0) {
			try {
				Thread.sleep(next.delayMillis);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return false; // test shutdown
			}
		}
		final byte[] sealed = NtlmCrypto.encryptAndSign(session, next.soapBody.getBytes(StandardCharsets.UTF_8));
		respond(out, next.status, null, NtlmCrypto.ENCRYPTED_CONTENT_TYPE, sealed);
		return true;
	}

	// --- NTLM server side -------------------------------------------------------

	private static byte[] negotiateToken(final String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Negotiate ", 0, 10)) {
			return null;
		}
		return Base64.getDecoder().decode(authorization.substring(10).trim());
	}

	/** Fixed Type 2 challenge message: target "FAKE", the fixed server challenge, and target info. */
	private static byte[] buildType2() {
		final byte[] targetName = "FAKE".getBytes(UTF16LE);
		final byte[] targetInfo = concat(
			avPair(2, "FAKE"), // NetBIOS domain
			avPair(1, "FAKESRV"), // NetBIOS computer
			new byte[]
			{ 0, 0, 0, 0 } // terminator
		);
		final ByteArrayOutputStream msg = new ByteArrayOutputStream();
		writeBytes(msg, "NTLMSSP\0".getBytes(StandardCharsets.US_ASCII));
		writeULong(msg, 2);
		final int targetNameOffset = 56;
		writeSecurityBuffer(msg, targetName.length, targetNameOffset);
		writeULong(msg, TYPE2_FLAGS);
		writeBytes(msg, SERVER_CHALLENGE);
		writeBytes(msg, new byte[8]); // context
		writeSecurityBuffer(msg, targetInfo.length, targetNameOffset + targetName.length);
		writeBytes(msg, new byte[8]); // version (unparsed by the client)
		writeBytes(msg, targetName);
		writeBytes(msg, targetInfo);
		return msg.toByteArray();
	}

	/**
	 * Verify the Type 3 NTLMv2 response against the configured credentials and, on success, recover
	 * the exported session key from the wire and install the mirrored (server-side) session keys.
	 * Returns null when the proof does not match — i.e. the client used a wrong password/hash.
	 */
	private WinRMSession authenticate(final byte[] type3) {
		final byte[] ntResponse = readSecurityBuffer(type3, 20);
		final byte[] encryptedSessionKey = readSecurityBuffer(type3, 52);
		final int flags = NTLMMessage.readULong(type3, 60);
		final String domain = new String(readSecurityBuffer(type3, 28), UTF16LE);
		final String user = new String(readSecurityBuffer(type3, 36), UTF16LE);

		if (!expectedDomain.equals(domain) || !expectedUser.equals(user) || ntResponse.length < 16) {
			return null;
		}

		// NTOWFv2 = HMAC-MD5(MD4(UTF16LE(password)), UTF16LE(UPPER(user) + domain)) — same derivation
		// as the client's CipherGen, computed from the SERVER's copy of the password.
		final MD4 md4 = new MD4();
		md4.update(expectedPassword.getBytes(UTF16LE));
		final byte[] ntlmHash = md4.getOutput();
		final byte[] ntowfV2 = EncryptionUtils.hmacMd5(
			ntlmHash,
			concat(expectedUser.toUpperCase(Locale.ROOT).getBytes(UTF16LE), expectedDomain.getBytes(UTF16LE))
		);

		// NTProofStr (first 16 bytes) must equal HMAC-MD5(NTOWFv2, serverChallenge || blob).
		final byte[] ntProofStr = Arrays.copyOfRange(ntResponse, 0, 16);
		final byte[] blob = Arrays.copyOfRange(ntResponse, 16, ntResponse.length);
		final byte[] expectedProof = EncryptionUtils.hmacMd5(ntowfV2, concat(SERVER_CHALLENGE, blob));
		if (!Arrays.equals(ntProofStr, expectedProof)) {
			return null;
		}

		// Session key: userSessionKey = HMAC-MD5(NTOWFv2, NTProofStr); with KEY_EXCH the wire field is
		// RC4(exportedSessionKey, userSessionKey) — RC4 is symmetric, so decrypt with the same call.
		final byte[] userSessionKey = EncryptionUtils.hmacMd5(ntowfV2, ntProofStr);
		final byte[] exportedSessionKey = EncryptionUtils.calculateRC4(encryptedSessionKey, userSessionKey);

		final WinRMSession session = new WinRMSession(expectedDomain, null, expectedUser, expectedPassword);
		session.applyKeys(flags, exportedSessionKey, true);
		return session;
	}

	// --- byte-level helpers -----------------------------------------------------

	private static byte[] readSecurityBuffer(final byte[] src, final int position) {
		final int length = (src[position] & 0xff) | ((src[position + 1] & 0xff) << 8);
		final int offset = NTLMMessage.readULong(src, position + 4);
		return Arrays.copyOfRange(src, offset, offset + length);
	}

	private static byte[] avPair(final int id, final String value) {
		final byte[] bytes = value.getBytes(UTF16LE);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(id & 0xff);
		out.write((id >> 8) & 0xff);
		out.write(bytes.length & 0xff);
		out.write((bytes.length >> 8) & 0xff);
		writeBytes(out, bytes);
		return out.toByteArray();
	}

	private static byte[] concat(final byte[]... arrays) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (final byte[] a : arrays) {
			writeBytes(out, a);
		}
		return out.toByteArray();
	}

	private static void writeBytes(final ByteArrayOutputStream out, final byte[] bytes) {
		out.write(bytes, 0, bytes.length);
	}

	private static void writeULong(final ByteArrayOutputStream out, final int value) {
		out.write(value & 0xff);
		out.write((value >> 8) & 0xff);
		out.write((value >> 16) & 0xff);
		out.write((value >> 24) & 0xff);
	}

	private static void writeSecurityBuffer(final ByteArrayOutputStream out, final int length, final int offset) {
		out.write(length & 0xff);
		out.write((length >> 8) & 0xff);
		out.write(length & 0xff);
		out.write((length >> 8) & 0xff);
		writeULong(out, offset);
	}

	// --- minimal HTTP -----------------------------------------------------------

	private void respond(
		final OutputStream out,
		final int status,
		final String extraHeader,
		final String contentType,
		final byte[] body
	) throws IOException {
		final byte[] payload = body == null ? new byte[0] : body;
		final boolean chunked = chunkedResponses && payload.length > 0;
		final StringBuilder head = new StringBuilder();
		head.append("HTTP/1.1 ").append(status).append(' ').append(status == 200 ? "OK" : "Error").append("\r\n");
		head.append("Server: FakeWsmanServer\r\n");
		if (extraHeader != null) {
			head.append(extraHeader).append("\r\n");
		}
		if (contentType != null) {
			head.append("Content-Type: ").append(contentType).append("\r\n");
		}
		head.append(chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: " + payload.length + "\r\n");
		head.append("\r\n");
		out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
		if (chunked) {
			writeChunkedBody(out, payload);
		} else {
			out.write(payload);
		}
		out.flush();
	}

	/** Write the payload as two chunks (the first with a chunk extension) plus a trailer field. */
	private static void writeChunkedBody(final OutputStream out, final byte[] payload) throws IOException {
		final int split = Math.max(1, payload.length / 2);
		writeChunk(out, payload, 0, split, ";boundary=middle");
		if (split < payload.length) {
			writeChunk(out, payload, split, payload.length - split, "");
		}
		out.write("0\r\nX-Fake-Trailer: done\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
	}

	private static void writeChunk(
		final OutputStream out,
		final byte[] payload,
		final int offset,
		final int length,
		final String extension
	) throws IOException {
		out.write((Integer.toHexString(length) + extension + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
		out.write(payload, offset, length);
		out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
	}

	/** One parsed HTTP request: headers (lower-cased names) and the raw body. */
	private static final class HttpRequest {

		final Map<String, String> headers = new TreeMap<>();
		byte[] body = new byte[0];

		String header(final String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}

		static HttpRequest read(final InputStream in) throws IOException {
			final String requestLine = readLine(in);
			if (requestLine == null || requestLine.isEmpty()) {
				return null;
			}
			final HttpRequest request = new HttpRequest();
			String line;
			while ((line = readLine(in)) != null && !line.isEmpty()) {
				final int colon = line.indexOf(':');
				if (colon > 0) {
					request.headers.put(
						line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
						line.substring(colon + 1).trim()
					);
				}
			}
			final String contentLength = request.header("content-length");
			if (contentLength != null) {
				final int length = Integer.parseInt(contentLength);
				final byte[] body = new byte[length];
				int read = 0;
				while (read < length) {
					final int n = in.read(body, read, length - read);
					if (n < 0) {
						throw new IOException("EOF in request body");
					}
					read += n;
				}
				request.body = body;
			}
			return request;
		}

		private static String readLine(final InputStream in) throws IOException {
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			int b;
			int prev = -1;
			while ((b = in.read()) != -1) {
				if (prev == '\r' && b == '\n') {
					final byte[] raw = buffer.toByteArray();
					return new String(raw, 0, raw.length - 1, StandardCharsets.ISO_8859_1);
				}
				buffer.write(b);
				prev = b;
			}
			return buffer.size() == 0 ? null : buffer.toString("ISO-8859-1");
		}
	}
}
