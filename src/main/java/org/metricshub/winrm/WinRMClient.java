package org.metricshub.winrm;

/*-
 * ╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲╱╲
 * WinRM Java Client
 * ჻჻჻჻჻჻
 * Copyright (C) 2023 - 2026 MetricsHub
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import org.metricshub.winrm.exceptions.WinRMClientException;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WinRMTimeoutException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMExecutorFactory;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

/**
 * The fluent entry point of the library: a reusable WinRM connection to one host, created with
 * a builder and closed with try-with-resources. One client authenticates once and can run any
 * number of WQL queries and commands over the same connection.
 *
 * <pre>{@code
 * try (
 * 	WinRMClient client = WinRMClient.builder("server01.acme.com")
 * 		.credentials("ACME\\admin", password)
 * 		.timeout(Duration.ofSeconds(30))
 * 		.build()) {
 *
 * 	WqlResult services = client.wql("SELECT Name, State FROM Win32_Service").execute();
 * 	for (WqlRow row : services) {
 * 		System.out.println(row.string("Name") + " is " + row.string("State"));
 * 	}
 *
 * 	CommandResult result = client.command("ipconfig /all").execute();
 * 	System.out.println(result.stdout());
 * }
 * }</pre>
 * <p>
 * Besides the blocking {@code execute()} terminals, both operations can stream:
 * {@link WqlRequest#stream()} yields WQL rows lazily page by page, and
 * {@link CommandRequest#start()} returns a {@link RemoteProcess} whose output is consumed while
 * the command is still running.
 * <p>
 * Thread-safety: a client may be shared between threads, but a WinRM connection is a serial
 * channel — concurrent operations are executed one at a time, and an open stream or process
 * holds the connection until it is closed.
 * <p>
 * Failures are reported through the unchecked
 * {@link org.metricshub.winrm.exceptions.WinRMClientException} hierarchy; the legacy static
 * helpers ({@link org.metricshub.winrm.wql.WinRMWqlExecutor},
 * {@link org.metricshub.winrm.command.WinRMCommandExecutor}) and their checked exceptions are
 * unaffected.
 */
public final class WinRMClient implements AutoCloseable {

	/** Default operation timeout when the builder does not set one. */
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

	private final WindowsRemoteExecutor executor;
	private final String hostname;
	private final String namespace;
	private final Duration timeout;

	private WinRMClient(
		final WindowsRemoteExecutor executor,
		final String hostname,
		final String namespace,
		final Duration timeout
	) {
		this.executor = executor;
		this.hostname = hostname;
		this.namespace = namespace;
		this.timeout = timeout;
	}

	/**
	 * Start building a client for the given host.
	 *
	 * @param hostname the host to connect to (mandatory; for Kerberos, use the FQDN the KDC knows)
	 * @return a new {@link Builder}
	 */
	public static Builder builder(final String hostname) {
		return new Builder(hostname);
	}

	/**
	 * Prepare a WQL query. Nothing is sent until {@link WqlRequest#execute()} is called.
	 *
	 * @param query the WQL query, e.g. {@code SELECT Name, State FROM Win32_Service}
	 * @return the request, to configure and execute
	 */
	public WqlRequest wql(final String query) {
		return new WqlRequest(this, query);
	}

	/**
	 * Prepare a command execution. Nothing is sent until {@link CommandRequest#execute()} is
	 * called.
	 *
	 * @param commandLine the command line to execute (run through {@code cmd.exe} by the remote shell)
	 * @return the request, to configure and execute
	 */
	public CommandRequest command(final String commandLine) {
		return new CommandRequest(this, commandLine);
	}

