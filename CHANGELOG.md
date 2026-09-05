# Changelog

All notable changes to this project are documented in this file.

## [Unreleased] — 2.0.0

### Added — HTTP Basic authentication

The client now supports **HTTP Basic** as an authentication scheme, in addition to NTLM and
Kerberos:

* `WinRMClient.Builder.authentication(AuthScheme.BASIC)` and the CLI's `--basic` option select it.
* Basic is stateless: the credential rides the `Authorization` header of **every** request, and
  there is no message protection — the payload travels as plaintext SOAP. The scheme is accepted
  over both transports, but it must be used over **HTTPS** in practice, where TLS protects the
  credential and the payload (over plain HTTP both travel in the clear).
* On Windows, WinRM accepts Basic for **local accounts only**, addressed by their **bare user
  name**: a domain account — or even a local account written as `MACHINE\user` — is rejected
  with a 401 (verified against a real host). The server must have the `Basic` setting enabled
  on the WinRM service (`winrm/config/service/auth`).
* `BASIC` joins `AuthenticationEnum` (legacy API) and participates in the ordered-fallback list
  like the other schemes.

### ⚠️ Breaking — SMB file copy replaced by a transfer through the WinRM channel

Files passed to `WinRMCommandExecutor.execute(...)` in `localFileToCopyList` are no longer copied
over SMB: they are transferred **through the WinRM command shell** (chunked base64, decoded with
`certutil -decode`, verified with a `certutil -hashfile` digest against the locally computed one).
Consequences:

- **Zero runtime dependencies**: `smbj` is gone, and with it BouncyCastle (`bcprov-jdk18on`,
  8.2 MB and a recurring source of CVE churn), `slf4j-api`, `mbassador`, and `asn-one`. The
  standalone CLI JAR shrinks from ~9 MB to a few hundred kB, and the library no longer references
  any logging API — problems are reported through exceptions only.
- **No SMB requirement**: TCP port 445 does not need to be reachable, no administrative/temporary
  share is created on the remote host (`net share` is no longer issued), and the copy now works
  from any client OS (the previous implementation wrote through a Windows UNC path, which only
  worked from a Windows client with ambient access to the share).
- A file already present in the remote temporary directory with an identical digest is not
  transferred again, preserving the caching behavior of repeated script executions.
- The remote copy is **content-addressed**: a fragment of the content digest is inserted before
  the file extension (e.g. `script.1a2b3c4d5e6f.vbs`), so same-named files with different content
  from concurrent clients can never overwrite each other. Scripts that inspect their own file
  name (e.g. `WScript.ScriptName`) will see the digest fragment. Overlong names are truncated so
  that both the NTFS path-component limit and the traditional Windows `MAX_PATH` (260) full-path
  limit hold, staging suffixes included (the digest keeps truncated names unique).
- The transfer is decoded into an operation-unique staging file, verified there, and only then
  published as the content-addressed destination. A destination that already carries the
  expected digest is never rewritten (so concurrent transfers of the same content cannot
  invalidate a copy already verified by another operation), while a mismatched pre-existing
  copy (e.g. corrupted in place) is repaired by replacement. In every case, the destination's
  digest is verified last: the operation fails rather than execute unproven bytes.
- The transfer is designed for the small script files this API is meant for; base64 over SOAP is
  not suited to bulk data.
- Transfer steps are batched to minimize WinRM operations (the digest probe rides the same
  command leg as the decode and publish steps), and a command rejected by the server-side
  concurrent-operation quota — very low on old hosts (15 per user on Windows 2008 R2) — is
  retried with a delay when the rejection happened before the command could run.
- `SmbTempShare` (class) and `WindowsRemoteProcessUtils.copyLocalFilesToShare(...)` were removed.
  `WindowsTempShare` is unchanged.

### ⚠️ Breaking — the CXF backend was removed

Version 2.0.0 removes the legacy Apache CXF backend. The dependency-free client introduced in the
previous release is the only implementation; the documented entry points are unchanged, so typical
calling code is unaffected (though a few CXF/SMB-only public types were removed — see Removed).
Consequences:

- **WinRM over HTTPS with self-signed certificates**: unlike the CXF-based client — which silently
  trusted every TLS certificate and skipped hostname verification — this client **validates the
  server certificate and verifies the hostname by default**. Connections to hosts with self-signed
  or otherwise untrusted certificates **fail** during the TLS handshake unless you:
  - install the server certificate (or its issuing CA) into a Java trust store
    (`-Djavax.net.ssl.trustStore=...`); or
  - disable TLS validation with `-Dorg.metricshub.winrm.tls.insecure=true`
    (**insecure — for testing only**).
