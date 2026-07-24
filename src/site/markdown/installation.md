keywords: install, maven, gradle, dependency, standalone, cli, jdk
description: Add the WinRM Java Client to your build, or run the standalone command-line jar.

# Installation

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

## Coordinates

The library is published on
[Maven Central](https://central.sonatype.com/artifact/${project.groupId}/${project.artifactId}) under:

| Field | Value |
| --- | --- |
| `groupId` | `${project.groupId}` |
| `artifactId` | `${project.artifactId}` |
| `version` | `${project.version}` |

## Add it to your build

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

For other build tools, the [dependency information](dependency-info.html) report lists the snippet
for Ivy, SBT, Leiningen, and others.

## Supported JDKs

The library targets **Java 11** and runs on any later JDK.

## Runtime dependencies

Since 2.0.0 the client has **zero runtime dependencies**. There is no longer an Apache CXF /
JAX-WS / JAXB stack, no BouncyCastle, and no SMB stack (`smbj`) on the classpath — the client speaks
WS-Management over the JDK's own HTTP and XML APIs, and copies files through the WinRM channel
itself. If you upgraded from 1.x, see [Migrating from 1.x](migrating-from-1x.html) for the details
and the behavior changes this implies.

## Standalone command-line jar

Every release also ships a self-contained executable jar that bundles the client and a small CLI.
Download `${project.artifactId}-${project.version}-standalone.jar` from the
[latest release](https://github.com/metricshub/winrm-java/releases/latest), then run it with Java:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar --help
```

Run a WQL query:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  --hostname server.example.com --username 'DOMAIN\user' \
  --password-file password.txt --ntlm \
  wql 'SELECT Name, State FROM Win32_Service'
```

Run a remote command (`cmd`, `exec`, and `run` are aliases for `command`):

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u Administrator -pf password.txt --https \
  exec ipconfig /all
```

The CLI is covered in more detail throughout the [Usage](wql.html) pages; `--version` prints the
build version.

## Where to go next

* [WQL Queries](wql.html)
* [Remote Commands](commands.html)
* [Authentication](authentication.html)
