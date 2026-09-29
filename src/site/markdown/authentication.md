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
example when the KDC is unreachable or the clock skew is too large.

## User name and domain

The user name may be given as `DOMAIN\user` or as a bare `user`. When a backslash is present, the
part before it is treated as the Windows domain and the part after it as the account name. In Java,
remember to escape the backslash in a string literal:

```java
"DOMAIN\\Administrator"   // domain = DOMAIN, user = Administrator
"Administrator"           // no domain
```

With Kerberos the `DOMAIN\` part is ignored: the principal is the account name in the default
realm of the [Kerberos configuration](#kerberos-configuration). Write `user@REALM` (e.g.
`admin@EXAMPLE.COM`) to name the realm explicitly.

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

Requesting Kerberos on a plain-HTTP client fails at `build()`, even in a fallback list such as
`(KERBEROS, NTLM)`, rather than being silently dropped: there is no Kerberos message encryption
over HTTP.

### Kerberos configuration

By default, Kerberos relies on the **ambient JDK Kerberos configuration** — the platform `krb5.conf`
(or the file named by `-Djava.security.krb5.conf`), or the realm and KDC given directly with
`-Djava.security.krb5.realm` and `-Djava.security.krb5.kdc`:

```bash
java -Djava.security.krb5.realm=EXAMPLE.COM \
     -Djava.security.krb5.kdc=dc01.example.com \
     -cp ... MyApp
```

The optional `ticketCache(Path)` builder option logs in from a Kerberos ticket cache (filled by
`kinit`, for example) instead of with the password. `credentials(...)` is still required: its user
name must be the cached ticket's principal, and the password is not used.

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
cannot request a forwardable ticket, but the JDK still reads `krb5.conf` when they are set, so a
file with only these lines is enough:

```ini
[libdefaults]
  forwardable = true
```

Otherwise the first operation fails with a message saying so, instead of the command failing later
on the second hop. An account that Active Directory never lets be delegated (*Account is sensitive
and cannot be delegated*, or a member of *Protected Users*) fails the same way.

Unlike `winrs`, which delegates only to hosts that Active Directory trusts for delegation, the
client forwards the ticket whether or not the host is trusted. The host then holds a ticket that
lets it act as you on the network until the ticket expires: enable delegation only for hosts you
trust.

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
name**: a domain account, or a local account written `MACHINE\user` or `DOMAIN\user`, gets a `401`
even with the correct password. Use `credentials("user", password)`, not
`credentials("MACHINE\\user", password)`. (The client itself sends a qualified name unchanged, for
the non-Microsoft WSMan services that accept one.)

```java
try (WinRMClient client = WinRMClient.builder("server.example.com")
        .https()
        .credentials("Administrator", password)   // Windows: a bare LOCAL account name
        .authentication(AuthScheme.BASIC)
        .build()) {
    ...
}
```

The WinRM service must have Basic enabled (it is off by default):
`winrm set winrm/config/service/auth @{Basic=true}`; see
[Preparing the Windows Host](preparing-the-host.html). Over plain HTTP it must also set
`AllowUnencrypted=true`, or it refuses the request with a `401` or a WSMan fault, depending on the
Windows version.

A Basic `401` can therefore mean a wrong password, but also a domain or qualified account name,
`Basic` disabled on the service, or plain HTTP with `AllowUnencrypted=false`: check the
configuration before suspecting the credential.

## Authentication failures

A credential the server rejects (HTTP `401`, after every scheme of the fallback list was tried)
surfaces as a
[`WinRMAuthenticationException`](apidocs/org/metricshub/winrm/exceptions/WinRMAuthenticationException.html)
whose message has the stable form `Authentication error on <endpoint> with user name "<user>"`.
When Kerberos is the last scheme tried and fails on the client side (a password the KDC rejects,
an unreachable KDC, an unknown service principal, a ticket that cannot be delegated), the error is
a plain `WinRMClientException` carrying the JDK's Kerberos message.

## Choosing the scheme on the command line

The standalone jar selects the scheme with `--ntlm` (the default), `--kerberos`, or `--basic`:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt \
  --https --kerberos \
  command whoami
```

Its Kerberos options (`--kerberos-kdc`, `--kerberos-realm`, `--allow-delegate`) are described in the
[Command-Line Client](cli.html#kerberos) manual.

## See also

* [Preparing the Windows Host](preparing-the-host.html) — the privileges the account needs, and why
  local administrator accounts are often denied
* [TLS / HTTPS](tls.html) — required for Kerberos, and the only safe transport for Basic
* [Timeouts and Errors](timeouts-and-errors.html) — how authentication failures surface
