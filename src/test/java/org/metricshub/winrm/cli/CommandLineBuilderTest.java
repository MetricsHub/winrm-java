package org.metricshub.winrm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandLineBuilderTest {

	@Test
	void joinsSimpleArguments() {
		assertEquals("ipconfig /all", CommandLineBuilder.join(List.of("ipconfig", "/all")));
	}

	@Test
	void quotesArgumentsWithSpacesAndEmptyArguments() {
		assertEquals("echo \"hello world\" \"\"", CommandLineBuilder.join(List.of("echo", "hello world", "")));
	}

	@Test
	void escapesQuotesAndTrailingBackslashesUsingWindowsRules() {
		assertEquals(
			"program \"say \\\"hello\\\"\" \"C:\\Program Files\\\\\"",
			CommandLineBuilder.join(List.of("program", "say \"hello\"", "C:\\Program Files\\"))
		);
	}
}
