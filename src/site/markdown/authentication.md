keywords: authentication, ntlm, kerberos, spnego, basic, domain, realm, kdc, krb5, ticket cache
description: Authenticate to WinRM with NTLM, Kerberos (SPNEGO), or HTTP Basic, including domain accounts, ordered fallback, and Kerberos configuration.

# Authentication

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

The client authenticates with **NTLM**, **Kerberos (SPNEGO)**, or **HTTP Basic**. The scheme is
chosen with `authentication(...)` on the [`WinRMClient`](apidocs/org/metricshub/winrm/WinRMClient.html)
builder, which takes one or more [`AuthScheme`](apidocs/org/metricshub/winrm/AuthScheme.html)
values:

```java
import org.metricshub.winrm.AuthScheme;

WinRMClient.builder("server.example.com")
    .credentials("DOMAIN\\Administrator", password)
    .authentication(AuthScheme.NTLM)                       // NTLM only (also the default)
    // .authentication(AuthScheme.KERBEROS)                // Kerberos only
    // .authentication(AuthScheme.BASIC)                   // HTTP Basic only
    // .authentication(AuthScheme.KERBEROS, AuthScheme.NTLM) // ordered fallback
    .build();
```

When `authentication(...)` is not called, **NTLM** is used.

## Ordered fallback

Several schemes form an **ordered fallback list**: each is tried in the given order until one
succeeds. `authentication(KERBEROS, NTLM)` attempts Kerberos first and falls back to NTLM — for
example when the KDC is unreachable or the clock skew is too large.

## User name and domain

The user name may be given as `DOMAIN\user` or as a bare `user`. When a backslash is present, the
part before it is treated as the Windows domain and the part after it as the account name. In Java,
remember to escape the backslash in a string literal:

```java
"DOMAIN\\Administrator"   // domain = DOMAIN, user = Administrator
"Administrator"           // no domain
```

The password is a `char[]`, and the builder deliberately does **not** copy it: the client keeps
that same array by reference end-to-end and never converts it to a `String` internally, so after
closing the client you can wipe the single authoritative copy of the secret
(`Arrays.fill(password, '\0')`).

## NTLM

NTLM is the default. It works over both transports:

* **HTTP** — the WinRM payload is protected with **NTLM message encryption**, so credentials and
  data are not sent in the clear even without TLS.
* **HTTPS** — NTLM runs inside the TLS tunnel. See [TLS / HTTPS](tls.html).

NTLM needs no extra configuration beyond the user name and password.

## Kerberos (SPNEGO)

Kerberos authentication uses SPNEGO through the JDK's GSS-API and **requires HTTPS**. Connect by
the **FQDN the KDC knows** (the service principal is `HTTP/<hostname>`), not by IP address:

```java
try (WinRMClient client = WinRMClient.builder("server.internal.example.com")
        .https()
        .credentials("DOMAIN\\Administrator", password)
        .authentication(AuthScheme.KERBEROS)
        // .ticketCache(Path.of("/tmp/krb5cc_1000"))   // optional
        .build()) {
    ...
}
```

Requesting Kerberos on a plain-HTTP client fails at `build()` with a clear message: there is no
Kerberos message encryption over HTTP.

### Kerberos configuration

By default, Kerberos relies on the **ambient JDK Kerberos configuration** — the platform `krb5.conf`
(or the file named by `-Djava.security.krb5.conf`), or the realm and KDC given directly with
`-Djava.security.krb5.realm` and `-Djava.security.krb5.kdc`:

```bash
java -Djava.security.krb5.realm=EXAMPLE.COM \
     -Djava.security.krb5.kdc=dc01.example.com \
     -cp ... MyApp
```

The optional `ticketCache(Path)` builder option points at a Kerberos ticket cache to use for the
connection; without it, Kerberos logs in with the user name and password.

## Basic

HTTP Basic sends the credential in the `Authorization` header of **every** request — there is no
handshake and no message protection, so the payload travels as plaintext SOAP. It works over both
transports, but over plain HTTP the credential and the data are sent **in the clear**: use Basic
over HTTPS only, where TLS protects both.

The credential is the user name exactly as given to `credentials(...)`. A domain-qualified name
(`DOMAIN\user`) keeps its domain prefix on the wire, which is how a domain controller locates the
account; a bare name is used as-is.

```java
try (WinRMClient client = WinRMClient.builder("server.example.com")
        .https()
        .credentials("DOMAIN\\Administrator", password)
        .authentication(AuthScheme.BASIC)
        .build()) {
    ...
}
```

The server must have Basic authentication enabled on the WinRM service — the `Basic` setting under
the service's `auth` section, `False` by default:
`winrm set winrm/config/service/auth @{Basic=true}`; see
[Preparing the Windows Host](preparing-the-host.html). Over HTTPS that is all that is needed, since
TLS provides the confidentiality. Over plain HTTP — which, as noted, should not be used — the
service would additionally have to set `AllowUnencrypted=true` (otherwise it refuses the unprotected
SOAP), which is exactly what the HTTPS recommendation exists to avoid.

## Authentication failures

A rejected credential (after every scheme of the fallback list was tried) surfaces as a
[`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html)
whose message has the stable form `Authentication error on <endpoint> with user name "<user>"`.

## Choosing the scheme on the command line

The standalone jar selects the scheme with `--ntlm` (the default), `--kerberos`, or `--basic`. The
three are mutually exclusive, and `--kerberos` requires `--https`:

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

* [Preparing the Windows Host](preparing-the-host.html) — the privileges the account needs, and why
  local administrator accounts are often denied
* [TLS / HTTPS](tls.html) — required for Kerberos and recommended for NTLM
* [Timeouts and Errors](timeouts-and-errors.html) — how authentication failures surface
