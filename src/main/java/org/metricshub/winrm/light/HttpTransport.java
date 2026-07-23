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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.metricshub.winrm.Utils;

/**
 * Minimal HTTP/1.1 client over a single kept-alive TCP socket. WinRM's NTLM authentication is
 * bound to the transport connection (not the request), so every request in a session must ride
 * the same socket — which is exactly why this does not use a pooling client.
 */
final class HttpTransport implements AutoCloseable {

	// After a connection has been idle at least this long, validate it before reuse: a peer may have
	// silently dropped an idle keep-alive connection, which Socket.isClosed() cannot detect. Kept small
	// so it never fires between the back-to-back requests of a single operation, only across idle gaps
	// (e.g. a cached executor sitting between polling cycles).
	private static final long VALIDATE_AFTER_INACTIVITY_MS = 1000;

	private final String host;
	private final int port;
	private final int timeoutMillis;
	private Socket socket;
	private OutputStream out;
	private BufferedInputStream in;
	private long lastActivityMillis;

	HttpTransport(final String host, final int port, final int timeoutMillis) {
		this.host = host;
		this.port = port;
		this.timeoutMillis = timeoutMillis;
	}

	static final class Response {

		final int status;
		final List<String[]> headers; // {name, value}, name lower-cased
		final byte[] body;

		Response(final int status, final List<String[]> headers, final byte[] body) {
			this.status = status;
			this.headers = headers;
			this.body = body;
		}

		String firstHeader(final String name) {
			final String n = name.toLowerCase(Locale.ROOT);
			for (final String[] h : headers) {
				if (h[0].equals(n)) {
					return h[1];
				}
			}
			return null;
		}

		List<String> allHeaders(final String name) {
			final String n = name.toLowerCase(Locale.ROOT);
			final List<String> values = new ArrayList<>();
			for (final String[] h : headers) {
				if (h[0].equals(n)) {
					values.add(h[1]);
				}
			}
			return values;
		}
	}

	/** Whether a live connection is currently held. */
	boolean isConnected() {
		if (socket == null || socket.isClosed() || !socket.isConnected()) {
			return false;
		}
		// Socket.isClosed() only reflects LOCAL closure: a server (or intermediary) that dropped an idle
		// keep-alive connection is invisible until the next write/read fails, which would silently lose
		// the first operation after the idle gap. Once the connection has been idle a while, probe it so
		// we treat it as dead here — request() then resets the NTLM session and reconnects before sending.
		if (Utils.getCurrentTimeMillis() - lastActivityMillis >= VALIDATE_AFTER_INACTIVITY_MS && isStalePeerClosed()) {
			close();
			return false;
		}
		return true;
	}

	/**
	 * Probe whether the peer has closed the connection, using a very short blocking read. A healthy idle
	 * keep-alive connection has no readable bytes, so the read times out (returns {@code false}); any
	 * byte or EOF means the connection is unusable (returns {@code true}). The read only ever consumes
	 * data on the unusable path, where the socket is discarded anyway, so a live stream is never disturbed.
	 */
	private boolean isStalePeerClosed() {
		final int previousTimeout;
		try {
			previousTimeout = socket.getSoTimeout();
		} catch (final IOException e) {
			return true;
		}
		try {
			socket.setSoTimeout(1);
			// A healthy idle keep-alive has nothing to read, so this blocks and times out (caught below).
			// Any return — EOF (peer closed) or an unexpected byte (protocol desync) — means it is unusable.
			in.read();
			return true;
		} catch (final SocketTimeoutException e) {
			return false;
		} catch (final IOException e) {
			return true;
		} finally {
			try {
				socket.setSoTimeout(previousTimeout);
			} catch (final IOException ignore) {
				// socket is being discarded on the stale path anyway
			}
		}
	}

	private void ensureConnected() throws IOException {
		if (isConnected()) {
			return;
		}
		final Socket newSocket = new Socket();
		try {
			newSocket.setTcpNoDelay(true);
			newSocket.connect(new InetSocketAddress(host, port), timeoutMillis);
			// Read timeout slightly above the caller's timeout so the WSMan OperationTimeout fault
			// (which the Receive loop retries) reliably arrives before a socket read times out.
			newSocket.setSoTimeout(timeoutMillis + 10_000);
			socket = newSocket;
			out = socket.getOutputStream();
			in = new BufferedInputStream(socket.getInputStream());
			lastActivityMillis = Utils.getCurrentTimeMillis();
		} catch (final IOException e) {
			// Never leave a half-open socket in the field, or ensureConnected would skip reconnecting.
			try {
				newSocket.close();
			} catch (final IOException ignore) {
				// best effort
			}
			socket = null;
			out = null;
			in = null;
			throw e;
		}
	}

