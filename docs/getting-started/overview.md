# Product overview

> What OmniAuth is, who it's for, and the problems it solves.

## What it is

OmniAuth is a Jenkins **security and access-governance platform**, delivered as a single
plugin (`io.jenkins.plugins:omniauth-governance`). It provides **flexible authentication** — native
username/password **and** OAuth2 / OIDC single sign-on — and, more importantly, the layer of
**security and management features** around Jenkins access that most teams end up building by
hand: least-privilege access management, just-in-time elevation, access reviews, session
control, emergency break-glass access, audit logging, and notifications.

It replaces both the Jenkins security realm (`OmniAuthSecurityRealm`, extending the built-in
`HudsonPrivateSecurityRealm`) and the authorization strategy
(`OmniAuthAuthorizationStrategy`, built on Matrix Authorization), while remaining fully
compatible with native Jenkins accounts.

> **It's not just SSO.** Single sign-on is one pillar. The reason to run OmniAuth is
> everything around authentication — how access is granted, scoped, reviewed, revoked, and
> audited, and how easy that is to manage from one console.

## Authentication: native + SSO

Two sign-in paths share one login page:

- **Native login** always works (bcrypt, API tokens, CLI) — ideal for service accounts and
  break-glass admins.
- **OAuth2 / OIDC single sign-on**, today via **Microsoft Entra (Azure AD)**, always hardened
  with PKCE and ID-token nonce.
- The realm's provider selector lists other providers (Okta, Auth0, generic OIDC) as
  *planned* — on the roadmap, not yet available. The sign-in path is Entra-coupled today
  (MSAL4J / Microsoft Graph).

## Who it's for

| Role | Value |
|---|---|
| **Platform / Jenkins administrators** | One console to manage who can sign in and what they can do — scoped, time-limited, reviewable access. |
| **Security & compliance teams** | Access reviews, audit logging, break-glass with MFA, session revocation, and hardened SSO (PKCE/nonce). |
| **Developers / end users** | Familiar SSO sign-in, plus self-service Just-in-Time access requests. |

## The problems it solves

- **Two audiences, one login.** Human users authenticate through corporate SSO, while
  service and admin accounts keep native login — side by side on one page.
- **Permission sprawl and ghost accounts.** Pre-provisioning by email, time-limited grants
  with auto-revocation, stale-account cleanup, and periodic access reviews keep the
  permission model honest.
- **Standing privilege.** Just-in-Time (JIT) access grants elevated roles only for a bounded
  window, with approval and auto-expiry.
- **Unconfirmed high-risk actions.** Break Glass requires an already-authenticated admin to
  pass a TOTP step-up before sensitive operations (it is a confirmation, not a lockout-recovery
  path — keep a native admin for true outages).
- **Blind spots.** An audit log, active-session view, and multi-channel notifications give
  visibility into access and login events.

## Capability summary

- **Access management & governance** — matrix authorization with grouped permissions,
  per-user and per-group grants, global or job/folder scope, expiry, JIT access, access
  review/certification, conflict detection, and a full audit log.
- **Security & account safety** — session revocation, fail-closed force-password-change,
  break-glass (TOTP step-up), enforced brute-force lockout, SSO domain allowlist, and
  stale-user cleanup.
- **Authentication** — native + OAuth2/OIDC SSO (PKCE + nonce), currently Microsoft Entra
  (others planned), identity anchored on a stable directory id, and optional group
  synchronization.
- **Notifications** — email (SMTP), Slack, and Microsoft Teams with per-event routing.
- **Experience** — a configurable, themeable login page.

See the [administration overview](../administration/index.md) for details on each area.

## What it is not

- It is **not** a SAML integration. SSO is wired via OAuth2/OIDC.
- It does **not** replace an identity provider's own MFA — MFA is enforced by the IdP during
  SSO.
- SSO is **Microsoft Entra today** — the provider selector lists others as *planned*, but the
  sign-in path is Entra-coupled (MSAL4J / Microsoft Graph). Don't assume vendor-neutral SSO.
- Brute-force protection **enforces a lockout**: past the failure threshold a native account is
  locked for a cooldown window (protected users exempt).

---
_Next: [Requirements](requirements.md) · [Installation](installation.md)_
