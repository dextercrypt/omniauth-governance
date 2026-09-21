# Authentication

> How users sign in: native accounts, Microsoft Entra SSO, and group synchronization.

Omni Auth's security realm (`OmniAuthSecurityRealm`) offers two authentication paths on a
single login page. Native login is inherited from Jenkins core; Entra SSO is added on top.

## The login page

The custom login page is served at:

```
https://<jenkins-host>/<prefix>/securityRealm/login
```

It presents a username/password form **and** a **Sign in with Microsoft** button (when
Entra is configured). The button's label and branding are driven by the configured
[SSO provider preset](#sso-provider-presets). See [Login branding](login-branding.md) to
customize the page.

> _🖼 screenshot: dual login page_

## Native login

Native authentication is standard Jenkins local-account login:

- Accounts and **bcrypt** password hashes are managed by Jenkins core (Omni Auth extends
  `HudsonPrivateSecurityRealm`).
- API tokens and CLI authentication work as usual.
- The native form posts to `/<prefix>/omniauth/nativeLogin`.

Native accounts are ideal for **service accounts** and **break-glass admins** that must not
depend on the SSO provider.

Related safety features:
- [Force password change](user-lifecycle.md#force-password-change) — require a user to set
  a new password before any session is issued (fail-closed).
- [Break Glass](break-glass.md) — MFA-protected emergency access.
- Brute-force **tracking** — repeated failures are counted against a configurable threshold
  and can trigger alerts (see [Configuration reference](../reference/configuration-reference.md)).

## Microsoft Entra SSO

SSO uses the OAuth2 **authorization-code flow** via MSAL4J, with OIDC ID tokens.

### Flow

```
Browser                     Jenkins                         Microsoft Entra
  │  GET securityRealm/login   │                                  │
  │◄── page (native + SSO) ────│                                  │
  │  click "Sign in with MS"   │                                  │
  │  GET commenceLogin ───────►│ generate state, nonce, PKCE      │
  │                            │ store in session                 │
  │◄── 302 to Entra ───────────│──── authorize request ─────────►│
  │                                                (user authenticates + MFA)
  │◄── 302 to finishLogin ───────────────────────────────────────│
  │  GET finishLogin?code&state►│ validate state                  │
  │                            │ exchange code (+PKCE verifier)   │
  │                            │ validate ID token + nonce        │
  │                            │ read oid, upn, name, email       │
  │                            │ (optional) fetch Graph groups    │
  │                            │ provision/update Jenkins user    │
  │                            │ establish session                │
  │◄── 302 to dashboard ───────│                                  │
```

Endpoints: `commenceLogin` (start) and `finishLogin` (callback). The callback must match
the redirect URI registered in Entra — see [Entra setup](entra-setup.md).

### Identity model

| Attribute | Role |
|---|---|
| **UPN (email)** | Used as the **Jenkins user id**, so admins can pre-provision access by email before first login. |
| **OID (Object ID)** | Stable identity anchor; a returning user is matched by OID even if their email changes. |
| name, email | Stored on the user for display. |

This is why **pre-provisioning** works: create access assignments for `user@company.com`
and they apply the moment that user first signs in via SSO.

### Security hardening

- **PKCE** — a per-login code verifier/challenge protects the code exchange.
- **State** — CSRF protection across the redirect.
- **Nonce** — bound into the ID token and validated on return to prevent replay.

See [Security model](../security/security-model.md) for the full posture.

### SSO provider presets

The login button is driven by a provider preset (`SsoProvider`). **Microsoft** is the
built-in preset today; the framework is designed so additional OAuth2/OIDC providers can be
added without reworking the login page. Multi-provider selection is a planned enhancement.

## Group synchronization

When **Enable Group Sync** is on, the plugin calls Microsoft Graph (`/me/memberOf`) after a
successful SSO login and maps each Azure AD group to a Spring Security granted authority.
Those authorities can then drive [group-based access](access-management.md#group-grants).

Requirements:
- Graph permission `GroupMember.Read.All` (admin-consented).
- Group sync toggled on in the Entra configuration.

See [Entra setup → API permissions](entra-setup.md#api-permissions) and
[Access management → group grants](access-management.md#group-grants).

---
_Next: [Microsoft Entra setup](entra-setup.md) · [Access management](access-management.md)_
