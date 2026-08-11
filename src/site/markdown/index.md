keywords: winrm java client, windows remote management, wsman, dependency-free, overview
description: A dependency-free Java client for Windows Remote Management (WinRM): run WQL queries and remote commands over NTLM or Kerberos.

# WinRM Java Client

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

## Overview

The **WinRM Java Client** is a small library that talks to the Windows Remote Management
(WS-Management) service on a remote Windows host. It lets a Java application:

* run **WQL / WMI queries** such as `SELECT Name, State FROM Win32_Service` and read the rows back
  ([WQL Queries](wql.html)), and
* **execute remote commands** — `cmd.exe` command lines or PowerShell scripts — capturing standard
  output, standard error and the exit code, optionally copying local script files to the host
  first ([Remote Commands](commands.html)).

Both operations can also **stream**: WQL rows are consumed page by page as they arrive
(`stream()`), and command output is consumed while the command is still running (`start()`,
returning a `java.lang.Process`-like handle) — memory stays bounded regardless of the result
size.

It supports **NTLM** over HTTP (with message encryption) and HTTPS, and **Kerberos (SPNEGO)** over
HTTPS ([Authentication](authentication.html)).

Since 2.0.0 the client has **zero runtime dependencies** (no Apache CXF / JAX-WS / JAXB stack, no SMB
stack) and is immune by construction to JAXP `ServiceLoader` conflicts, because it uses the
JDK-default XML factories. Problems are reported through exceptions only — the library pulls in no
logging framework.

> [!WARNING]
> **Upgrading from 1.x?** Version 2.0.0 removed the legacy Apache CXF backend and now
> **validates TLS certificates and verifies hostnames by default**. If you connect over HTTPS to
> hosts with self-signed certificates, read [Migrating from 1.x](migrating-from-1x.html) first.

## Add the dependency

The library is published on [Maven Central](https://central.sonatype.com/artifact/${project.groupId}/${project.artifactId}).

> [!TABS]
> * Maven
>   ```xml
>   <dependency>
>     <groupId>${project.groupId}</groupId>
>     <artifactId>${project.artifactId}</artifactId>
>     <version>${project.version}</version>
>   </dependency>
>   ```
> * Gradle (Groovy)
>   ```groovy
>   implementation '${project.groupId}:${project.artifactId}:${project.version}'
>   ```
> * Gradle (Kotlin)
>   ```kotlin
>   implementation("${project.groupId}:${project.artifactId}:${project.version}")
>   ```

See [Installation](installation.html) for the coordinates, the supported JDKs, and the standalone
command-line jar.

## A first WQL query

> [!NOTE]
> **On the target host**, WinRM must be enabled and the account must have sufficient privileges.
> Windows Server 2012 and later have WinRM enabled by default and an administrator account works
> with no configuration; Windows 10 / 11, non-administrator accounts, and local (non-domain)
> administrator accounts all need host-side setup. See
> [Preparing the Windows Host](preparing-the-host.html).

Everything starts with the fluent
[`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html) builder — one client authenticates
once and can run any number of queries and commands over the same connection:

```java
import java.time.Duration;
import org.metricshub.winrm.WinRMClient;
import org.metricshub.winrm.WqlResult;
import org.metricshub.winrm.WqlRow;

public class Example {

    public static void main(String[] args) {
        try (WinRMClient client = WinRMClient.builder("server.example.com")
                .credentials("DOMAIN\\Administrator", "the-password".toCharArray())
                .timeout(Duration.ofSeconds(30))
                .build()) {

            WqlResult result = client.wql("SELECT Name, State FROM Win32_Service").execute();

            System.out.println(result.columns());       // [Name, State]
            for (WqlRow row : result) {
                System.out.println(row.string("Name") + " is " + row.string("State"));
            }
        }
    }
}
```

Remote commands work the same way:

```java
CommandResult result = client.command("ipconfig /all").execute();
System.out.println(result.stdout());
```

And so do PowerShell scripts — delivered base64-encoded (`-EncodedCommand`), so they need no
quoting or escaping at all:

```java
CommandResult ps = client.powerShell("Get-Service | Where-Object Status -eq 'Running'").execute();
```

Failures are reported through the unchecked
[`WinRMClientException`](apidocs/org/metricshub/winrm/exceptions/WinRMClientException.html)
hierarchy. The static one-shot helpers that predate `WinRMClient`
([`WinRMWqlExecutor.executeWql(...)`](apidocs/org/metricshub/winrm/wql/WinRMWqlExecutor.html),
[`WinRMCommandExecutor.execute(...)`](apidocs/org/metricshub/winrm/command/WinRMCommandExecutor.html))
remain available and unchanged, with their checked exceptions.

## Where to go next

* [Installation](installation.html) — coordinates, supported JDKs, and the standalone CLI jar
* [Preparing the Windows Host](preparing-the-host.html) — prerequisites on the target: enabling WinRM
  and the privileges the account needs
* [WQL Queries](wql.html) — query WMI and read the result
* [Remote Commands](commands.html) — run commands and copy files to the host
* [File Transfers](file-transfers.html) — how files are copied through the WinRM channel
* [Command-Line Client](cli.html) — the standalone jar's manual page
* [Authentication](authentication.html) — NTLM and Kerberos
* [TLS / HTTPS](tls.html) — certificate validation and trust stores
* [Timeouts and Errors](timeouts-and-errors.html) — timeout semantics and the exception surface
* [Migrating from 1.x](migrating-from-1x.html) — the 2.0.0 breaking changes, and moving to the fluent API
* [Migrating from winrm4j](migrating-from-winrm4j.html) — moving from cloudsoft/winrm4j: option mapping and behavioral differences
* [Legacy API](legacy.html) — the static one-shot helpers that predate `WinRMClient`
