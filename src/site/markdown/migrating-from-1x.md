keywords: migration, upgrade, 1.x, 2.0, cxf, tls, smb, breaking changes
description: What changed in WinRM Java Client 2.0.0 and how to upgrade from the 1.x releases.

# Migrating from 1.x

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Version 2.0.0 is a major cleanup: the legacy Apache CXF backend and the SMB-based file copy are
gone, leaving a **dependency-free** client. The **public API is unchanged**, so calling code
compiles and runs without modification — but two runtime behaviors changed, and you should read this
page before upgrading.

## TL;DR

* The Apache CXF backend was **removed**; the dependency-free client is the only implementation.
* HTTPS now **validates the certificate and verifies the hostname by default** (1.x trusted every
  certificate). Self-signed hosts that used to work will now fail the TLS handshake until you trust
  the certificate or opt out.
* File copy for `localFileToCopyList` now goes **through the WinRM channel** instead of SMB.
* A few CXF/SMB-only classes were removed.

## TLS is validated by default

The 1.x CXF client silently trusted every TLS certificate and skipped hostname verification. The
2.0.0 client uses the JDK's default validating socket factory instead, so **HTTPS connections to
hosts with self-signed or otherwise untrusted certificates now fail** during the handshake.

To restore connectivity, either:

* install the server certificate (or its issuing CA) into a Java trust store —
  `-Djavax.net.ssl.trustStore=...` (recommended); or
* disable validation with `-Dorg.metricshub.winrm.tls.insecure=true` (**insecure — testing only**).

See [TLS / HTTPS](tls.html) for details.

## The CXF backend was removed

The dependency-free client introduced in the previous release is now the only implementation. There
is no switch to fall back to the Apache CXF backend — if you still need it, stay on winrm-java 1.x.

## File copy no longer uses SMB

Files listed in `localFileToCopyList` for
[`WinRMCommandExecutor.execute(...)`](commands.html) are no longer copied over SMB. They are
transferred **through the WinRM command shell** (chunked base64, decoded on the host with `certutil`
and verified with a digest). The consequences:

* **No SMB requirement** — TCP port 445 no longer needs to be reachable, no administrative or
  temporary share is created on the host, and the copy now works from **any client OS** (1.x wrote
  through a Windows UNC path and only worked from a Windows client).
* The remote copy is **content-addressed**: a fragment of the content digest is inserted before the
  file extension (for example `script.1a2b3c4d.vbs`). Same-named files with different content can no
  longer overwrite each other, and a script that reads its own name (`WScript.ScriptName`) will see
  the digest fragment.
* A file already present on the host with an identical digest is not transferred again.
* The transport is meant for **small script files**, not bulk data.

## Fewer dependencies

Removing CXF and SMB leaves the library with **zero runtime dependencies**: the Apache CXF /
JAX-WS / JAXB stack is gone, and so are `smbj`, BouncyCastle, SLF4J, `mbassador`, and `asn-one`. The
standalone CLI jar shrinks from around 9 MB to a few hundred kB, and the library no longer references
any logging API — problems are reported through [exceptions](timeouts-and-errors.html) only.

## Removed classes

If your code referenced these internal or SMB/CXF-only types, they no longer exist:

* `KerberosCredentialsException` — was thrown only by CXF internals.
* `SmbTempShare` and `WindowsRemoteProcessUtils.copyLocalFilesToShare(...)` — replaced by the
  WinRM-channel file transfer. `WindowsTempShare` is unchanged.
* The Apache CXF-based `WinRMService` and its `service.client` internals, along with the generated
  WSDL/XSD resources.

The documented entry points — `WinRMWqlExecutor`, `WinRMCommandExecutor`, `WinRMEndpoint`,
`WindowsRemoteCommandResult`, the enums, and the exception types — are unchanged.