	Response post(final String path, final byte[] body, final String contentType, final String authorization)
		throws IOException {
		ensureConnected();
		try {
			final StringBuilder head = new StringBuilder();
			head.append("POST ").append(path).append(" HTTP/1.1\r\n");
			head.append("Accept: */*\r\n");
			head.append("User-Agent: winrm-java-light\r\n");
			head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
			if (contentType != null) {
				head.append("Content-Type: ").append(contentType).append("\r\n");
			}
			head.append("Host: ").append(host).append(':').append(port).append("\r\n");
			head.append("Connection: Keep-Alive\r\n");
			if (authorization != null) {
				head.append("Authorization: ").append(authorization).append("\r\n");
			}
			head.append("\r\n");

			// Send head and body in a single write (one TCP segment), matching the reference client.
			final byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
			final ByteArrayOutputStream request = new ByteArrayOutputStream(
				headBytes.length + (body == null ? 0 : body.length)
			);
			request.write(headBytes);
			if (body != null && body.length > 0) {
				request.write(body);
			}
			out.write(request.toByteArray());
			out.flush();

			final Response response = readResponse();
			lastActivityMillis = Utils.getCurrentTimeMillis();
			return response;
		} catch (final IOException | RuntimeException e) {
			// A broken write/read leaves the socket in an unknown state and its read position possibly
			// corrupted; close it so the next request establishes a fresh (re-authenticated) connection.
			close();
			throw e;
		}
	}

	private Response readResponse() throws IOException {
		final String statusLine = readLine();
		if (statusLine == null) {
			throw new IOException("Connection closed by server before response");
		}
		final String[] statusParts = statusLine.split(" ", 3);
		final int status = Integer.parseInt(statusParts[1]);

		final List<String[]> headers = new ArrayList<>();
		int contentLength = -1;
		boolean chunked = false;
		boolean close = false;
		String line;
		while ((line = readLine()) != null && !line.isEmpty()) {
			final int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			final String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			final String value = line.substring(colon + 1).trim();
			headers.add(new String[] { name, value });
			if ("content-length".equals(name)) {
				contentLength = Integer.parseInt(value);
			} else if ("transfer-encoding".equals(name) && value.toLowerCase(Locale.ROOT).contains("chunked")) {
				chunked = true;
			} else if ("connection".equals(name) && value.toLowerCase(Locale.ROOT).contains("close")) {
				close = true;
			}
		}

		final byte[] body;
		if (chunked) {
			body = readChunked();
		} else if (contentLength >= 0) {
			body = readFixed(contentLength);
		} else {
			body = new byte[0];
		}

		if (close) {
			close();
		}
		return new Response(status, headers, body);
	}

	private String readLine() throws IOException {
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

	private byte[] readFixed(final int length) throws IOException {
		final byte[] buffer = new byte[length];
		int read = 0;
		while (read < length) {
			final int n = in.read(buffer, read, length - read);
			if (n < 0) {
				throw new IOException("Unexpected EOF: got " + read + " of " + length + " body bytes");
			}
			read += n;
		}
		return buffer;
	}

	private byte[] readChunked() throws IOException {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		while (true) {
			final String sizeLine = readLine();
			if (sizeLine == null) {
				throw new IOException("Unexpected EOF in chunked body");
			}
			final int semicolon = sizeLine.indexOf(';');
			final int size = Integer.parseInt((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim(), 16);
			if (size == 0) {
				// After the terminating chunk come zero or more optional trailer fields, then a final
				// empty line. Consume them all, or leftover bytes desync the kept-alive NTLM socket.
				String trailer;
				while ((trailer = readLine()) != null && !trailer.isEmpty()) {
					// discard trailer field
				}
				break;
			}
			body.write(readFixed(size));
			readLine(); // CRLF after each chunk
		}
		return body.toByteArray();
	}

	@Override
	public void close() {
		// Read the field into a local before closing so a concurrent close() (the main thread closing
		// the socket to unblock a worker blocked in a socket read) cannot NPE on a check-then-use race.
		// Closing an already-closed Socket is a no-op; closing an open one unblocks any pending read.
		final Socket doomed = socket;
		socket = null;
		out = null;
		in = null;
		if (doomed != null) {
			try {
				doomed.close();
			} catch (final IOException ignore) {
				// best effort
			}
		}
	}
}
