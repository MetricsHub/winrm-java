package org.metricshub.winrm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonLinesWriterTest {

	@Test
	void writesOrderedEscapedJsonValues() throws Exception {
		final Map<String, Object> row = new LinkedHashMap<>();
		row.put("Name", "Spooler");
		row.put("Label", "Café 東京");
		row.put("Path", "C:\\Windows\nSystem32");
		row.put("Missing", null);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.US_ASCII.name())) {
			JsonLinesWriter.write(row, output);
		}

		assertEquals(
			"{\"Name\":\"Spooler\",\"Label\":\"Café 東京\",\"Path\":\"C:\\\\Windows\\nSystem32\",\"Missing\":null}" +
				System.lineSeparator(),
			bytes.toString(StandardCharsets.UTF_8.name())
		);
	}
}
