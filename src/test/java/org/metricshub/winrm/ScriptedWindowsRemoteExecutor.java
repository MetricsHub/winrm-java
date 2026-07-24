package org.metricshub.winrm;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled {@link WindowsRemoteExecutor} fake: tests register canned responses matched by
 * substring, in registration order, and can then assert on the commands that were executed.
 * A response queue that runs out keeps repeating its last element.
 */
public class ScriptedWindowsRemoteExecutor implements WindowsRemoteExecutor {

	private static final class CommandHandler {

		private final String substring;
		private final Deque<WindowsRemoteCommandResult> results;

		private CommandHandler(final String substring, final WindowsRemoteCommandResult[] results) {
			this.substring = substring;
			this.results = new ArrayDeque<>(List.of(results));
		}

		private WindowsRemoteCommandResult next() {
			return results.size() > 1 ? results.poll() : results.peek();
		}
	}

	private static final class WqlHandler {

		private final String substring;
		private final List<Map<String, Object>> rows;

		private WqlHandler(final String substring, final List<Map<String, Object>> rows) {
			this.substring = substring;
			this.rows = rows;
		}
	}

	private final List<CommandHandler> commandHandlers = new ArrayList<>();
	private final List<WqlHandler> wqlHandlers = new ArrayList<>();

	private final List<String> executedCommands = new ArrayList<>();
	private boolean closed;

	/**
	 * Register command responses: each executed command containing the substring consumes the
	 * next result of the queue (the last result repeats once the queue is exhausted).
	 */
	public ScriptedWindowsRemoteExecutor expectCommand(
		final String substring,
		final WindowsRemoteCommandResult... results
	) {
		commandHandlers.add(new CommandHandler(substring, results));
		return this;
	}

	/** Register the result rows of any WQL query containing the substring. */
	public ScriptedWindowsRemoteExecutor expectWql(final String substring, final List<Map<String, Object>> rows) {
		wqlHandlers.add(new WqlHandler(substring, rows));
		return this;
	}

	/** All the commands executed so far, in order. */
	public List<String> getExecutedCommands() {
		return executedCommands;
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public List<Map<String, Object>> executeWql(final String wqlQuery, final long timeout) {
		return wqlHandlers
			.stream()
			.filter(handler -> wqlQuery.contains(handler.substring))
			.findFirst()
			.map(handler -> handler.rows)
			.orElseThrow(() -> new AssertionError("Unexpected WQL query: " + wqlQuery));
	}

	@Override
	public WindowsRemoteCommandResult executeCommand(
		final String command,
		final String workingDirectory,
		final Charset charset,
		final long timeout
	) {
		executedCommands.add(command);

		return commandHandlers
			.stream()
			.filter(handler -> command.contains(handler.substring))
			.findFirst()
			.map(CommandHandler::next)
			.orElseThrow(() -> new AssertionError("Unexpected command: " + command));
	}

	@Override
	public String getHostname() {
		return "host";
	}

	@Override
	public String getUsername() {
		return "user";
	}

	@Override
	public char[] getPassword() {
		return "pass".toCharArray();
	}

	@Override
	public void close() {
		closed = true;
	}
}