	/**
	 * Prepare a PowerShell script execution. Nothing is sent until
	 * {@link CommandRequest#execute()} is called.
	 * <p>
	 * The script travels base64-encoded ({@code powershell.exe -NoProfile -NonInteractive
	 * -EncodedCommand ...}), so it needs <b>no quoting or escaping whatsoever</b>: quotes, pipes,
	 * newlines, and {@code $variables} reach PowerShell exactly as written.
	 *
	 * <pre>{@code
	 * CommandResult result = client.powerShell(
	 * 	"Get-Service | Where-Object { $_.Status -eq 'Running' } | Select-Object -First 5 Name"
	 * ).execute();
	 * }</pre>
	 *
	 * The returned request is the same as for {@link #command(String)}: every option and both
	 * terminals apply unchanged — including {@link CommandRequest#upload(Path...)}, whose path
	 * rewriting happens on the script text <i>before</i> it is encoded, so a script referencing an
	 * uploaded file runs against the remote copy. {@code powershell.exe} exits with 0 on success
	 * and 1 when the script ends with a terminating error; call {@code exit <n>} in the script for
	 * a specific exit code.
	 * <p>
	 * There is no practical script size limit. A script whose encoded invocation would not fit
	 * the remote shell's command line (roughly 3000 characters of script) is automatically
	 * transferred as a temporary {@code .ps1} file — through the WinRM connection itself, exactly
	 * like {@link CommandRequest#upload(Path...)} — and run with {@code powershell.exe -File}.
	 * The remote copy is content-addressed, so re-running an identical script skips the transfer.
	 * Like any request with uploads, the transfer commands are then what creates the remote
	 * shell, so the shell-scoped {@link CommandRequest#workingDirectory(String)} does not apply.
	 *
	 * @param script the PowerShell script to execute, verbatim
	 * @return the request, to configure and execute
	 * @throws IllegalArgumentException when the script is blank
	 */
	public CommandRequest powerShell(final String script) {
		Utils.checkNonBlank(script, "script");
		return new CommandRequest(this, script, true);
	}

	/**
	 * Copy a local file to an explicit path on the remote host, through the WinRM connection
	 * itself (no SMB, no extra port). The transfer is digest-verified and skipped when the
	 * destination already has identical content; the destination directory is created when
	 * needed. The client's timeout applies.
	 *
	 * @param localFile the local file to copy
	 * @param remoteFile the absolute destination path on the remote host, e.g.
	 *        {@code C:\Windows\Temp\collect.ps1}
	 * @throws org.metricshub.winrm.exceptions.WinRMTimeoutException when the timeout elapses first
	 * @throws org.metricshub.winrm.exceptions.WinRMClientException for any other failure
	 */
	public void uploadFile(final Path localFile, final String remoteFile) {
		try {
			ShellFileCopy.copyLocalFileToRemoteFile(executor, localFile, remoteFile, toMillis(timeout));
		} catch (final TimeoutException e) {
			throw new WinRMTimeoutException(
				String.format("Upload of %s timed out after %s on %s", localFile, timeout, hostname),
				e
			);
		} catch (final IOException e) {
			throw new WinRMClientException(e.getMessage(), e);
		} catch (final WindowsRemoteException e) {
			throw translate(e);
		}
	}

	/**
	 * Get the hostname this client connects to.
	 *
	 * @return the hostname
	 */
	public String hostname() {
		return hostname;
	}

	/**
	 * Close the client and release its connection. Idempotent; operations attempted after
	 * closing throw {@link IllegalStateException}.
	 */
	@Override
	public void close() {
		executor.close();
	}

	/** The executor backing this client. */
	WindowsRemoteExecutor executor() {
		return executor;
	}

	/** The client-level default WMI namespace. */
	String defaultNamespace() {
		return namespace;
	}

	/** The client-level default operation timeout. */
	Duration defaultTimeout() {
		return timeout;
	}

