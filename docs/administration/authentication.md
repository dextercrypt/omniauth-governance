# Authentication

> How users sign in: native accounts and OAuth2 / OIDC single sign-on, with a pluggable
> provider framework and group synchronization.

OmniAuth's security realm (`OmniAuthSecurityRealm`) offers two authentication paths on a
single login page. Native login is inherited from Jenkins core; OAuth2/OIDC single sign-on is
added on top through a **provider framework**. **Microsoft Entra (Azure AD) is the provider
integrated today; additional OAuth2/OIDC providers are planned.**

## The login page

The custom login page is served at:

```
https://<jenkins-host>/<prefix>/securityRealm/login
```

It presents a username/password form **and** an SSO button (when a provider is configured).
The button's label and branding follow the configured
[provider preset](#provider-framework). See [Login branding](login-branding.md) to customize
the page.

## Native login

Native authentication is standard Jenkins local-account login:

- Accounts and **bcrypt** password hashes are managed by Jenkins core (OmniAuth extends
  `HudsonPrivateSecurityRealm`).
- API tokens and CLI authentication work as usual.
- The native form posts to `/<prefix>/omniauth/nativeLogin`.

Native accounts are ideal for **service accounts** and **break-glass admins** that must not
depend on an external identity provider.

Related safety features:
- [Force password change](user-lifecycle.md#force-password-change) — require a user to set a
  new password before any session is issued (fail-closed).
- [Break Glass](break-glass.md) — MFA-protected emergency access.
- Brute-force **tracking** — repeated failures are counted against a configurable threshold
  and can trigger alerts (see [Configuration reference](../reference/configuration-reference.md)).

## OAuth2 / OIDC single sign-on

SSO uses the OAuth2 **authorization-code flow** with OIDC ID tokens, acquired through the
configured provider. The current implementation uses **MSAL4J** for the Microsoft Entra
provider.

### Flow

```
Browser                     Jenkins                         Identity provider (Entra today)
  │  GET securityRealm/login   │                                  │
  │◄── page (native + SSO) ────│                                  │
  │  click the SSO button      │                                  │
  │  GET commenceLogin ───────►│ generate state, nonce, PKCE      │
  │                            │ store in session                 │
  │◄── 302 to provider ────────│──── authorize request ─────────►│
  │                                                (user authenticates + MFA)
  │◄── 302 to finishLogin ───────────────────────────────────────│
  │  GET finishLogin?code&state►│ validate state                  │
  │                            │ exchange code (+PKCE verifier)   │
  │                            │ validate ID token + nonce        │
  │                            │ read subject id, email, name     │
  │                            │ (optional) fetch groups          │
  │                            │ provision/update Jenkins user    │
  │                            │ establish session                │
  │◄── 302 to dashboard ───────│                                  │
```

Endpoints: `/<prefix>/omniauth/commenceLogin` (start) and `/<prefix>/omniauth/finishLogin`
(callback), both served by `OmniAuthRootAction` (they are **not** under `securityRealm/`). The
callback must match the redirect URI registered with the provider — for Entra, see
[Entra setup](entra-setup.md).

### Identity model

| Attribute | Role |
|---|---|
| **Email / UPN** | Used as the **Jenkins user id**, so admins can pre-provision access by email before first login. |
| **Directory object id** | Stable identity anchor; a returning user is matched by this id even if their email changes. (For Entra this is the Azure **OID**.) |
| name, email | Stored on the user for display. |

This is why **pre-provisioning** works: create access assignments for `user@company.com` and
they apply the moment that user first signs in via SSO.

### Security hardening

- **PKCE** — a per-login code verifier/challenge protects the code exchange.
- **State** — CSRF protection across the redirect.
- **Nonce** — bound into the ID token and validated on return to prevent replay.
- **Allowed email domains** — an optional Entra sign-in gate: a Microsoft-verified identity
  whose email domain is outside the configured allowlist is rejected after the pre-provisioning
  check (see [Entra setup → Allowed email domains](entra-setup.md)).

See [Security model](../security/security-model.md) for the full posture.

### Provider framework {#provider-framework}

The SSO button and labels are driven by a provider preset (`SsoProvider`):

- **Microsoft Entra** is the only preset **implemented and supported today**. The sign-in path
  itself is Entra-coupled (MSAL4J / Microsoft Graph) — there is a provider *selector* in the
  realm UI, not yet a provider-agnostic abstraction.
- Other providers (Okta, Auth0, generic OIDC) appear in the selector as *planned* — on the
  roadmap, not yet available. Don't assume vendor-neutral SSO today.

For developers: see [Extension points → add an SSO provider](../developer/extension-points.md#where-to-extend).

## Group synchronization

When group sync is enabled, the plugin fetches the user's groups from the identity provider
after a successful SSO login and maps each group to a Spring Security granted authority. Those
authorities can then drive [group-based access](access-management.md#group-grants).

For Microsoft Entra this uses MS Graph (`/me/memberOf`) and requires the
`GroupMember.Read.All` permission — see [Entra setup → API permissions](entra-setup.md#api-permissions).

---
_Next: [Microsoft Entra setup](entra-setup.md) · [Access management](access-management.md)_
