keywords: tls, https, certificate, trust store, hostname verification, insecure, self-signed, sslcontext
description: How the client validates TLS certificates over HTTPS, how to trust a certificate, per-client TLS options, and the insecure test-only opt-out.

# TLS / HTTPS

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Use HTTPS with `https()` on the [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html)
builder. HTTPS uses port **5986** by default (HTTP uses **5985**); `port(int)` overrides either.

```java
WinRMClient.builder("server.example.com")
    .https()
    .credentials("DOMAIN\\Administrator", password)
    .build();
```

## Validation is on by default

Since 2.0.00 the client uses the JDK's default, **validating** `SSLSocketFactory`: it checks the
server certificate against the JDK's trust store (its `cacerts`, not the Windows certificate store)
and **verifies** during the handshake that the certificate matches the host name passed to
`builder(...)`.

> [!WARNING]
> This is a change from the 1.x CXF-based client, which silently trusted every certificate and
> skipped hostname verification. Connections over HTTPS to hosts with **self-signed or otherwise
> untrusted certificates now fail** during the TLS handshake unless you trust the certificate or
> explicitly opt out (below). See [Migrating from 1.x](migrating-from-1x.html).

Current JDKs also disable TLS 1.0 and 1.1 (`jdk.tls.disabledAlgorithms`), so the handshake fails
with a host that offers nothing newer (Windows Server 2008 R2 by default), whatever the trust
settings. Enable TLS 1.2 on the host, or, for a test host, re-enable the old protocol in a file
passed with `-Djava.security.properties=<file>`.

## Trusting a certificate

The recommended fix for a self-signed or private-CA host is to add the server certificate (or its
issuing CA) to a Java trust store and point the JVM at it with the standard system properties:

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -cp ... MyApp
```

Because the client uses the JDK default socket factory, any trust store configured this way (or the
JDK's default `cacerts`) applies automatically.

### A dedicated trust store for one client

To use a specific trust store for one client — without touching the JVM-wide configuration — pass
your own `SSLContext` to the builder. Hostname verification stays on:

```java
KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
try (InputStream in = Files.newInputStream(Path.of("winrm-truststore.jks"))) {
    trustStore.load(in, "changeit".toCharArray());
}
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustStore);
SSLContext sslContext = SSLContext.getInstance("TLS");
sslContext.init(null, tmf.getTrustManagers(), null);

WinRMClient client = WinRMClient.builder("server.example.com")
    .https()
    .credentials("DOMAIN\\Administrator", password)
    .sslContext(sslContext)
    .build();
```

## Disabling validation (insecure — testing only)

For a self-signed test host where installing a trust store is not practical,
`trustAllCertificates()` on the builder trusts **all** certificates and skips hostname
verification — for that client only:

```java
WinRMClient.builder("test-host.local")
    .https()
    .credentials("Administrator", password)
    .trustAllCertificates()   // insecure — testing only
    .build();
```

The JVM-wide system property `org.metricshub.winrm.tls.insecure=true` has the same effect for
every client that does not configure TLS explicitly (it is what the legacy API uses):

```bash
java -Dorg.metricshub.winrm.tls.insecure=true -cp ... MyApp
```

> [!WARNING]
> Both opt-outs defeat the protection TLS provides against man-in-the-middle attacks. Use them only
> for testing or for isolated hosts, never in production.

`trustAllCertificates()` and `sslContext(...)` are mutually exclusive, and either one takes
precedence over the system property for that client.

## On the command line

The standalone jar's `--https` and `--https-permissive` options are `https()` and
`trustAllCertificates()`; see the [Command-Line Client](cli.html#options) manual:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt \
  --https --https-permissive \
  wql 'SELECT Name FROM Win32_ComputerSystem'
```

## See also

* [Authentication](authentication.html) — Kerberos requires HTTPS
* [Preparing the Windows Host](preparing-the-host.html) — creating the HTTPS listener on port 5986
* [Timeouts and Errors](timeouts-and-errors.html) — a TLS failure surfaces as a connection error
