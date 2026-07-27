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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the {@link HttpTransport#pollTimeout(int)} deadline: one deadline-bounded poll may
 * span several HTTP round trips (a reconnect plus a re-authentication exchange), and every leg
 * must be capped by what is LEFT of the poll's budget — a peer answering each leg just fast enough
 * must not be able to stretch the poll to several multiples of the requested wait.
 */
class HttpTransportDeadlineTest {

	@Test
	void everyLegOfABoundedPollSharesOneDeadline() throws Exception {
		try (ServerSocket server = new ServerSocket(0)) {
			final Thread handler = new Thread(() -> serveSlowly(server), "slow-http-server");
			handler.setDaemon(true);
			handler.start();

			final HttpTransport transport = new HttpTransport("127.0.0.1", server.getLocalPort(), 60_000);
			try {
				// Budget: 500 ms wait + 1 s fault headroom = 1.5 s for EVERY leg together. The server
				// answers each leg after 600 ms — fast enough for any single leg, so only the shared
				// deadline can stop the sequence (leg 1 at ~0.6 s, leg 2 at ~1.2 s, leg 3 runs out).
				transport.pollTimeout(500);
				final long start = System.nanoTime();
				assertThrows(
					SocketTimeoutException.class,
					() -> {
						for (int leg = 0; leg < 8; leg++) {
							transport.post("/wsman", new byte[0], null, null);
						}
					}
				);
				final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
				assertTrue(
					elapsedMillis < 3_000,
					"the legs must share the poll deadline, not get a fresh timeout each; took " + elapsedMillis + " ms"
				);
			} finally {
				transport.close();
			}
		} // closing the ServerSocket unblocks the handler thread
	}

	/** Serve every request of every connection with a minimal 200 response, 600 ms late. */
	private static void serveSlowly(final ServerSocket server) {
		try {
			while (true) {
				final Socket socket = server.accept();
				final Thread connection = new Thread(() -> serveConnection(socket), "slow-http-conn");
				connection.setDaemon(true);
				connection.start();
			}
		} catch (final IOException ignored) {
			// server socket closed: test over
		}
	}

	private static void serveConnection(final Socket socket) {
		try (socket) {
			final InputStream in = new BufferedInputStream(socket.getInputStream());
			final OutputStream out = socket.getOutputStream();
			while (readRequestHead(in)) {
				Thread.sleep(600);
				out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
			}
		} catch (final IOException | InterruptedException ignored) {
			// connection torn down: client timed out or test over
		}
	}

	/** Consume one request head (the posts of this test carry no body). */
	private static boolean readRequestHead(final InputStream in) throws IOException {
		int matched = 0;
		int b;
		while ((b = in.read()) != -1) {
			// A request head ends with CRLFCRLF.
			if ((matched % 2 == 0 && b == '\r') || (matched % 2 == 1 && b == '\n')) {
				if (++matched == 4) {
					return true;
				}
			} else {
				matched = b == '\r' ? 1 : 0;
			}
		}
		return false;
	}
}
