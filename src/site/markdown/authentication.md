keywords: authentication, ntlm, kerberos, spnego, domain, realm, kdc, krb5, ticket cache
description: Authenticate to WinRM with NTLM or Kerberos (SPNEGO), including domain accounts and Kerberos configuration.

# Authentication

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

The client authenticates with either **NTLM** or **Kerberos (SPNEGO)**. The scheme is chosen by the
`authentications` argument, a
`List<`[`AuthenticationEnum`](apidocs/org/metricshub/winrm/service/client/auth/AuthenticationEnum.html)`>`
that both `executeWql(...)` and `WinRMCommandExecutor.execute(...)` accept.

```java
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.KERBEROS;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.NTLM;

singletonList(NTLM);        // NTLM only (also the default when null or empty)
singletonList(KERBEROS);    // Kerberos only
```

If the list is `null` or empty, **NTLM** is used.

## User name and domain

The user name may be given as `DOMAIN\user` or as a bare `user`. When a backslash is present, the
part before it is treated as the Windows domain and the part after it as the account name. In Java,
remember to escape the backslash in a string literal:

```java
"DOMAIN\\Administrator"   // domain = DOMAIN, user = Administrator
"Administrator"           // no domain
```

## NTLM

NTLM is the default. It works over both transports:

* **HTTP** — the WinRM payload is protected with **NTLM message encryption**, so credentials and
  data are not sent in the clear even without TLS.
* **HTTPS** — NTLM runs inside the TLS tunnel. See [TLS / HTTPS](tls.html).

NTLM needs no extra configuration beyond the user name and password.

## Kerberos (SPNEGO)

Kerberos authentication uses SPNEGO through the JDK's GSS-API and **requires HTTPS**.

```java
import static org.metricshub.winrm.WinRMHttpProtocolEnum.HTTPS;
import static org.metricshub.winrm.service.client.auth.AuthenticationEnum.KERBEROS;

executeWql(
    HTTPS, "server.example.com", null,
    "DOMAIN\\Administrator", password, null,
    "SELECT Name FROM Win32_ComputerSystem",
    30_000L,
    ticketCache,                 // optional java.nio.file.Path to a ticket cache
    singletonList(KERBEROS)
);
```

### Kerberos configuration

By default, Kerberos relies on the **ambient JDK Kerberos configuration** — the platform `krb5.conf`
(or the file named by `-Djava.security.krb5.conf`), or the realm and KDC given directly with
`-Djava.security.krb5.realm` and `-Djava.security.krb5.kdc`:

```bash
java -Djava.security.krb5.realm=EXAMPLE.COM \
     -Djava.security.krb5.kdc=dc01.example.com \
     -cp ... MyApp
```

The optional `ticketCache` parameter points to a Kerberos ticket cache to use for the connection.

## Choosing the scheme on the command line

The standalone jar selects the scheme with `--ntlm` (the default) or `--kerberos`. The two are
mutually exclusive, and `--kerberos` requires `--https`:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt \
  --https --kerberos \
  command whoami
```

Instead of relying on the ambient configuration, the CLI can set the JDK Kerberos configuration for
the current invocation:

| Option | Meaning |
| --- | --- |
| `--kerberos-kdc <host>` | Sets the KDC and, unless `--kerberos-realm` is given, infers the realm from the KDC's DNS suffix. |
| `--kerberos-realm <realm>` | Overrides the inferred realm. Requires `--kerberos-kdc`. |

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.internal.example.com -u 'DOMAIN\user' -pf password.txt \
  --https --kerberos --kerberos-kdc dc01.internal.example.com \
  command whoami
```

Here the realm is inferred as `INTERNAL.EXAMPLE.COM` by dropping the KDC's first DNS label and
upper-casing the rest. This follows a common Active Directory naming convention but is not
guaranteed by Kerberos — pass `--kerberos-realm` when the realm does not match the KDC's DNS suffix,
or when the KDC is not a fully qualified DNS name.

## See also

* [TLS / HTTPS](tls.html) — required for Kerberos and recommended for NTLM
* [Timeouts and Errors](timeouts-and-errors.html) — how authentication failures surface
