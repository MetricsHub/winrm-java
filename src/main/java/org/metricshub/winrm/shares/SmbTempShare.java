package org.metricshub.winrm.shares;

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

import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WindowsRemoteExecutor;
import org.metricshub.winrm.WindowsTempShare;
import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;

public class SmbTempShare extends WindowsTempShare implements AutoCloseable {

	private final WinRMEndpoint winRMEndpoint;
	private final SMBClient smbClient;
	private final Connection connection;
	private final Session session;
	private final DiskShare diskShare;

	/**
	 * The SmbTempShare constructor.
	 *
	 * @param winRMService WinRMService instance
	 * @param winRMEndpoint Endpoint with credentials
	 * @param smbClient The SMB client
	 * @param connection The SMB connection
	 * @param session The SMB session
	 * @param diskShare The SMB disk share
	 * @param shareNameOrUnc The name of the share, or its full UNC path
	 * @param remotePath The path on the remote system of the directory being shared
	 */
	private SmbTempShare(
		final WinRMService winRMService,
		final WinRMEndpoint winRMEndpoint,
		final SMBClient smbClient,
		final Connection connection,
		final Session session,
		final DiskShare diskShare,
		final String shareNameOrUnc,
		final String remotePath
	) {
		super(winRMService, shareNameOrUnc, remotePath);
		this.winRMEndpoint = winRMEndpoint;
		this.smbClient = smbClient;
		this.connection = connection;
		this.session = session;
		this.diskShare = diskShare;
	}

	private static final ConcurrentHashMap<WinRMEndpoint, SmbTempShare> CONNECTIONS_CACHE = new ConcurrentHashMap<>();

	private final AtomicInteger useCount = new AtomicInteger(1);

	/**
	 * Create a SmbTempShare instance.
	 * Get or create a temp share and connect to it with SMB.
	 *
	 * @param winRMEndpoint Endpoint with credentials (mandatory)
	 * @param timeout Timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @param ticketCache The Ticket Cache path
	 * @param authentications List of authentications. only NTLM if absent
	 *
	 * @return SmbTempShare instance
	 *
	 * @throws IOException If an I/O error occurred
	 * @throws WinRMException For any problem encountered
	 * @throws TimeoutException To notify userName of timeout.
	 */
	public static SmbTempShare createInstance(
		final WinRMEndpoint winRMEndpoint,
		final long timeout,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws IOException, WinRMException, TimeoutException {
		Utils.checkNonNull(winRMEndpoint, "winRMEndpoint");
		Utils.checkNonNull(winRMEndpoint.getPassword(), "password");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		try {
			return CONNECTIONS_CACHE.compute(
				winRMEndpoint,
				(key, smb) -> {
					if (smb == null) {
						WinRMService winRMService = null;
						SMBClient smbClient = null;
						Connection connection = null;
						Session session = null;
						DiskShare diskShare = null;

						try {
							winRMService = WinRMService.createInstance(winRMEndpoint, timeout, ticketCache, authentications);

							final WindowsTempShare windowsTempShare = getOrCreateShare(
								winRMService,
								timeout,
								(w, r, s, t) -> {
									try {
										shareRemoteDirectory(w, r, s, t);
									} catch (final TimeoutException | WindowsRemoteException e) {
										throw new RuntimeException(e);
									}
								}
							);

							final SmbConfig smbConfig = SmbConfig
								.builder()
								.withSecurityProvider(new BCSecurityProvider())
								.withTimeout(timeout, TimeUnit.SECONDS)
								.build();

							final AuthenticationContext authenticationContext = new AuthenticationContext(
								winRMEndpoint.getUsername(),
								winRMEndpoint.getPassword(),
								winRMEndpoint.getDomain()
							);

							smbClient = createSmbClient(smbConfig);
							connection = smbClient.connect(winRMEndpoint.getHostname());
							session = connection.authenticate(authenticationContext);
							diskShare = (DiskShare) session.connectShare(windowsTempShare.getShareName());

							return new SmbTempShare(
								winRMService,
								winRMEndpoint,
								smbClient,
								connection,
								session,
								diskShare,
								windowsTempShare.getUncSharePath(),
								windowsTempShare.getRemotePath()
							);
						} catch (final RuntimeException e) {
							closeResources(winRMService, smbClient, connection, session, diskShare);

							throw e;
						} catch (final Exception e) {
							closeResources(winRMService, smbClient, connection, session, diskShare);

							throw new RuntimeException(e);
						}
					} else {
						synchronized (smb) {
							smb.incrementUseCount();

							return smb;
						}
					}
				}
			);
		} catch (final RuntimeException e) {
			final Throwable cause = e.getCause();

			if (cause instanceof IOException) {
				throw (IOException) cause;
			}

			if (cause instanceof TimeoutException) {
				throw (TimeoutException) cause;
			}

			if (cause instanceof WindowsRemoteException) {
				throw (WinRMException) cause;
			}

			throw e;
		}
	}

	private static void closeResources(
		final WinRMService winRMService,
		final SMBClient smbClient,
		final Connection connection,
		final Session session,
		final DiskShare diskShare
	) {
		try {
			if (diskShare != null) {
				diskShare.close();
			}

			if (session != null) {
				session.close();
			}

			if (connection != null) {
				connection.close();
			}
		} catch (final IOException ioe) {
			throw new RuntimeException(ioe);
		}

		if (smbClient != null) {
			smbClient.close();
		}

		if (winRMService != null) {
			winRMService.close();
		}
	}

	int getUseCount() {
		return useCount.get();
	}

	void incrementUseCount() {
		useCount.incrementAndGet();
	}

	/**
	 * @return whether this WbemServices instance is connected and usable
	 */
	boolean isConnected() {
		return getUseCount() > 0;
	}

	/**
	 * Check if it's connected. If not, throw an IllegalStateException.
	 */
	public void checkConnectedFirst() {
		if (!isConnected()) {
			throw new IllegalStateException("This instance has been closed and a new one must be created.");
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (useCount.decrementAndGet() == 0) {
			CONNECTIONS_CACHE.remove(winRMEndpoint);

			if (diskShare != null) {
				diskShare.close();
			}

			if (session != null) {
				session.close();
			}

			if (connection != null) {
				connection.close();
			}

			if (smbClient != null) {
				smbClient.close();
			}

			((WinRMService) getWindowsRemoteExecutor()).close();
		}
	}

	/**
	 * Share the remote directory on the host.
	 *
	 * @param windowsRemoteExecutor WinRMService instance.
	 * @param remotePath The remote path.
	 * @param shareName The Share Name.
	 * @param timeout Timeout in milliseconds.
	 *
	 * @throws TimeoutException To notify userName of timeout.
	 * @throws WindowsRemoteException For any problem encountered
	 *
	 */
	private static void shareRemoteDirectory(
		final WindowsRemoteExecutor windowsRemoteExecutor,
		final String remotePath,
		final String shareName,
		final long timeout
	) throws TimeoutException, WindowsRemoteException {
		final String command = String.format(
			"net share %s=%s /grant:%s,Full",
			shareName,
			remotePath,
			windowsRemoteExecutor.getUsername()
		);

		windowsRemoteExecutor.executeCommand(command, null, null, timeout);
	}

	static SMBClient createSmbClient(final SmbConfig smbConfig) {
		return new SMBClient(smbConfig);
	}
}
