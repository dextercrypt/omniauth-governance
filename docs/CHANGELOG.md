# Changelog

All notable changes to Omni Auth are documented here. This project aims to follow
[Keep a Changelog](https://keepachangelog.com/) conventions and
[Semantic Versioning](https://semver.org/).

> This file is a scaffold. Fill each release section from the git history and issue
> tracker as versions are cut.

## [Unreleased]

### Added
- _TODO: list unreleased additions_

### Changed
- _TODO_

### Fixed
- _TODO_

## [1.0] — TBD

First feature-complete release. Highlights (see the documentation for details):

- **Authentication:** dual native + Microsoft Entra SSO on one login page; OAuth2
  authorization-code flow via MSAL4J with PKCE and ID-token nonce validation;
  OID-anchored identity; optional MS Graph group sync; provider-preset framework.
- **Authorization:** `OmniAuthAuthorizationStrategy` (matrix-based) with grouped
  permissions, per-user and per-group grants, global or job/folder scope, and expiry.
- **Governance:** access review/certification with weekly digest; Just-in-Time (JIT)
  access with approval and auto-expiry; conflict detection; full audit log.
- **Safety & operations:** session revocation and active-session view; fail-closed force
  password change; Break Glass emergency access (TOTP); brute-force tracking; stale-user
  warning and cleanup workers.
- **Notifications:** email (SMTP), Slack, and Microsoft Teams with per-event routing,
  retry-on-failure, test actions, and email preview.
- **Branding:** configurable, themeable login page (heading, tab title, announcement,
  footer, logo, animated background, butler styles with rotation).
- **Configuration as Code:** JCasC symbols for the realm, Entra config, authorization
  strategy, assignments, roles, and JIT requests.

---

_For contributor-facing change history, see the project git log._
