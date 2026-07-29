keywords: prerequisites, enable winrm, quickconfig, enable-psremoting, firewall, 5985, 5986, privileges, permissions, non-admin, local administrator, uac, localaccounttokenfilterpolicy, rootsddl, group policy
description: Prerequisites on the targeted Windows host — when WinRM is already enabled, how to enable it over HTTP or HTTPS, which ports and firewall rules matter, and which privileges the connecting account needs.

# Preparing the Windows Host

<!-- MACRO{toc|fromDepth=2|toDepth=3|id=toc} -->

Two things must be true on the **targeted Windows host** before this client can talk to it:

1. the **WinRM service is running with a listener**, and that listener is reachable through the
   firewall, and
2. the **account you connect with has enough rights** — both to reach WinRM at all, and to perform
   the specific operation (WQL query, remote command, file transfer).

Nothing has to be installed: WinRM ships with every supported version of Windows. This page covers
when it is already on, how to turn it on, and how to get the privileges right.

## What this client needs, and what it does not

| Requirement | Detail |
| --- | --- |
| WinRM service running | Startup type is automatic (or delayed automatic) on Windows Server 2008 and later. |
| A listener | HTTP on port **5985**, or HTTPS on port **5986**. See [TLS / HTTPS](tls.html). |
| Firewall open on that port | Inbound, from the machine running the client. |
| `Negotiate` authentication enabled on the service | **`True` by default.** This is what carries NTLM; `Kerberos` (also `True` by default) carries Kerberos. |
| An account with the right privileges | See [Privileges](#Privileges) below. |

Just as important, a few settings that other WinRM guides tell you to change are **not** needed
here:

* **`AllowUnencrypted` stays `False`.** Over plain HTTP the client protects the payload with
  **NTLM message encryption**, so the service's default refusal of unencrypted traffic is
  satisfied. If a guide tells you to set `AllowUnencrypted=true`, that advice is for clients that
  use Basic authentication — not this one.
* **`Basic` and `CredSSP` stay `False`.** The client authenticates with NTLM or Kerberos only
  ([Authentication](authentication.html)).
* **`TrustedHosts` is irrelevant.** That is a setting on the *Windows* WinRM **client**, consulted
  by the `winrs` command-line tool. A Java client never reads it, so you do not need to add anything
  to it on either machine.
* **No DCOM (port 135) and no SMB (port 445).** WQL rides WinRM rather than DCOM, and file
  transfers ride the WinRM channel itself ([File Transfers](file-transfers.html)). The WinRM port
  is the only one you need to open.

## Is WinRM already enabled?

### Windows Server 2012 and later — usually yes

On Windows Server, **remote management is enabled by default** since Windows Server 2012. A default
installation therefore already has:

* the WinRM service running,
* an HTTP listener on port 5985,
* the firewall open on 5985, and
* `Kerberos` and `Negotiate` authentication enabled.

That is exactly what this client needs, which is why connecting to a freshly installed Windows
Server with an administrator account normally works with no host-side preparation at all.

It can still have been turned off afterwards — by Group Policy, by a hardening baseline, or by an
unattended-install answer file — so verify rather than assume.

### Windows 10 / 11 and other client versions — no

Client versions of Windows do **not** enable WinRM by default. You must enable it explicitly, and
the network profile matters (see [Enabling WinRM](#Enabling_WinRM)).

### Domain membership is not what enables WinRM

> [!NOTE]
> A common belief is that WinRM is enabled because a machine is joined to a domain. It is not:
> what enables it by default is being a **Windows Server** edition (2012 or later), domain-joined
> or not. A domain-joined Windows 11 workstation still has WinRM off; a standalone Windows Server
> 2022 in a workgroup still has it on.

Domain membership does change three things that matter here:

* **Kerberos becomes possible.** Kerberos needs a KDC, so it is only available in a domain;
  workgroup hosts are limited to NTLM. See [Authentication](authentication.html).
* **Group Policy becomes the practical way to enable WinRM** across many hosts at once — which is
  why WinRM *is* in fact enabled on the client machines of many domains. That is the GPO's doing,
  not the domain's.
* **Domain accounts escape UAC remote token filtering**, unlike local accounts. This is the single
  most common cause of "my local admin account gets access denied" — see
  [Local administrators and UAC](#Local_administrators_and_UAC).

### Checking on the host

```powershell
Get-Service WinRM                              # is the service running?
winrm enumerate winrm/config/listener          # is there a listener, on which port and address?
winrm get winrm/config/service                 # Negotiate/Kerberos enabled? AllowUnencrypted? RootSDDL?
Get-NetFirewallRule -Name 'WINRM*' | Select-Object Name, Enabled, Profile
```

`winrm get winrm/config/service` needs an elevated prompt; it is also the quickest way to read the
listener's security descriptor (`RootSDDL`) discussed under [Privileges](#Privileges).

### Checking from the client

The quickest end-to-end check is the standalone jar
([Command-Line Client](cli.html)) — it exercises the same code path as the library:

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt \
  wql 'SELECT Name FROM Win32_ComputerSystem'
```

```bash
java -jar ${project.artifactId}-${project.version}-standalone.jar \
  -h server.example.com -u 'DOMAIN\user' -pf password.txt \
  command whoami
```

Run both: they exercise **different plug-ins** on the host and can fail independently — the first
needs WMI access, the second needs remote-shell access.

## Enabling WinRM

### The quick way (HTTP, port 5985)

This client needs three things on the host: the **service running**, a **listener**, and the
**firewall open**. One command sets up all three, from an **elevated** prompt on the target host:

```console
winrm quickconfig
```

Answer `y` when it asks. Add `-quiet` to skip the prompt. You can also use PowerShell's
`Enable-PSRemoting -Force`, which also enables WinRM.

> [!NOTE]
> `winrm quickconfig` creates the firewall exception **only for the current firewall profile**. If
> the host later moves to another profile (for example from Domain to Private), re-run it, or
> create the rule for all profiles explicitly.

### On client versions of Windows: the network profile

On Windows 10 / 11, `winrm quickconfig` **refuses to create the firewall exception when the active
network profile is Public**, and fails with a message to that effect. The service and the listener
may still be configured, but the port stays closed — so connections time out.

The clean fix is to set the network to **Private** (or join a domain), then re-run
`winrm quickconfig`:

```powershell
Set-NetConnectionProfile -InterfaceAlias 'Ethernet' -NetworkCategory Private
```

Where that is not possible, create the inbound rule explicitly, scoped as tightly as you can:

```powershell
New-NetFirewallRule -DisplayName 'WinRM HTTP' -Direction Inbound -Protocol TCP `
  -LocalPort 5985 -RemoteAddress 192.0.2.10 -Action Allow
```

> [!WARNING]
> Opening 5985 on the Public profile exposes the port on every untrusted network the host attaches
> to. Always restrict `-RemoteAddress` to the addresses of your client machines rather than allowing
> any source.

### HTTPS (port 5986)

An HTTPS listener needs a server certificate whose subject (or SAN) matches the name you connect
by, and whose thumbprint is bound to the listener:

```console
winrm quickconfig -transport:https
```

`quickconfig` only succeeds if a suitable certificate is already in the host's `LocalMachine\My`
store. Otherwise create one and bind it explicitly — for a lab host, a self-signed certificate is
enough. Remember to open port 5986 as well, which `quickconfig -transport:https` does not
necessarily do for you:

```powershell
$cert = New-SelfSignedCertificate -DnsName 'server.example.com' -CertStoreLocation Cert:\LocalMachine\My
New-WSManInstance -ResourceURI winrm/config/Listener `
  -SelectorSet @{ Address = '*'; Transport = 'HTTPS' } `
  -ValueSet @{ Hostname = 'server.example.com'; CertificateThumbprint = $cert.Thumbprint }
New-NetFirewallRule -DisplayName 'WinRM HTTPS' -Direction Inbound -Protocol TCP -LocalPort 5986 -Action Allow
```

On the client side, a self-signed certificate is not trusted by default since 2.0.0: import it into
a Java trust store, or opt out for testing. See [TLS / HTTPS](tls.html).

### At scale: Group Policy

For many hosts, enable the listener through
**Computer Configuration → Administrative Templates → Windows Components → Windows Remote
Management (WinRM) → WinRM Service → "Allow remote server management through WinRM"**, set to
**Enabled** (registry: `HKLM\Software\Policies\Microsoft\Windows\WinRM\Service`,
`AllowAutoConfig` = 1).

> [!WARNING]
> Enabling that policy also requires filling in its **IPv4 filter** and **IPv6 filter** fields —
> `*` to listen on all addresses of that family, or a range to restrict it. Leaving a filter **empty
> disables the listener for that address family**, so a policy that is "Enabled" with blank filters
> can leave the whole fleet without a listener even after the service and firewall are configured.
> They are `IPv4Filter` and `IPv6Filter` under the same registry key.

Two further things the policy does *not* do, and that you must configure alongside it:

* **open the firewall** — add the inbound rule for port 5985 (or 5986) through the Windows Firewall
  policy, and
* **start the service** — set the WinRM service startup type to Automatic through the
  System Services policy.

Setting that same policy to **Disabled** is the supported way to turn remote management off, and is
a frequent reason a Windows Server that "should" work does not.

## Privileges

### The short answer

| Account | Works out of the box? |
| --- | --- |
| **Domain administrator** (or any domain account in the host's local `Administrators`) | **Yes.** Nothing to configure. |
| **Built-in local `Administrator`** | **Yes** (it is exempt from UAC token filtering by default). |
| **Any other local account in `Administrators`** | **No** — access denied until `LocalAccountTokenFilterPolicy` is set. See below. |
| **Non-administrator account** | **No** — needs an explicit grant on the listener, plus WMI grants *if it runs WQL queries*. See [Configuring a non-administrator account](#Configuring_a_non-administrator_account). |

Administrator rights are what make WinRM work with zero host configuration, because the default
security descriptor on the WinRM listener grants full access to `BUILTIN\Administrators` and to
nobody else who connects over the network.

### Local administrators and UAC

This is the trap that catches most people connecting to a workgroup host or with a local account.
Microsoft states it plainly:

> Local administrator accounts other than the built-in Administrator account may not have rights to
> manage a server remotely, even if remote management is enabled.

Under UAC, a local account that is a member of `Administrators` receives a **filtered token** on
network logon, stripped of its administrative privileges — so WinRM denies it. The built-in
`Administrator` account is exempt; **domain** accounts in `Administrators` are not affected at all.

To let other local administrator accounts connect, set on the **target host**:

```powershell
New-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System' `
  -Name LocalAccountTokenFilterPolicy -PropertyType DWord -Value 1 -Force
```

> [!WARNING]
> `LocalAccountTokenFilterPolicy = 1` disables UAC remote restrictions for **all** local
> administrator accounts, which materially weakens the host's resistance to lateral movement with a
> stolen local credential (this is the setting that makes local-account pass-the-hash useful to an
> attacker). Prefer a **domain account** placed in the local `Administrators` group, which needs no
> such change. Where a local account is unavoidable, give it a unique, long password per host.

A useful diagnostic: if the **built-in `Administrator`** authenticates but another member of
`Administrators` does not, with the password known-good in both cases, it is token filtering
essentially every time.

### What each operation actually requires

The privileges are not one single thing — the client uses **two different WinRM plug-ins**, gated
separately:

| Operation | Needs |
| --- | --- |
| **WQL queries** (`client.wql(...)`) | Remote access to the listener, access to the **WMI plug-in**, and rights on the target **WMI namespace** (`ROOT\CIMV2` by default) — plus whatever the queried class itself demands. |
| **Remote commands** (`client.command(...)`) | Remote access to the listener, remote shell access on the host (`AllowRemoteShellAccess`, `True` by default), and whatever rights **the command itself** needs once it runs. |
| **Transfer-and-run** (`upload(...)`) | Both of the above, plus write access to `<windir>\Temp\SEN_ShareFor_<CLIENT>$`, and `certutil` and `forfiles` present on the host. See [File Transfers](file-transfers.html). |
| **`uploadFile(...)`** to an explicit path | Remote shell access, write access to the destination directory, and `certutil` on the host (the same transfer engine, minus the transfer directory and its `forfiles` housekeeping). |

So an account can perfectly well run WQL queries and fail to run commands, or the reverse. When
diagnosing, test the two independently — as in [Checking from the client](#Checking_from_the_client)
above.

### Configuring a non-administrator account

Non-administrative access is possible, and is the right choice for a monitoring account that only
needs to read WMI. Step 1 below is always required. **Step 2 is only needed for WQL queries** (and
therefore for transfer-and-run, which discovers the remote Windows directory with one): an account
that only runs commands or calls `uploadFile(...)` never reaches WMI, so grant it nothing there.

**1. Grant remote access to the WinRM listener.** The default listener security descriptor
(`RootSDDL`) grants full access to `BUILTIN\Administrators` and read access to interactive users
only: `O:NSG:BAD:P(A;;GA;;;BA)(A;;GR;;;IU)S:P(AU;FA;GA;;;WD)(AU;SA;GXGW;;;WD)`. Inspect the value on
your own host first, since a hardening baseline may have changed it:

```powershell
(Get-Item WSMan:\localhost\Service\RootSDDL).Value
```

> [!WARNING]
> Adding the account to the built-in **`Remote Management Users`** group is **not** enough, despite
> being the advice you will find most often. That group is granted access to PowerShell's own
> remoting endpoints, which this client never connects to — it is *not* in the default `RootSDDL`,
> which is what gates the listener. The same is true of `winrs` and of other third-party WinRM
> clients.

So the account needs an entry in the descriptor itself:

```powershell
winrm configSDDL default
```

That opens a permissions dialog: add the account or group and grant **Read** and **Execute**. Prefer
this dialog over setting `RootSDDL` as a string — it **adds** your entry to the existing descriptor,
whereas assigning the value wholesale risks dropping the entries already there.

**2. Grant WMI access — required for WQL queries.** Two steps, both on the target host:

* Add the account to the local **`WinRMRemoteWMIUsers__`** group, which exists to gate the WinRM
  WMI plug-in:

  ```powershell
  net localgroup WinRMRemoteWMIUsers__ /add 'DOMAIN\monitoring'
  ```

  > [!NOTE]
  > `WinRMRemoteWMIUsers__` and `Remote Management Users` carry the *same* description in Windows
  > ("can access WMI resources over management protocols"), which makes them easy to confuse.
  > `WinRMRemoteWMIUsers__` is the one Microsoft's WinRM documentation names for the WMI plug-in on
  > Windows 8 / Server 2012 **and later** — it is current, not a legacy name — and it is created when
  > WinRM is configured, so a host where WinRM was never enabled may not have it yet.
  >
  > Either way, **membership in neither group is sufficient on its own**: the namespace rights below
  > and the `RootSDDL` entry from step 1 are what actually grant access.

* Then grant the account rights on the WMI namespace itself, since group membership alone does not
  confer them. Run `wmimgmt.msc` → **WMI Control** → *Properties* → *Security*, select the
  namespace (`Root\CIMV2` for almost everything), *Security*, add the account, and grant
  **Enable Account** and **Remote Enable**, with *Applies to* set to
  **This namespace and subnamespaces**.

  Those two are all a `SELECT` query needs. In particular **`Execute Methods` is not required** —
  it authorizes invoking WMI class methods, which this client never does (it only runs `SELECT`
  queries; anything else is rejected locally as a `WqlSyntaxException`). Many monitoring guides
  grant it anyway; leave it off unless something else on the account's behalf needs it.

  Granting these on `Root` with *This namespace and subnamespaces* covers every namespace at once;
  granting them on `Root\CIMV2` alone is tighter and usually sufficient.

**3. Remote commands need more than this.** Listener access lets a non-administrator open a remote
shell, but the commands you then run are ordinary Windows processes subject to ordinary Windows
security — reading a service's state, a registry key or a protected directory still requires the
corresponding rights, and much of what people run over WinRM needs administrative ones. The
practical guidance: use a non-administrator account for **WQL-only** workloads, and expect to need
an administrative account when you run commands. Whichever you choose, verify it with
`command whoami` as shown above rather than assuming.

### The second hop

A remote command authenticates with a **network logon** whose credentials **cannot be delegated
onward**. So a command that reaches a *second* remote resource — a UNC path, another server, a
mapped drive — fails with access denied, even though the same command works when run locally on
the host.

Windows solves this with CredSSP or Kerberos constrained delegation. This client **does not support
CredSSP** ([Authentication](authentication.html)), so the workaround is to avoid the second hop:
copy what you need onto the host first ([File Transfers](file-transfers.html)), or have the command
use credentials it supplies itself.

## Host quotas worth knowing about

The WinRM service enforces per-user quotas that surface as faults rather than as anything
resembling a privilege problem. Defaults differ markedly between Windows versions, and the older
the host, the tighter they are:

| Setting | What it limits | Note |
| --- | --- | --- |
| `MaxMemoryPerShellMB` | Memory per shell, including child processes | Historically **150 MB**; 1024 MB on modern hosts. A command whose output is large can hit it. |
| `MaxShellsPerUser` | Concurrent shells per user | 5 on older hosts, 30 on modern ones. Close clients you no longer need. |
| `MaxConcurrentOperationsPerUser` | Concurrent operations per user | 15 on Windows Server 2008 R2, 1500 later. File transfers are batched specifically to stay under low limits. |
| `MaxEnvelopeSizekb` | SOAP envelope size | 150 KB on older hosts, **500 KB** on modern ones; caps how much a single response can carry. |
| `IdleTimeout` | How long an idle shell survives | 180000 ms (3 min) on older hosts, **7200000 ms** (2 h) on modern ones; 60000 ms minimum. |

Read them with `winrm get winrm/config`. Raising a quota is a considered decision, not a reflex —
prefer narrowing the query or splitting the command.

## Troubleshooting: symptom to cause

| What you see | Likely cause on the host |
| --- | --- |
| Connection refused / connection timed out on 5985 or 5986 | Service not running, no listener, or the firewall is closed. Check all three, in that order. |
| Connection succeeds but every request is refused, with correct credentials | The account is denied by `RootSDDL`, or it is a local administrator hitting UAC token filtering. |
| `WinRMAuthenticationException` for one local admin but not for the built-in `Administrator` | UAC token filtering — see [Local administrators and UAC](#Local_administrators_and_UAC). |
| `WinRMAuthenticationException` with a Kerberos scheme, NTLM working | Connect by the FQDN the KDC knows, check clock skew, or fall back to NTLM. See [Authentication](authentication.html). |
| `WinRMFaultException` whose detail is `WBEM_E_ACCESS_DENIED` | The account reached WMI but lacks namespace rights — step 2 above. |
| `WinRMFaultException` whose detail is `WBEM_E_INVALID_CLASS` or `WBEM_E_INVALID_NAMESPACE` | The query is wrong, not the permissions. See [WQL Queries](wql.html). |
| WQL works, commands do not | Remote shell access, `AllowRemoteShellAccess`, or a per-user shell quota. |
| Commands work, WQL does not | WMI plug-in or namespace rights — step 2 above. |
| A TLS handshake failure over HTTPS | The certificate is not trusted by the JVM, or its name does not match. See [TLS / HTTPS](tls.html). |

The full exception surface, including how to read a WSMan fault code, is described in
[Timeouts and Errors](timeouts-and-errors.html).

## See also

* [Authentication](authentication.html) — NTLM and Kerberos, and what each needs from the host
* [TLS / HTTPS](tls.html) — trusting the listener's certificate
* [File Transfers](file-transfers.html) — what transfers need on the host
* [Timeouts and Errors](timeouts-and-errors.html) — the exception surface and WSMan fault detail
* [Command-Line Client](cli.html) — the quickest way to test a host's configuration
