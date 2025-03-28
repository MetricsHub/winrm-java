# WinRM Java Client

The Windows Remote Management (WinRM) Java Client is a library that enables to:
* Connect to a remote Windows server using one of the two authentication types (NTLM, KERBEROS)
* Execute WMI Query Language (WQL) queries which uses HTTP/HTTPS protocols.

# How to run the WinRM Client inside Java

Add WinRM in the list of dependencies in your [Maven **pom.xml**](https://maven.apache.org/pom.html):

```xml
<dependencies>
	<!-- [...] -->
	<dependency>
		<groupId>${project.groupId}</groupId>
		<artifactId>${project.artifactId}</artifactId>
		<version>${project.version}</version>
	</dependency>
</dependencies>
```

Use it as follows:
```Java
import static java.nio.file.Paths.get;
import static java.util.Collections.singletonList;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTP;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.wql.WinRMWqlExecutor.executeWql;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.metricshub.winrm.exceptions.WinRMException;
import org.metricshub.winrm.exceptions.WqlQuerySyntaxException;
import org.metricshub.winrm.service.client.auth.AuthenticationEnum;
import org.metricshub.winrm.wql.WinRMWqlExecutor;

public class Main {

	public static void main(String[] args) throws WinRMException, WqlQuerySyntaxException, TimeoutException {

		final String wqlQuery = "SELECT Name, Path, Type FROM Win32_Share";
		final String hostname = "my-hostname-or-ip-address";
		final String username = "my-username";
		final char[] password = "my-password".toCharArray();
		final long timeout = 50 * 1000L; // in milliseconds
		final Path ticketCache = get("path");

		// Authentication type : NTLM or KERBEROS
		final List<AuthenticationEnum> authentications = singletonList(NTLM);

		// Execute a WQL Query in the hostname and print the result
		executeWql(HTTP, hostname, 5985, username, password, null, wqlQuery, timeout, ticketCache, authentications)
				.getRows().forEach(System.out::println);

	}
}
```
