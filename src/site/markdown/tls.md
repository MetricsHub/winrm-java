keywords: tls, https, certificate, trust store, hostname verification, insecure, self-signed
description: How the client validates TLS certificates over HTTPS, how to trust a certificate, and the insecure test-only opt-out.

# TLS / HTTPS

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Use HTTPS by passing `HTTPS` as the protocol. HTTPS uses port **5986** by default (HTTP uses
**5985**); pass an explicit `port` to override either.

```java
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;

executeWql(HTTPS, "server.example.com", null, /* ... */);
```

## Validation is on by default

Since 2.0.0 the client uses the JDK's default, **validating** `SSLSocketFactory`: it checks the
server certificate against the platform trust store and **verifies the server hostname** during the
handshake.

> [!WARNING]
> This is a change from the 1.x CXF-based client, which silently trusted every certificate and
> skipped hostname verification. Connections over HTTPS to hosts with **self-signed or otherwise
> untrusted certificates now fail** during the TLS handshake unless you trust the certificate or
> explicitly opt out (below). See [Migrating from 1.x](migrating-from-1x.html).

## Trusting a certificate

The recommended fix for a self-signed or private-CA host is to add the server certificate (or its
issuing CA) to a Java trust store and point the JVM at it with the standard system properties:

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -cp ... MyApp
```

Because the client uses the JDK default socket factory, any trust store configured this way (or the
platform's default trust store) applies automatically.

## Disabling validation (insecure — testing only)

For a self-signed test host where installing a trust store is not practical, set the system property
`org.metricshub.winrm.tls.insecure` to `true`. This trusts **all** certificates and skips hostname
verification:

```bash
java -Dorg.metricshub.winrm.tls.insecure=true -cp ... MyApp
```

> [!WARNING]
> This defeats the protection TLS provides against man-in-the-middle attacks. Use it only for
> testing or for isolated hosts, never in production.

## On the command line

The standalone jar mirrors this behavior:

| Option | Meaning |
| --- | --- |
| `--https` | Use HTTPS (port 5986 by default). |
| `--https-permissive` | Trust any certificate and hostname. Intentionally insecure; testing only. Requires `--https`. |

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt \
  --https --https-permissive \
  wql 'SELECT Name FROM Win32_ComputerSystem'
```

`--https-permissive` sets `org.metricshub.winrm.tls.insecure=true` for that invocation.

## See also

* [Authentication](authentication.html) — Kerberos requires HTTPS
* [Timeouts and Errors](timeouts-and-errors.html) — a TLS failure surfaces as a connection error
