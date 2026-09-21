# Product overview

> What OmniAuth is, who it's for, and the problems it solves.

## What it is

OmniAuth is a Jenkins **security and access-governance platform**, delivered as a single
plugin (`io.jenkins.plugins:omni-auth`). It provides **flexible authentication** — native
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

## Authentication is pluggable

SSO is built on a **provider framework** rather than being hard-wired to one vendor:

- **Native login** always works (bcrypt, API tokens, CLI) — ideal for service accounts and
  break-glass admins.
- **OAuth2 / OIDC single sign-on** is provided through a pluggable provider preset.
- **Microsoft Entra (Azure AD) is the provider integrated today.** Additional OAuth2/OIDC
  providers (e.g. Okta, Auth0, generic OIDC) are planned; the login page and token layer are
  already parameterized by provider to make that straightforward.

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
- **Emergency lockout.** Break Glass provides MFA-protected recovery when SSO or admin access
  is unavailable.
- **Blind spots.** An audit log, active-session view, and multi-channel notifications give
  visibility into access and login events.
- **Vendor lock-in on identity.** A provider framework means SSO isn't tied to a single IdP.

## Capability summary

- **Access management & governance** — matrix authorization with grouped permissions,
  per-user and per-group grants, global or job/folder scope, expiry, JIT access, access
  review/certification, conflict detection, and a full audit log.
- **Security & account safety** — session revocation, fail-closed force-password-change,
  break-glass (TOTP), brute-force tracking, and stale-user cleanup.
- **Authentication** — native + OAuth2/OIDC SSO (PKCE + nonce), pluggable provider framework
  with Microsoft Entra integrated today, identity anchored on a stable directory id, and
  optional group synchronization.
- **Notifications** — email (SMTP), Slack, and Microsoft Teams with per-event routing.
- **Experience** — a configurable, themeable login page.

See the [administration overview](../administration/index.md) for details on each area.

## What it is not

- It is **not** a SAML integration. SSO is wired via OAuth2/OIDC.
- It does **not** replace an identity provider's own MFA — MFA is enforced by the IdP during
  SSO.
- SSO is **not** limited by design to one vendor, but today **only Microsoft Entra is
  implemented**; other providers are planned.
- Brute-force protection currently **tracks and alerts**; it does not hard-lock accounts.

---
_Next: [Requirements](requirements.md) · [Installation](installation.md)_
