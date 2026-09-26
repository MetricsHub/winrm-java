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
    .https()
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
example when the KDC is unreachable or the clock skew is too large. Because the list contains
Kerberos, it requires an HTTPS transport: Kerberos is rejected over plain HTTP rather than being
silently dropped (see [Kerberos](#kerberos-spnego) below).

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

### Credential delegation

A remote command runs under a network logon that cannot use your credentials to reach a *further*
host — a UNC path on a file server, an Active Directory query, a database — so such access fails
with *access denied*: the second hop (see
[Preparing the Windows Host](preparing-the-host.html#the-second-hop)). `allowDelegation()` lifts
that limit, like `winrs -allowdelegate`: Kerberos forwards your ticket-granting ticket (TGT) to the
host, and the commands of the connection authenticate onward as you.

```java
try (WinRMClient client = WinRMClient.builder("server.internal.example.com")
        .https()
        .authentication(AuthScheme.KERBEROS)
        .allowDelegation()                       // Kerberos only
        .credentials("DOMAIN\\user", password)
        .build()) {
    client.command("dir \\\\fileserver\\share").execute();
}
```

The TGT must be **forwardable**, and the JDK asks the KDC for a forwardable one only when told to:
set `forwardable = true` in the `[libdefaults]` section of the JDK's `krb5.conf` (see
[Kerberos configuration](#kerberos-configuration)) — or, with `ticketCache(Path)`, get the cached
ticket with `kinit -f`. The `java.security.krb5.realm` and `java.security.krb5.kdc` properties
cannot say it, but the file is read in addition to them, so next to them a file with only these
lines is enough:

```ini
[libdefaults]
  forwardable = true
```

Otherwise the first operation fails with a message saying so, instead of the command failing later
on the second hop. An account that Active Directory never lets be delegated (*Account is sensitive
and cannot be delegated*, or a member of *Protected Users*) fails the same way.

Unlike `winrs`, which delegates only to hosts that Active Directory trusts for delegation, the
client forwards the ticket to any host it is enabled for (verified with a host that is not trusted
for delegation). The host then holds a ticket that lets it act as you on the network until the
ticket expires: enable delegation only for hosts you trust.

`build()` rejects `allowDelegation()` when Kerberos is not among the schemes: NTLM and Basic
credentials cannot be delegated. In an ordered fallback such as `(KERBEROS, NTLM)`, a connection
that falls back to NTLM is not delegated. CredSSP, the other way `winrs` delegates, is not
supported.

## Basic

HTTP Basic sends the credential in the `Authorization` header of **every** request — there is no
handshake and no message protection, so the payload travels as plaintext SOAP. It works over both
transports, but over plain HTTP the credential and the data are sent **in the clear**: use Basic
over HTTPS only, where TLS protects both.

On Windows, WinRM accepts Basic for **local accounts only**, addressed by their **bare user
name**: a domain account is rejected, and so is a *local* account written with a qualifying
prefix — `MACHINE\user` or `DOMAIN\user` gets a `401` even when the password is correct
(verified against a real host). Use `credentials("user", password)`, not
`credentials("MACHINE\\user", password)`. The client itself does not reject a qualified name —
some non-Microsoft WSMan services accept one — and sends the account with all whitespace
removed: a `DOMAIN\user` value is rebuilt as `DOMAIN` + `\` + the account, and a bare name is
sent as-is.

```java
try (WinRMClient client = WinRMClient.builder("server.example.com")
        .https()
        .credentials("Administrator", password)   // Windows: a bare LOCAL account name
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

Note that most server-side refusals above surface as the same `401`: a Basic authentication
error can mean a wrong password, but also a domain-qualified or domain account, or `Basic`
disabled on the service — check the configuration before suspecting the credential. Unencrypted
HTTP with `AllowUnencrypted=false` is refused too, but not always as a `401`: depending on the
Windows version, the service may authenticate the credential and then reject the unprotected
SOAP with a WSMan fault (a `401` was observed on Server 2008 R2, a fault is reported on later
versions), so that misconfiguration can surface as either an authentication error or a fault.

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

`--allow-delegate` turns on [credential delegation](#credential-delegation) for the invocation. It
requires `--kerberos`, and the ticket must still be forwardable: `--kerberos-kdc` sets the KDC and
the realm, not `forwardable = true`.

## See also

* [Preparing the Windows Host](preparing-the-host.html) — the privileges the account needs, and why
  local administrator accounts are often denied
* [TLS / HTTPS](tls.html) — required for Kerberos and recommended for NTLM
* [Timeouts and Errors](timeouts-and-errors.html) — how authentication failures surface
