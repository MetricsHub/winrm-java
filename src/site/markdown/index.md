keywords: winrm java client, windows remote management, wsman, dependency-free, overview
description: A dependency-free Java client for Windows Remote Management (WinRM): run WQL queries and remote commands over NTLM or Kerberos.

# WinRM Java Client

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

## Overview

The **WinRM Java Client** is a small library that talks to the Windows Remote Management
(WS-Management) service on a remote Windows host. It lets a Java application:

* run **WQL / WMI queries** such as `SELECT Name, State FROM Win32_Service` and read the rows back
  ([WQL Queries](wql.html)), and
* **execute remote commands**, capturing standard output, standard error and the exit code —
  optionally copying local script files to the host first ([Remote Commands](commands.html)).

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

Everything starts with the static
[`WinRMWqlExecutor.executeWql(...)`](apidocs/org/metricshub/winrm/wql/WinRMWqlExecutor.html) method:

```java
import static java.util.Collections.singletonList;
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTP;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;
import static org.metricshub.winrm.wql.WinRMWqlExecutor.executeWql;

import org.metricshub.winrm.wql.WinRMWqlExecutor;

public class Example {

    public static void main(String[] args) throws Exception {

        WinRMWqlExecutor result = executeWql(
            HTTP,                                   // protocol (HTTP or HTTPS)
            "server.example.com",                   // hostname (mandatory)
            5985,                                   // port (null for the protocol default)
            "DOMAIN\\Administrator",                // username (DOMAIN\user or user)
            "the-password".toCharArray(),           // password
            null,                                   // namespace (null → ROOT\CIMV2)
            "SELECT Name, State FROM Win32_Service", // WQL query
            30_000L,                                // timeout in milliseconds
            null,                                   // Kerberos ticket cache (null for NTLM)
            singletonList(NTLM)                     // authentication schemes
        );

        System.out.println(result.getHeaders());        // [Name, State]
        result.getRows().forEach(System.out::println);  // one List<String> per row
    }
}
```

## Where to go next

* [Installation](installation.html) — coordinates, supported JDKs, and the standalone CLI jar
* [WQL Queries](wql.html) — query WMI and read the result
* [Remote Commands](commands.html) — run commands and copy files to the host
* [Authentication](authentication.html) — NTLM and Kerberos
* [TLS / HTTPS](tls.html) — certificate validation and trust stores
* [Timeouts and Errors](timeouts-and-errors.html) — timeout semantics and the exception surface
* [Migrating from 1.x](migrating-from-1x.html) — the 2.0.0 breaking changes
