# Changelog

All notable changes to this project are documented in this file.

## [Unreleased] — 2.0.0

### ⚠️ Breaking — the CXF backend was removed

Version 2.0.0 removes the legacy Apache CXF backend. The dependency-free client introduced in the
previous release is the only implementation; the public API is unchanged, so calling code is
unaffected. Consequences:

- **WinRM over HTTPS with self-signed certificates**: unlike the CXF-based client — which silently
  trusted every TLS certificate and skipped hostname verification — this client **validates the
  server certificate and verifies the hostname by default**. Connections to hosts with self-signed
  or otherwise untrusted certificates **fail** during the TLS handshake unless you:
  - install the server certificate (or its issuing CA) into a Java trust store
    (`-Djavax.net.ssl.trustStore=...`); or
  - disable TLS validation with `-Dorg.metricshub.winrm.tls.insecure=true`
    (**insecure — for testing only**).
- Setting `-Dorg.metricshub.winrm.backend=cxf` now fails with a clear error instead of selecting
  the removed backend: remove the property (or stay on winrm-java 1.x).
- The jar shrinks dramatically: the Apache CXF / JAX-WS / JAXB stack is gone and the only runtime
  dependency left is `smbj` (used for copying files to remote shares).

### Removed

- The Apache CXF-based backend (`WinRMService` and the `service.client` internals), the CXF /
  JAX-WS / JAXB / `jaxws-rt` dependencies, and the WSDL/XSD resources and code generation.
- `KerberosCredentialsException` (was thrown only by CXF internals).

### Added

- Dependency-free WinRM client with no Apache CXF / JAX-WS / JAXB stack, immune by construction to
  JAXP `ServiceLoader` conflicts (it uses the JDK-default XML factories). Supports NTLM over HTTP
  (with message encryption) and HTTPS, and Kerberos (SPNEGO, via the JDK GSS-API) over HTTPS.
- `org.metricshub.winrm.tls.insecure` system property to trust all TLS certificates and skip
  hostname verification (insecure — for testing only).
- In-process protocol tests (`WsmanProtocolTest` + `FakeWsmanServer`) covering the full WSMan
  path — NTLM handshake, message encryption, multipart framing, Enumerate/Pull paging, shell
  lifecycle, and fault mapping — with no Windows host required (they run in `mvn verify`).
- `WinRMLiveTest`: a one-command smoke run against a real host (see README). Before the CXF
  removal, its predecessor (`BackendDifferentialTest`) proved result parity between the two
  backends on live hosts.

### Changed

- HTTPS connections validate certificates and verify hostnames by default (see the breaking
  change above).
- The exception surface matches the pre-2.0.0 CXF backend (feature parity): authentication
  rejections raise the same `Authentication error on <endpoint> with user name "<user>"` message,
  operations on a closed executor raise the same `IllegalStateException` message, the WSMan
  `OperationTimeout` header uses the same `PT#.###S` millisecond-precision format, and the
  `EndOfSequence` / `Items` enumeration markers are recognized in both their WS-Enumeration and
  WSMan namespace variants. WSMan fault exceptions additionally carry the detailed `WSManFault`
  message (including the provider-level detail, e.g. WMI `WBEM_E_*` mnemonics) alongside the SOAP
  reason text.
