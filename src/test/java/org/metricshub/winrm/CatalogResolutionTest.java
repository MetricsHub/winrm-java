package org.metricshub.winrm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.catalog.OASISCatalogManager;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the JAX-WS catalog shipped at META-INF/jax-ws-catalog.xml is
 * auto-discovered by Apache CXF and remaps the absolute schema URLs referenced
 * by wsdl/WinRM.wsdl to local classpath resources.
 *
 * <p>Without this mapping the WSDL loader fetches dsp8033 / dsp8034 / xml.xsd /
 * ws-addr.xsd from schemas.dmtf.org / www.w3.org over the network. On offline
 * or restricted-egress hosts that fetch blocks for ~75 s (OS TCP timeout) and
 * the connection attempt fails with
 * {@code WSDLException(PARSER_ERROR) ... Caused by: ConnectException}.
 */
class CatalogResolutionTest {

	@Test
	void catalogResolvesWsdlImportUrlsToClasspathResources() throws Exception {
		final Bus bus = BusFactory.getDefaultBus(true);
		final OASISCatalogManager catalog = OASISCatalogManager.getCatalogManager(bus);
		assertNotNull(catalog, "CXF OASISCatalogManager must be available");

		assertResolvesToClasspath(catalog, "http://schemas.dmtf.org/wbem/wsman/1/dsp8033_1.0.xsd", "dsp8033_1.0.xsd");
		assertResolvesToClasspath(catalog, "http://schemas.dmtf.org/wbem/wsman/1/dsp8034_1.0.xsd", "dsp8034_1.0.xsd");
		assertResolvesToClasspath(catalog, "http://www.w3.org/2001/xml.xsd", "xml.xsd");
		assertResolvesToClasspath(catalog, "http://www.w3.org/2006/03/addressing/ws-addr.xsd", "ws-addr.xsd");
	}

	private static void assertResolvesToClasspath(
		final OASISCatalogManager catalog,
		final String systemId,
		final String expectedSuffix
	) throws Exception {
		final String resolved = catalog.resolveSystem(systemId);
		assertNotNull(resolved, "catalog did not resolve " + systemId);
		// Must NOT be a network URL — otherwise CXF will still hit the network at runtime.
		assertFalse(
			resolved.startsWith("http://") || resolved.startsWith("https://"),
			"catalog returned a network URL for " + systemId + " -> " + resolved
		);
		assertTrue(resolved.endsWith(expectedSuffix), "resolved URI does not end with " + expectedSuffix + ": " + resolved);
	}
}
