# WinRM Java Client

![GitHub release (with filter)](https://img.shields.io/github/v/release/metricshub/winrm-java)
![Build](https://img.shields.io/github/actions/workflow/status/metricshub/winrm-java/deploy.yml)
![GitHub top language](https://img.shields.io/github/languages/top/metricshub/winrm-java)
![License](https://img.shields.io/github/license/metricshub/winrm-java)

This project uses [WS-Man Client](https://github.com/OpenNMS/wsman) and [winrm4j](https://github.com/cloudsoft/winrm4j/)

See **[Project Documentation](https://metricshub.org/winrm-java)** and the [Javadoc](https://metricshub.org/winrm-java/apidocs) for more information on how to use this library in your code.

The Windows Remote Management (WinRM) Java Client is a library that enables to:
* Connect to a remote Windows server using one of the two authentication types (NTLM, KERBEROS)
* Execute WMI Query Language (WQL) queries which uses HTTP/HTTPS protocols.

> ## ⚠️ Upgrading from 1.x
>
> Version 2.0.0 **removed the legacy Apache CXF backend**: the dependency-free **light** client is
> the only implementation (same public API — calling code is unaffected). Two consequences:
>
> * Unlike the CXF-based client, which silently trusted every TLS certificate, the light client
>   **validates the server certificate and verifies the hostname by default**.
>   **WinRM-over-HTTPS connections to hosts with self-signed or otherwise untrusted certificates
>   will fail** during the TLS handshake unless you install the server certificate (or its issuing
>   CA) into a Java trust store (e.g. `-Djavax.net.ssl.trustStore=...`) or disable TLS validation
>   with `-Dorg.metricshub.winrm.tls.insecure=true` (**insecure — for testing only**).
> * Setting `-Dorg.metricshub.winrm.backend=cxf` now fails with a clear error instead of selecting
>   the removed backend. Remove the property (or stay on winrm-java 1.x).

## The WinRM client

The client has **zero runtime dependencies** (no Apache CXF / JAX-WS / JAXB, no BouncyCastle, no
SLF4J — problems are reported through exceptions only) and is immune by construction to JAXP
`ServiceLoader` conflicts (it uses the JDK-default XML factories). It supports **NTLM over HTTP
(with message encryption) and HTTPS** and **Kerberos (SPNEGO) over HTTPS**.

Files listed in `localFileToCopyList` are copied to the remote host **through the WinRM channel
itself** (chunked base64 through the command shell, decoded with `certutil` and verified with a
digest): no SMB, no TCP port 445, no administrative share — and it works from any client OS. A
file already present on the remote host with an identical digest is not transferred again. This
transport is designed for small script files, not bulk data. Over HTTPS it
validates the certificate and verifies the hostname by default (see the upgrade warning above);
`-Dorg.metricshub.winrm.tls.insecure=true` trusts all certificates (insecure, testing only).
Kerberos uses the ambient Kerberos configuration (`krb5.conf` / `-Djava.security.krb5.*`) unless
the command-line KDC and realm options described below are used.

## Command-line client

Every build produces the regular library JAR and an additional self-contained executable:

```text
target/winrm-java-<version>.jar
target/winrm-java-<version>-standalone.jar
```

Run a WQL query:

```bash
java -jar target/winrm-java-<version>-standalone.jar \
  --hostname server.example.net --username 'DOMAIN\user' \
  --password-file password.txt --ntlm \
  wql 'SELECT Name,State FROM Win32_Service'
```

Run a remote command (`cmd`, `exec`, and `run` are aliases for `command`):

```bash
java -jar target/winrm-java-<version>-standalone.jar \
  -h server.example.net -u Administrator -pf password.txt --https \
  exec ipconfig /all
```

Use `--help` for the complete option list and `--version` for the build version. HTTP is the
default transport and uses port 5985; `--https` uses port 5986. `-P`/`--port` overrides either
default, and `-t`/`--timeout` sets the operation timeout in milliseconds (60,000 by default).

NTLM is used when neither authentication flag is supplied. `--ntlm` and `--kerberos` are mutually
exclusive. Kerberos requires HTTPS. By default it uses the ambient JDK Kerberos configuration. The
CLI can instead configure the JDK for the current invocation with `--kerberos-kdc <host>`. If no
`--kerberos-realm <realm>` is supplied, the realm is inferred by removing the KDC hostname's first
DNS label and uppercasing the remaining suffix. For example:

```bash
java -jar target/winrm-java-<version>-standalone.jar \
  -h server.internal.sentrysoftware.net -u 'DOMAIN\user' -pf password.txt \
  --https --kerberos --kerberos-kdc camus.internal.sentrysoftware.net \
  command whoami
```

This infers `INTERNAL.SENTRYSOFTWARE.NET`. The inference follows a common Active Directory DNS
naming convention; it is not guaranteed by Kerberos. Specify `--kerberos-realm` when the realm does
not match the KDC's DNS suffix or when the KDC is not a fully qualified DNS name. Both options are
valid only with `--kerberos`, and `--kerberos-realm` requires `--kerberos-kdc`.

HTTPS validates the certificate and hostname by default. `--https-permissive` trusts any
certificate and hostname; it is intentionally insecure and should only be used for testing or
isolated hosts.

`-p`/`--password` is convenient for interactive use, but command-line arguments may be visible to
other local processes. Prefer `-pf`/`--password-file` for automation. Password files are decoded as
UTF-8. Exactly one final LF, CRLF, or CR is removed; all other bytes, including whitespace and
earlier line endings, are part of the password. The two password options are mutually exclusive.
If neither is supplied, the CLI securely requests the password from the interactive console without
echoing it. Non-interactive runs must use `--password-file` (or, less securely, `--password`).

WQL writes one compact UTF-8 JSON object per row to stdout
([JSON Lines](https://jsonlines.org/)); property order follows the WinRM response. Diagnostics go
only to stderr. Remote command stdout and stderr are forwarded to the corresponding local streams.
The current backend buffers an operation's result; the CLI output boundary is ready to consume the
streaming API when it becomes available.

Exit behavior is stable:

| Exit code | Meaning |
| ---: | --- |
| `0` | Successful WQL query or remote command |
| `0`–`255` | Remote command exit code, when representable |
| `64` | Invalid CLI usage |
| `69` | Connection, DNS, socket, or TLS failure |
| `70` | WinRM protocol or other remote failure |
| `77` | Authentication failure |
| `124` | Operation timeout |

## Build instructions

This is a simple Maven project. Build with:

```bash
mvn verify
```

### Protocol tests

The build includes in-process protocol tests (`WsmanProtocolTest`) that exercise the client's
full WSMan path — NTLM handshake, message encryption, `multipart/encrypted` framing, WQL
Enumerate/Pull paging, the command shell lifecycle, and fault mapping — against a fake WSMan
server, so no Windows host is needed in CI.

### Live run against a real host

`WinRMLiveTest` runs a WQL query and a command against a **real** WinRM host (the successor of
the pre-2.0.0 CXF-vs-light differential harness). It is skipped unless `winrm.live.host` is set:

```bash
mvn test -Dtest=WinRMLiveTest \
  -Dwinrm.live.host=myhost.example.com \
  -Dwinrm.live.protocol=https \
  -Dwinrm.live.username='MYDOMAIN\myuser' \
  -Dwinrm.live.password-file=/path/to/password.txt
```

Optional properties: `winrm.live.port`, `winrm.live.password` (inline), `winrm.live.namespace`,
`winrm.live.wql`, `winrm.live.command`, and `winrm.live.tls.insecure=true` (skip TLS validation
for hosts with self-signed certificates).

## Release instructions

The artifact is deployed to Sonatype's [Maven Central](https://central.sonatype.com/).

The actual repository URL is https://s01.oss.sonatype.org/, with server Id `ossrh` and requires credentials to deploy
artifacts manually.

But it is strongly recommended to only use [GitHub Actions "Release to Maven Central"](actions/workflows/release.yml) to perform a release:

* Manually trigger the "Release" workflow
* Specify the version being released and the next version number (SNAPSHOT)
* Release the corresponding staging repository on [Sonatype's Nexus server](https://s01.oss.sonatype.org/)
* Merge the PR that has been created to prepare the next version

## License

License is Apache-2. Each source file must include the Apache-2 header (build will fail otherwise).
To update source files with the proper header, simply execute the below command:

```bash
mvn license:update-file-header
```
