# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### ⚠️ Upgrade warning — WinRM over HTTPS with self-signed certificates

The new dependency-free **light** backend is now the **default**. Unlike the previous CXF-based
client — which silently trusted every TLS certificate and skipped hostname verification — the light
backend **validates the server certificate and verifies the hostname by default**.

As a result, **WinRM-over-HTTPS connections to hosts with self-signed or otherwise untrusted
certificates that worked with earlier versions will now fail** during the TLS handshake. To restore
connectivity, do one of:

- install the server certificate (or its issuing CA) into a Java trust store
  (`-Djavax.net.ssl.trustStore=...`);
- disable TLS validation with `-Dorg.metricshub.winrm.tls.insecure=true`
  (**insecure — for testing only**); or
- select the legacy CXF backend with `-Dorg.metricshub.winrm.backend=cxf`.

### Added

- Dependency-free "light" WinRM backend with no Apache CXF / JAX-WS / JAXB stack, immune by
  construction to JAXP `ServiceLoader` conflicts (it uses the JDK-default XML factories). Supports
  NTLM over HTTP and HTTPS, and Kerberos (SPNEGO, via the JDK GSS-API) over HTTPS.
- `org.metricshub.winrm.backend` system property to choose the backend (`light` — default — or `cxf`).
- `org.metricshub.winrm.tls.insecure` system property to trust all TLS certificates and skip hostname
  verification on the light backend (insecure — for testing only).

### Changed

- The **light** backend is now the default; the CXF backend is opt-in via
  `org.metricshub.winrm.backend=cxf`.
- HTTPS connections validate certificates and verify hostnames by default (see the upgrade warning).
- The light backend's exception surface now matches the CXF backend (feature parity, #106):
  authentication rejections raise the same `Authentication error on <endpoint> with user name "<user>"`
  message, operations on a closed executor raise the same `IllegalStateException` message, the WSMan
  `OperationTimeout` header uses the same `PT#.###S` millisecond-precision format, and the
  `EndOfSequence` / `Items` enumeration markers are recognized in both their WS-Enumeration and WSMan
  namespace variants. WSMan fault exceptions additionally carry the detailed `WSManFault` message
  (including the provider-level detail, e.g. WMI `WBEM_E_*` mnemonics) alongside the SOAP reason text.

### Deprecated

- The CXF-based backend is deprecated and will be **removed in a future major release**.
