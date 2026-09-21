# Product overview

> What Omni Auth is, who it's for, and the problems it solves.

## What it is

Omni Auth is a Jenkins **security realm** and **authorization strategy** delivered as a
single plugin (`io.jenkins.plugins:omni-auth`). It replaces the default Jenkins login and
permission model with an enterprise-grade identity and access platform while remaining
fully compatible with Jenkins' native accounts.

At its core, `OmniAuthSecurityRealm` extends Jenkins' built-in
`HudsonPrivateSecurityRealm`, so every native capability (local accounts, bcrypt password
storage, API tokens, CLI authentication) continues to work unchanged. On top of that
foundation, Omni Auth adds Microsoft Entra SSO and a governance suite.

## Who it's for

| Role | Value |
|---|---|
| **Platform / Jenkins administrators** | One place to manage who can sign in and what they can do; scoped, time-limited, and reviewable access. |
| **Security & compliance teams** | Access reviews, audit logging, break-glass with MFA, session revocation, and SSO with PKCE/nonce hardening. |
| **Developers / end users** | Familiar "Sign in with Microsoft" experience; self-service Just-in-Time access requests. |

## The problems it solves

- **Two audiences, one login.** Human users authenticate through corporate SSO (Entra),
  while service/admin accounts keep native username/password — side by side on one page.
- **Ghost accounts and permission sprawl.** Pre-provisioning by email, time-limited
  grants with auto-revocation, stale-account cleanup, and periodic access reviews keep the
  permission matrix honest.
- **Standing privilege.** Just-in-Time (JIT) access grants elevated roles only for a
  bounded window, with approval and auto-expiry.
- **Emergency lockout.** Break Glass provides an MFA-protected recovery path when SSO or
  admin access is unavailable.
- **Blind spots.** An audit log, active-session view, and multi-channel notifications
  (email/Slack/Teams) give visibility into access and login events.

## Capability summary

- **Authentication** — native + Microsoft Entra OAuth2/OIDC (MSAL4J), PKCE + nonce,
  OID-anchored identity, MS Graph group sync, provider-preset framework.
- **Authorization** — matrix-based strategy with grouped permissions, per-user and
  per-group grants, global or job/folder-scoped, with expiry.
- **Governance** — access review/certification, JIT access, conflict detection, audit log.
- **Operations & safety** — session revocation, force password change (fail-closed),
  break-glass (TOTP), brute-force tracking, stale-user cleanup.
- **Notifications** — email (SMTP), Slack, and Microsoft Teams with per-event routing.
- **Branding** — configurable, themeable login page.

See the [administration overview](../administration/index.md) for details on each area.

## What it is not

- It is **not** a SAML integration. Entra is wired via OAuth2/OIDC app registration.
- It does **not** replace Microsoft's own SSO MFA — MFA is enforced by Entra during SSO.
- Brute-force protection currently **tracks and alerts**; it does not hard-lock accounts.

---
_Next: [Requirements](requirements.md) · [Installation](installation.md)_