- The jar shrinks dramatically: the Apache CXF / JAX-WS / JAXB stack is gone, and with the SMB
  file copy replaced by a WinRM-native transfer (see above), the library has **zero runtime
  dependencies**.

### Removed

- The Apache CXF-based backend (`WinRMService` and the `service.client` internals), the CXF /
  JAX-WS / JAXB / `jaxws-rt` dependencies, and the WSDL/XSD resources and code generation.
- `KerberosCredentialsException` (was thrown only by CXF internals).
- The `smbj` dependency (and its transitive BouncyCastle, SLF4J, `mbassador`, and `asn-one`),
  `SmbTempShare`, and `WindowsRemoteProcessUtils.copyLocalFilesToShare(...)` — replaced by the
  file transfer through the WinRM command shell.

### Added

- **Fluent client API** (issue #131): `WinRMClient.builder(host)` creates a reusable,
  `AutoCloseable` client — one authentication, any number of WQL queries and commands over the
  same connection. Per-operation builders (`client.wql(...)`, `client.command(...)`) end in
  `execute()` and return typed results (`WqlResult`/`WqlRow` with case-insensitive property
  lookup, `CommandResult`), with `java.time.Duration` timeouts throughout. Failures are reported
  through a new unchecked exception hierarchy (`WinRMClientException`, with
  `WinRMAuthenticationException`, `WinRMFaultException` — carrying the WSMan fault code, reason,
  and provider detail as fields — `WinRMTimeoutException`, and `WqlSyntaxException`). The legacy
  static helpers and their checked exceptions are unchanged.
- **WQL enumeration tuning** (issue #86): `pageSize(int)` sets the WS-Enumeration `MaxElements`
  batch size (default 32000) and `pullTimeout(Duration)` sets the per-Pull `MaxTime`; both on the
  fluent WQL builder, plumbed down to the WSMan envelopes.
- **Per-client TLS configuration**: `trustAllCertificates()` and `sslContext(SSLContext)` on the
  client builder override the global `org.metricshub.winrm.tls.insecure` system property for that
  client only.
- **First-class file upload**: `client.uploadFile(localPath, remotePath)` copies a file to an
  explicit remote path through the WinRM channel (digest-verified, skip-if-identical, destination
  directory created when needed) — also available to the legacy API as
  `ShellFileCopy.copyLocalFileToRemoteFile(...)`.
- The WSMan `OperationTimeout` header and the socket read timeout now follow each operation's own
  timeout instead of the executor's creation timeout (they were always the same value through the
  legacy API; the fluent API can override the timeout per operation).
- A cached remote command shell reaped by the server between commands (e.g. its `IdleTimeout`
  expired on a long-lived client) is transparently recreated and the rejected command retried
  once — previously every later command on the same executor kept failing with the
  shell-not-found fault.
- The library's internal housekeeping queries (output-encoding detection, Windows-directory
  discovery for file transfers) now explicitly target `ROOT\CIMV2`, so a client configured with a
  custom default WMI namespace can still run commands and transfer files.
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

- **Kerberos over plain HTTP is now rejected for any authentication list that contains it.**
  An ordered fallback list such as `authentication(AuthScheme.KERBEROS, AuthScheme.NTLM)` on the
  default (HTTP) builder previously dropped the Kerberos entry and quietly fell back to the
  remaining schemes (NTLM); it now fails at `build()` with a `WinRMClientException`
  ("Kerberos over WinRM requires HTTPS …"). This makes the rejection fail-closed for every list
  rather than only a Kerberos-only one, matching the builder's documented "Kerberos requested over
  HTTP is rejected" contract and the CLI's `--kerberos` requiring `--https`. Existing code that
  uses such a list over plain HTTP must either call `https()` or remove Kerberos from the list.
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
- Code quality: the PMD report is clean and `pmd:check` now runs in `mvn verify`, so a new violation
  of `pmd.xml` fails the build (issue #122). The cleanup is behavior-preserving — redundant
  parentheses and modifiers removed, empty catch blocks named and commented, and two loops
  restructured. The only signature change is the removal of the unused `target` parameter from the
  `CipherGen` constructor (an internal NTLM helper).
