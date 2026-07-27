package org.metricshub.winrm.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Pins the light backend's WSMan response handling to the behavior of the legacy CXF backend
 * (issue #106: feature parity and fault mapping): EndOfSequence/Items namespace variants, and
 * fault summaries that carry the WSManFault detail message (with its WBEM_E_* mnemonics).
 */
class WsmanClientParityTest {

	private static final String WSEN = "http://schemas.xmlsoap.org/ws/2004/09/enumeration";
	private static final String WSMAN = "http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd";

	private static Document parse(final String xml) throws Exception {
		return WsmanClient.parse(xml.getBytes(StandardCharsets.UTF_8));
	}

	// --- EndOfSequence variants ---------------------------------------------

	@Test
	void endOfSequenceDetectedInWsEnumerationNamespace() throws Exception {
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><wsen:PullResponse xmlns:wsen=\"" +
				WSEN +
				"\">" +
				"<wsen:EndOfSequence/>" +
				"</wsen:PullResponse></s:Body></s:Envelope>"
		);
		assertTrue(WsmanClient.hasEnumerationElement(doc, "EndOfSequence"));
	}

	@Test
	void endOfSequenceDetectedInWsmanNamespaceVariant() throws Exception {
		// WinRM can emit the marker in its own WSMan namespace instead of WS-Enumeration; the CXF
		// backend accepts both variants, so the light backend must too.
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><wsen:EnumerateResponse xmlns:wsen=\"" +
				WSEN +
				"\">" +
				"<wsman:EndOfSequence xmlns:wsman=\"" +
				WSMAN +
				"\"/>" +
				"</wsen:EnumerateResponse></s:Body></s:Envelope>"
		);
		assertTrue(WsmanClient.hasEnumerationElement(doc, "EndOfSequence"));
	}

	@Test
	void wmiPropertyNamedEndOfSequenceIsNotMistakenForTheMarker() throws Exception {
		// A WMI property that happens to be called EndOfSequence lives in the class's own namespace
		// and must not terminate the enumeration.
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><wsen:PullResponse xmlns:wsen=\"" +
				WSEN +
				"\">" +
				"<wsman:Items xmlns:wsman=\"" +
				WSMAN +
				"\">" +
				"<p:MyClass xmlns:p=\"http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/MyClass\">" +
				"<p:EndOfSequence>oops</p:EndOfSequence>" +
				"</p:MyClass>" +
				"</wsman:Items>" +
				"</wsen:PullResponse></s:Body></s:Envelope>"
		);
		assertFalse(WsmanClient.hasEnumerationElement(doc, "EndOfSequence"));
	}

	// --- Items variants and row extraction ----------------------------------

	@Test
	void itemsCollectedFromBothNamespaceVariants() throws Exception {
		// EnumerateResponse carries wsen:Items, PullResponse carries wsman:Items; both must yield rows.
		final String instance = "<p:Win32_Service xmlns:p=\"http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/Win32_Service\">"
			+
			"<p:Name>Spooler</p:Name>" +
			"<p:State>Running</p:State>" +
			"</p:Win32_Service>";
		final Document wsenItems = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><wsen:EnumerateResponse xmlns:wsen=\"" +
				WSEN +
				"\">" +
				"<wsen:Items>" +
				instance +
				"</wsen:Items>" +
				"</wsen:EnumerateResponse></s:Body></s:Envelope>"
		);
		final Document wsmanItems = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><wsen:PullResponse xmlns:wsen=\"" +
				WSEN +
				"\">" +
				"<wsman:Items xmlns:wsman=\"" +
				WSMAN +
				"\">" +
				instance +
				"</wsman:Items>" +
				"</wsen:PullResponse></s:Body></s:Envelope>"
		);

		for (final Document doc : List.of(wsenItems, wsmanItems)) {
			final List<Map<String, String>> rows = new ArrayList<>();
			WsmanClient.collectItems(doc, rows);
			assertEquals(1, rows.size());
			assertEquals("Spooler", rows.get(0).get("Name"));
			assertEquals("Running", rows.get(0).get("State"));
		}
	}

	@Test
	void wmiPropertyNamedItemsIsNotMistakenForTheWrapper() throws Exception {
		// A structured WMI property called Items (in the class's namespace) must not be read as a
		// second Items wrapper producing phantom rows.
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><wsen:PullResponse xmlns:wsen=\"" +
				WSEN +
				"\">" +
				"<wsman:Items xmlns:wsman=\"" +
				WSMAN +
				"\">" +
				"<p:MyClass xmlns:p=\"http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/MyClass\">" +
				"<p:Name>real-row</p:Name>" +
				"<p:Items><p:Nested>phantom</p:Nested></p:Items>" +
				"</p:MyClass>" +
				"</wsman:Items>" +
				"</wsen:PullResponse></s:Body></s:Envelope>"
		);
		final List<Map<String, String>> rows = new ArrayList<>();
		WsmanClient.collectItems(doc, rows);
		assertEquals(1, rows.size());
		assertEquals("real-row", rows.get(0).get("Name"));
	}

	// --- Fault summaries ------------------------------------------------------

	@Test
	void faultSummaryCarriesWsmanFaultDetailWithWbemMnemonic() throws Exception {
		// MetricsHub tells an "acceptable" WMI error (bad class/namespace) from a broken connection by
		// matching WBEM_E_* in the exception message — the detail Message text must surface.
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><s:Fault>" +
				"<s:Reason><s:Text xml:lang=\"en-US\">The WS-Management service cannot process the request.</s:Text></s:Reason>"
				+
				"<s:Detail>" +
				"<f:WSManFault xmlns:f=\"http://schemas.microsoft.com/wbem/wsman/1/wsmanfault\" Code=\"2150858778\">" +
				"<f:Message>The WMI service or the WMI provider returned an unknown error: WBEM_E_INVALID_CLASS</f:Message>" +
				"</f:WSManFault>" +
				"</s:Detail>" +
				"</s:Fault></s:Body></s:Envelope>"
		);
		final String summary = WsmanClient.faultSummary(500, doc);
		assertTrue(summary.contains("HTTP 500"), summary);
		assertTrue(summary.contains("WSManFault 2150858778"), summary);
		assertTrue(summary.contains("The WS-Management service cannot process the request."), summary);
		assertTrue(summary.contains("WBEM_E_INVALID_CLASS"), summary);
	}

	@Test
	void faultSummaryDoesNotDuplicateDetailAlreadyInReason() throws Exception {
		// WinRM usually repeats the same text in Reason and in the WSManFault Message; it must appear once.
		final String message = "The WMI service or the WMI provider returned an unknown error: WBEM_E_INVALID_NAMESPACE";
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><s:Fault>" +
				"<s:Reason><s:Text xml:lang=\"en-US\">" +
				message +
				" </s:Text></s:Reason>" +
				"<s:Detail>" +
				"<f:WSManFault xmlns:f=\"http://schemas.microsoft.com/wbem/wsman/1/wsmanfault\" Code=\"2150858778\">" +
				"<f:Message>" +
				message +
				" </f:Message>" +
				"</f:WSManFault>" +
				"</s:Detail>" +
				"</s:Fault></s:Body></s:Envelope>"
		);
		final String summary = WsmanClient.faultSummary(500, doc);
		final int first = summary.indexOf("WBEM_E_INVALID_NAMESPACE");
		final int last = summary.lastIndexOf("WBEM_E_INVALID_NAMESPACE");
		assertTrue(first >= 0, summary);
		assertEquals(first, last, "detail text must not be duplicated: " + summary);
	}

	@Test
	void faultSummaryWithoutWsmanFaultDetailStillReadable() throws Exception {
		final Document doc = parse(
			"<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">" +
				"<s:Body><s:Fault>" +
				"<s:Reason><s:Text xml:lang=\"en-US\">Some transport-level failure</s:Text></s:Reason>" +
				"</s:Fault></s:Body></s:Envelope>"
		);
		assertEquals("HTTP 500: Some transport-level failure", WsmanClient.faultSummary(500, doc));
	}

	// --- OperationTimeout format ---------------------------------------------

	@Test
	void operationTimeoutFormattedLikeCxf() {
		// The CXF backend formats the WSMan OperationTimeout with DecimalFormat("PT#.###S", ROOT):
		// millisecond precision, '.' separator, no trailing zeros. Same bytes on the wire from light.
		assertTrue(timeoutOf(30000L).contains("<wsman:OperationTimeout>PT30S</wsman:OperationTimeout>"));
		assertTrue(timeoutOf(1500L).contains("<wsman:OperationTimeout>PT1.5S</wsman:OperationTimeout>"));
		assertTrue(timeoutOf(1234L).contains("<wsman:OperationTimeout>PT1.234S</wsman:OperationTimeout>"));
		assertTrue(timeoutOf(90061L).contains("<wsman:OperationTimeout>PT90.061S</wsman:OperationTimeout>"));
	}

	private static String timeoutOf(final long timeoutMs) {
		return Envelopes.enumerateWql(
			"http://host:5985/wsman",
			"root/cimv2",
			"SELECT * FROM Win32_OperatingSystem",
			timeoutMs,
			32000
		);
	}
}
