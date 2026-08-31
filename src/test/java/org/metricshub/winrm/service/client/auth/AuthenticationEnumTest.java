package org.metricshub.winrm.service.client.auth;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.metricshub.winrm.Utils.EMPTY;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.BASIC;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.KERBEROS;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.getValueOf;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class AuthenticationEnumTest {

	@Test
	void testGetValueOf() {
		assertEquals(empty(), getValueOf(null));
		assertEquals(empty(), getValueOf(EMPTY));
		assertEquals(empty(), getValueOf("unknown"));
		assertEquals(of(NTLM), getValueOf(" ntlm "));
		assertEquals(of(NTLM), getValueOf(" Ntlm "));
		assertEquals(of(NTLM), getValueOf(" NTLM "));
		assertEquals(of(KERBEROS), getValueOf(" kerberos "));
		assertEquals(of(KERBEROS), getValueOf(" Kerberos "));
		assertEquals(of(KERBEROS), getValueOf(" KERBEROS "));
		assertEquals(of(BASIC), getValueOf(" basic "));
		assertEquals(of(BASIC), getValueOf(" Basic "));
		assertEquals(of(BASIC), getValueOf(" BASIC "));
	}

	@Test
	void testGetValueOfIsInsensitiveToTheDefaultLocale() {
		// With the default locale set to Turkish, the locale-sensitive toUpperCase() mangles "basic"
		// into "BASİC" (dotted capital I), which does not match any enum name. The lookup must
		// resolve regardless of the JVM default locale.
		final Locale original = Locale.getDefault();
		Locale.setDefault(new Locale("tr"));
		try {
			assertEquals(of(BASIC), getValueOf("basic"));
			assertEquals(of(KERBEROS), getValueOf("kerberos"));
			assertEquals(of(NTLM), getValueOf("ntlm"));
		} finally {
			Locale.setDefault(original);
		}
	}
}
