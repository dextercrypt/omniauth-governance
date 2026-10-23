# Changelog

All notable changes to OmniAuth are documented here. This project aims to follow
[Keep a Changelog](https://keepachangelog.com/) conventions and
[Semantic Versioning](https://semver.org/).

> The first public release will be tagged **`v1.0.0-beta.1`**. Until then, everything below is
> unreleased and heading toward that tag. The plugin version is derived from the build
> (`1.0.0-SNAPSHOT` in development), never hardcoded.

## [Unreleased] — toward `1.0.0-beta.1`

Most recent notable changes (newest first):

### Added
- **Entra SSO domain allowlist** (`allowedDomains`): restrict sign-in to listed email domains;
  a Microsoft-verified identity outside the list is rejected even if pre-provisioned or in an
  allowed group (audit event `login_denied_domain`).
- **Test Connection** button on the Entra config: validates tenant / client ID / client secret
  against Microsoft via a client-credentials token grant before saving.
- **Audit log retention** (`auditRetentionDays`, default 365, `0` = keep forever) with a daily
  pruning worker, plus a live storage readout.
- **Full audit coverage**: the audit log now records every state-changing and security action
  (permission grant/revoke, session revoked, config changed/exported/imported/reverted,
  user marked/unmarked for deletion, password changed, provisioning requested/rejected,
  activation resent, JIT timed-out/expired, domain-denied sign-in, …).
- **Overview dashboard**: live-polling KPI metrics, a 14-day login-activity chart (SSO / native
  / failures), and a composition donut — all dependency-free.
- **Backup & Restore UI (v2)**: export / import / revert of roles, scoped assignments, groups
  and settings as JCasC-shaped YAML, with restore-point history and a colour-coded,
  Terraform-style plan preview before import and rollback (no realm or secrets).
- **First-class JCasC** support: realm, Entra config, authorization strategy, assignments,
  roles, JIT requests and provisioning requests round-trip; global settings (brute-force
  lockout, notifications, branding, retention) round-trip under the global-config descriptor.
- **About / Info page** with a live version row and dynamic sign-in-methods summary.

### Changed
- Reframed the plugin as an **authentication and access-governance** platform (not just SSO);
  dropped all legacy "Dual Auth" naming.
- Security realm presents a **provider selector** (Microsoft Entra available; Okta / Google /
  OIDC / SAML marked *planned*). SSO today is Entra-coupled (MSAL4J / Microsoft Graph).
- **Self-signup removed** — replaced by the provisioning portal; `/signup` redirects to login.
- Redirect URI is shown as a full URL derived from the configured Jenkins URL.

### Fixed
- Audit log read ordering (newest-first), date-range filtering (UTC), and SSO-admin purge
  confirmation (typed phrase for Entra admins who have no local password).
- System Config dashboard widget (Group Sync source; PKCE+nonce shown as always-on).

## First-release highlights

The feature-complete baseline the first release builds on:

- **Authentication:** native (bcrypt, API tokens, CLI) + OAuth2/OIDC SSO on one login page —
  **currently Microsoft Entra**, others *planned*. Authorization-code flow with PKCE and
  ID-token nonce always enforced; directory-id-anchored identity; optional group sync.
- **Authorization:** `OmniAuthAuthorizationStrategy` (matrix-based) with grouped permissions,
  per-user and per-group grants, global or job/folder scope, and expiry-based auto-revoke.
- **Governance:** access review / certification with a weekly digest; Just-in-Time (JIT) access
  with multi-approver AND-logic, approval timeout and auto-expiry; conflict detection; a full
  audit log.
- **Safety & operations:** session revocation and active-session view; fail-closed force
  password change; **Break Glass** TOTP step-up for an already-authenticated admin (multi-device,
  not a lockout-recovery path); **enforced brute-force lockout** (threshold + cooldown,
  protected users exempt); stale-user warning and cleanup workers.
- **Provisioning portal:** anonymous access requests (native + Entra), an admin approval queue,
  approve-with-role, time-limited accounts with expiry, and a request circuit breaker.
- **Notifications:** email (SMTP), Slack (Block Kit), and Microsoft Teams (Adaptive Cards) with
  per-event routing, retry-on-failure, test actions, and email preview.
- **Branding:** configurable, themeable login page (heading, tab title, announcement, footer,
  logo, animated background, butler styles with rotation).

---

_For contributor-facing change history, see the project git log._