	/**
	 * Translate a legacy checked exception into the unchecked hierarchy: when a typed
	 * {@link WinRMClientException} (fault, authentication) is in the cause chain, it is unwrapped
	 * and rethrown directly; anything else is wrapped with its message preserved.
	 */
	static WinRMClientException translate(final Exception exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof WinRMClientException) {
				return (WinRMClientException) cause;
			}
		}
		return new WinRMClientException(
			exception.getMessage() != null ? exception.getMessage() : exception.toString(),
			exception
		);
	}

	/**
	 * Validate that a duration is non-null and at least one millisecond (the wire granularity of
	 * every timeout in this API — a positive sub-millisecond duration would silently become 0),
	 * and return it.
	 */
	static Duration checkPositive(final Duration duration, final String name) {
		Utils.checkNonNull(duration, name);
		if (toMillis(duration) < 1) {
			throw new IllegalArgumentException(name + " must be at least one millisecond.");
		}
		return duration;
	}

	/** Convert a positive duration to milliseconds, saturating instead of overflowing. */
	static long toMillis(final Duration duration) {
		try {
			return duration.toMillis();
		} catch (final ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Builder of {@link WinRMClient} instances: connection-scoped settings with sensible
	 * defaults. Only the hostname and the credentials are mandatory.
	 */
	public static final class Builder {

		private final String hostname;
		private WinRMHttpProtocolEnum protocol = WinRMHttpProtocolEnum.HTTP;
		private Integer port;
		private String username;
		private char[] password;
		private String namespace;
		private List<AuthScheme> authentication;
		private Path ticketCache;
		private boolean trustAllCertificates;
		private int consoleCodePage;
		private SSLContext sslContext;
		private Duration timeout = DEFAULT_TIMEOUT;

		private Builder(final String hostname) {
			Utils.checkNonBlank(hostname, "hostname");
			this.hostname = hostname;
		}

		/**
		 * Connect over HTTPS (port 5986 unless {@link #port(int)} is set). The server certificate
		 * is validated against the platform trust store and the hostname is verified, unless
		 * {@link #trustAllCertificates()} or {@link #sslContext(SSLContext)} says otherwise.
		 *
		 * @return this builder
		 */
		public Builder https() {
			this.protocol = WinRMHttpProtocolEnum.HTTPS;
			return this;
		}

		/**
		 * Connect over HTTP (port 5985 unless {@link #port(int)} is set) — the default. The SOAP
		 * messages are NTLM-encrypted on the wire.
		 *
		 * @return this builder
		 */
		public Builder http() {
			this.protocol = WinRMHttpProtocolEnum.HTTP;
			return this;
		}

		/**
		 * Set the port. Default: 5985 for HTTP, 5986 for HTTPS.
		 *
		 * @param port the TCP port (1-65535)
		 * @return this builder
		 */
		public Builder port(final int port) {
			if (port < 1 || port > 65535) {
				throw new IllegalArgumentException("port must be between 1 and 65535.");
			}
			this.port = port;
			return this;
		}

		/**
		 * Set the credentials (mandatory).
		 *
		 * @param username the user name, plain ({@code user}) or domain-qualified ({@code DOMAIN\\user})
		 * @param password the password; the array is deliberately not copied, so the caller can
		 *        wipe the single authoritative copy of the secret after closing the client
		 * @return this builder
		 */
		@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The password char[] is deliberately shared, not copied, so the caller "
			+
			"can wipe the single authoritative copy of the secret")
		public Builder credentials(final String username, final char[] password) {
			Utils.checkNonNull(username, "username");
			Utils.checkNonNull(password, "password");
			// Validate the shape now (on the whitespace-stripped form the endpoint actually parses):
			// a lone or edge backslash would otherwise surface as an obscure parsing error at build().
			final String cleaned = username.replaceAll("\\s", "");
			final int backslash = cleaned.indexOf('\\');
			if (cleaned.isEmpty() || backslash == 0 || backslash == cleaned.length() - 1) {
				throw new IllegalArgumentException("username must be \"user\" or \"DOMAIN\\user\".");
			}
			this.username = username;
			this.password = password;
			return this;
		}

		/**
		 * Set the default WMI namespace for WQL queries. Default: {@code ROOT\CIMV2}. Each query
		 * can override it with {@link WqlRequest#namespace(String)}.
		 *
		 * @param namespace the WMI namespace
		 * @return this builder
		 */
		public Builder namespace(final String namespace) {
			Utils.checkNonBlank(namespace, "namespace");
			this.namespace = namespace;
			return this;
		}

		/**
		 * Set the authentication schemes, tried in the given order until one succeeds. Default:
		 * NTLM only. Kerberos requires HTTPS.
		 *
		 * @param schemes the schemes in fallback order, e.g. {@code KERBEROS, NTLM}
		 * @return this builder
		 */
		public Builder authentication(final AuthScheme... schemes) {
			Utils.checkNonNull(schemes, "schemes");
			if (schemes.length == 0) {
				throw new IllegalArgumentException("At least one authentication scheme is required.");
			}
			final List<AuthScheme> list = new ArrayList<>(schemes.length);
			for (final AuthScheme scheme : schemes) {
				Utils.checkNonNull(scheme, "schemes");
				list.add(scheme);
			}
			this.authentication = list;
			return this;
		}

		/**
		 * Set the Kerberos ticket cache path. Default: none — Kerberos logs in with the password.
		 *
		 * @param ticketCache the ticket cache path
		 * @return this builder
		 */
		public Builder ticketCache(final Path ticketCache) {
			Utils.checkNonNull(ticketCache, "ticketCache");
			this.ticketCache = ticketCache;
			return this;
		}

		/**
		 * Trust every server certificate and skip hostname verification over HTTPS — for
		 * self-signed test hosts. Insecure: do not use in production. This per-client setting
		 * replaces the global {@code org.metricshub.winrm.tls.insecure} system property.
		 *
		 * @return this builder
		 */
		public Builder trustAllCertificates() {
			this.trustAllCertificates = true;
			return this;
		}

		/**
		 * Use a custom {@link SSLContext} for HTTPS — e.g. one built around a dedicated trust
		 * store. Hostname verification stays on. Mutually exclusive with
		 * {@link #trustAllCertificates()}.
		 *
		 * @param sslContext the TLS context providing the socket factory
		 * @return this builder
		 */
		public Builder sslContext(final SSLContext sslContext) {
			Utils.checkNonNull(sslContext, "sslContext");
			this.sslContext = sslContext;
			return this;
		}

		/**
		 * Set the default timeout of every operation — a wall-clock deadline covering
		 * authentication, every WSMan round trip, and result collection. Default: 30 seconds.
		 * Each operation can override it.
		 *
		 * @param timeout the timeout (at least one millisecond)
		 * @return this builder
		 */
		public Builder timeout(final Duration timeout) {
			this.timeout = checkPositive(timeout, "timeout");
			return this;
		}

		/**
		 * Set the console code page of the remote command shell. Default: 65001 (UTF-8), which makes
		 * command output UTF-8 whatever the remote locale — the right choice for reading output and
		 * for piping data to a program.
		 * <p>
		 * An <b>interactive</b> session needs a different setting: under code page 65001 a remote
		 * {@code cmd.exe} decodes the command lines it reads from its standard input one byte at a
		 * time, so every non-ASCII character is lost. Pin a single-byte code page (typically the
		 * remote machine's ANSI one, {@code Win32_OperatingSystem.CodeSet}) and use the matching
		 * charset for both directions, as the CLI's {@code shell} subcommand does.
		 *
		 * @param consoleCodePage the console code page (e.g. 1252), or 0 for the default
		 * @return this builder
		 */
		public Builder consoleCodePage(final int consoleCodePage) {
			if (consoleCodePage < 0) {
				throw new IllegalArgumentException("consoleCodePage must not be negative.");
			}
			this.consoleCodePage = consoleCodePage;
			return this;
		}

		/**
		 * Build the client. This does not connect yet: the connection is established and
		 * authenticated by the first operation.
		 *
		 * @return the client, to use with try-with-resources
		 * @throws org.metricshub.winrm.exceptions.WinRMClientException when the configuration is
		 *         rejected (e.g. Kerberos requested over HTTP)
		 */
		public WinRMClient build() {
			if (username == null || password == null) {
				throw new IllegalStateException("credentials(username, password) is required.");
			}
			if (sslContext != null && trustAllCertificates) {
				throw new IllegalStateException("Set either sslContext(...) or trustAllCertificates(), not both.");
			}

			final WinRMEndpoint endpoint = new WinRMEndpoint(protocol, hostname, port, username, password, namespace);

			List<AuthenticationEnum> authentications = null;
			if (authentication != null) {
				authentications = new ArrayList<>(authentication.size());
				for (final AuthScheme scheme : authentication) {
					authentications.add(
						scheme == AuthScheme.KERBEROS ? AuthenticationEnum.KERBEROS : AuthenticationEnum.NTLM
					);
				}
			}

			try {
				final WindowsRemoteExecutor executor = WinRMExecutorFactory.createInstance(
					endpoint,
					toMillis(timeout),
					ticketCache,
					authentications,
					sslContext,
					trustAllCertificates,
					consoleCodePage
				);
				return new WinRMClient(executor, endpoint.getHostname(), endpoint.getNamespace(), timeout);
			} catch (final WinRMException e) {
				throw translate(e);
			}
		}
	}
}
