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

> ## ⚠️ Upgrade warning
>
> The **light** backend is now the **default**, and — unlike the previous CXF-based client, which
> silently trusted every TLS certificate — it **validates the server certificate and verifies the
> hostname by default**. **WinRM-over-HTTPS connections to hosts with self-signed or otherwise
> untrusted certificates will now fail** during the TLS handshake unless you do one of:
>
> * install the server certificate (or its issuing CA) into a Java trust store (e.g. `-Djavax.net.ssl.trustStore=...`);
> * disable TLS validation with `-Dorg.metricshub.winrm.tls.insecure=true` (**insecure — for testing only**); or
> * select the legacy CXF backend with `-Dorg.metricshub.winrm.backend=cxf`.
>
> The CXF backend stays available through that property for now and will be **removed in a future major release**.

## WinRM backends

The library ships two interchangeable backends, both implementing the same public API so calling code is unaffected by the choice:

* **light** (default) — a dependency-free client (no Apache CXF / JAX-WS / JAXB), immune by construction to JAXP `ServiceLoader` conflicts (it uses the JDK-default XML factories). Supports **NTLM over HTTP and HTTPS** and **Kerberos (SPNEGO) over HTTPS**. Over HTTPS it validates the certificate and verifies the hostname by default (see the upgrade warning above); `-Dorg.metricshub.winrm.tls.insecure=true` trusts all certificates (insecure, testing only). Kerberos uses the ambient Kerberos configuration (`krb5.conf` / `-Djava.security.krb5.*`).
* **cxf** — the mature CXF-based backend, still available during the transition and scheduled for removal in a future major release.

Select the backend with the `org.metricshub.winrm.backend` system property (`light` is the default):

```bash
# opt into the legacy CXF backend
java -Dorg.metricshub.winrm.backend=cxf ...
```

## Build instructions

This is a simple Maven project. Build with:

```bash
mvn verify
```

### Protocol tests

The build includes in-process protocol tests (`WsmanProtocolTest`) that exercise the light
backend's full WSMan path — NTLM handshake, message encryption, `multipart/encrypted` framing,
WQL Enumerate/Pull paging, the command shell lifecycle, and fault mapping — against a fake WSMan
server, so no Windows host is needed in CI.

### Differential run against a real host

`BackendDifferentialTest` runs the same operations through the legacy CXF backend and the light
backend against a **real** WinRM host and asserts the results match. It is skipped unless
`winrm.diff.host` is set:

```bash
mvn test -Dtest=BackendDifferentialTest -Dmaven.javadoc.skip=true \
  -Dwinrm.diff.host=myhost.example.com \
  -Dwinrm.diff.protocol=https \
  -Dwinrm.diff.username='MYDOMAIN\myuser' \
  -Dwinrm.diff.password-file=/path/to/password.txt
```

Optional properties: `winrm.diff.port`, `winrm.diff.password` (inline), `winrm.diff.namespace`,
`winrm.diff.wql`, `winrm.diff.command`, `winrm.diff.badcreds=true` (also compare wrong-password
error messages; off by default because it triggers failed logons), and
`winrm.diff.tls.insecure=false` (validate TLS on the light backend instead of matching the CXF
backend's trust-all behavior).

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
