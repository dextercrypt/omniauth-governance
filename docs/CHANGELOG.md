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
- **Native-account management**: reset a native account's password (one-time temporary password,
  forces a change at next login, revokes active sessions, audited) and edit its full name / email
  directly from the user detail view — no need for Jenkins' own user pages. Native accounts only;
  the login id itself is immutable and cannot be renamed.
- **Approver-adjustable JIT duration**: an approver can grant a **shorter** window than requested
  (a "grant for" selector on both the in-product and emailed approval pages); with multiple
  approvers the **minimum** chosen duration wins, and no approver can exceed the requested window.
- **In-app confirmation dialogs**: destructive per-row actions across the management pages (JIT
  revoke, role delete, access-review keep/revoke, group remove, notification-log clear) and the JIT
  banner's End Access / Cancel now use a consistent in-app modal instead of the browser's native
  `confirm()`.
- **SOS Mode**: a host-filesystem-triggered fail-safe for a true admin lockout (all admins SSO-only
  with Entra down, or a misconfigured authorization strategy). Dropping an `omniauth-sos.request`
  file in `JENKINS_HOME` arms a 30-minute recovery window — live-checked, no restart — and opens a
  dark-until-armed endpoint at `/sos/recover`. An admin enters one of their single-use **SOS recovery
  codes** (no username — the matching code identifies them); they are re-granted global Administer
  and signed in. Codes are stored only as salted hashes, so a copy of `JENKINS_HOME` cannot reveal
  them. Enrollment is mandatory and keyed on *usable* codes (a blocking one-time interstitial on
  admin login that re-fires if an admin runs out, plus low/depleted warnings and a dashboard
  attention item). SOS **only arms when OmniAuth is the active Security Realm** — an idle or
  abandoned install (different realm) stays completely dark, so it cannot be a backdoor; genuine
  lockouts (IdP unreachable, authorization strategy misconfigured) still arm because OmniAuth is
  still the configured realm. Admin is restored into any matrix-based authorization strategy
  (OmniAuth's or the stock Matrix / Project-Matrix strategies); for a non-matrix strategy the user
  is signed in and told plainly that admin must be repaired via `config.xml`. A site-wide banner
  announces an active window with one-click Deactivate (also in Settings); the window auto-closes
  after 30 minutes or 5 failed attempts. Audit events: `sos_armed`, `sos_recovery_success`,
  `sos_recovery_failed`, `sos_deactivated`, `sos_expired`, `sos_locked`. Can be disabled via
  `sosEnabled`. Distinct from Break Glass (in-session TOTP step-up, not recovery).
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
- **Baseline raised to Jenkins 2.555.3 / Java 21** (from 2.541.2), preparing for Update Center
  hosting. Jenkins 2.555.x removed Java 17, so **Java 21 is now required**. Parent plugin POM bumped
  to `6.2236.v12dd4c483242`; dependency versions moved onto the `bom-2.555.x` plugin BOM. Artifact
  id renamed `omni-auth` → `omniauth-governance` (Java package, class names and URLs unchanged).
- JIT access banner countdown now shows hours and minutes (e.g. `3h 15m 02s`) instead of raw minutes.
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
