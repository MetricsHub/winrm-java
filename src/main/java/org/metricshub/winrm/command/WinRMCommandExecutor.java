package org.metricshub.winrm.command;

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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.metricshub.winrm.TimeoutHelper;
import org.metricshub.winrm.Utils;
import org.metricshub.winrm.WinRMHttpProtocolEnum;
import org.metricshub.winrm.WindowsRemoteCommandResult;
import org.metricshub.winrm.WindowsRemoteProcessUtils;
import org.metricshub.winrm.exceptions.WindowsRemoteException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.WinRMEndpoint;
import org.metricshub.winrm.service.WinRMService;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.shares.SmbTempShare;

public class WinRMCommandExecutor {

	private WinRMCommandExecutor() {}

	/**
	 * Execute a command on a remote Windows system and return an object with
	 * the output of the command.
	 *
	 * You can specify local files to be copied to the remote system before executing the command.
	 * If the command contains references to these local files, it will be updated to reference the
	 * path on the remote system where the files have been copied.
	 *
	 * Example:
	 *
	 * <code>
	 * 		WinRemoteCommandExecutor.execute(
	 * 		"CSCRIPT c:\\MyScript.vbs", null, "remote-srv", null, null, null, 30000, Arrays.asList("c:\\MyScript.vbs"), false);
	 * </code>
	 *
	 * This will copy <b>c:\\MyScript.vbs</b> to <b>remote-srv</b>, typically in
	 * <b>C:\\Windows\\Temp\\SEN_ShareFor_MYHOST</b> and the command that is executed will therefore
	 * become:
	 *
	 * <code>CSCRIPT "C:\\Windows\\Temp\\SEN_ShareFor_MYHOST\\MyScript.vbs"</code>
	 *
	 * @param command The command to execute. (Mandatory)
	 * @param protocol The HTTP protocol (HTTP by default)
	 * @param hostname Host to connect to. (Mandatory)
	 * @param port The port (5985 for HTPP or 5986 for HTTPS by default)
	 * @param username The username name. (Mandatory)
	 * @param password The password.
	 * @param workingDirectory Path of the directory for the spawned process on the remote system (can be null)
	 * @param timeout The timeout in milliseconds (throws an IllegalArgumentException if negative or zero)
	 * @param localFileToCopyList List of local files to copy to the remote before the execution
	 * @param ticketCache The Ticket Cache path
	 * @param authentications List of authentications. only NTLM if absent
	 *
	 * @return an instance of WindowsRemoteCommandResult with the result of the command
	 *
	 * @throws IOException If an I/O error occurs.
	 * @throws TimeoutException To notify userName of timeout
	 * @throws WindowsRemoteException For any problem encountered on remote
	 */
	public static WindowsRemoteCommandResult execute(
		final String command,
		final WinRMHttpProtocolEnum protocol,
		final String hostname,
		final Integer port,
		final String username,
		final char[] password,
		final String workingDirectory,
		final long timeout,
		final List<String> localFileToCopyList,
		final Path ticketCache,
		final List<AuthenticationEnum> authentications
	) throws IOException, TimeoutException, WindowsRemoteException {
		Utils.checkNonNull(command, "command");
		Utils.checkArgumentNotZeroOrNegative(timeout, "timeout");

		final long start = System.currentTimeMillis();

		final WinRMEndpoint winRMEndpoint = new WinRMEndpoint(protocol, hostname, port, username, password, null);

		if (localFileToCopyList == null || localFileToCopyList.isEmpty()) {
			try (
				final WinRMService winRMService = WinRMService.createInstance(
					winRMEndpoint,
					timeout,
					ticketCache,
					authentications
				)
			) {
				final Charset charset = WindowsRemoteProcessUtils.getWindowsEncodingCharset(
					winRMService,
					TimeoutHelper.getRemainingTime(timeout, start, "No time left to retrieve the code set")
				);

				return winRMService.executeCommand(command, workingDirectory, charset, timeout);
			} catch (final WqlQuerySyntaxException e) {
				throw new IOException(e);
			}
		}

		try (
			final SmbTempShare smbTempShare = SmbTempShare.createInstance(
				winRMEndpoint,
				timeout,
				ticketCache,
				authentications
			)
		) {
			smbTempShare.checkConnectedFirst();

			final List<String> localFiles = localFileToCopyList
				.stream()
				.filter(Utils::isNotBlank)
				.collect(Collectors.toList());

			// Copy the list specified list of files, and update the command accordingly
			final String localFilesUpdatedCommand = WindowsRemoteProcessUtils.copyLocalFilesToShare(
				command,
				localFiles,
				smbTempShare.getUncSharePath(),
				smbTempShare.getRemotePath()
			);

			final Charset charset = WindowsRemoteProcessUtils.getWindowsEncodingCharset(
				smbTempShare.getWindowsRemoteExecutor(),
				TimeoutHelper.getRemainingTime(timeout, start, "No time left to retrieve the code set")
			);

			return smbTempShare
				.getWindowsRemoteExecutor()
				.executeCommand(
					String.format("CMD.EXE /C (%s)", localFilesUpdatedCommand),
					null,
					charset,
					TimeoutHelper.getRemainingTime(timeout, start, "No time left to execute command")
				);
		} catch (final WqlQuerySyntaxException e) {
			throw new IOException(e);
		}
	}
}
