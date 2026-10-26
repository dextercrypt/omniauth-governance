# OmniAuth — Documentation

> A security and access-governance platform for Jenkins, with flexible native + OAuth login.

OmniAuth combines flexible authentication — **native username/password and OAuth2 / OIDC
single sign-on** — with a full suite of security and management features: access management,
just-in-time elevation, access reviews, session control, emergency break-glass access,
audit logging, and multi-channel notifications. The value isn't only how users sign in; it's
everything around it that keeps Jenkins access **secure and easy to manage**.

Single sign-on today uses **Microsoft Entra (Azure AD)**; the realm's provider selector lists
other OAuth2 / OIDC providers as *planned* (roadmap, not yet available).

This documentation set is organized for administrators, security teams, operators, and
developers.

---

## Start here

| I want to… | Go to |
|---|---|
| Understand what the product does | [Product overview](getting-started/overview.md) |
| Check prerequisites | [Requirements](getting-started/requirements.md) |
| Install or upgrade the plugin | [Installation](getting-started/installation.md) |
| Get running in ~15 minutes | [Quick start](getting-started/quick-start.md) |

---

## Documentation map

### Getting started
- [Product overview](getting-started/overview.md)
- [Requirements](getting-started/requirements.md)
- [Installation & upgrade](getting-started/installation.md)
- [Quick start](getting-started/quick-start.md)

### Administration
- [Administration overview](administration/index.md)
- [Overview dashboard](administration/dashboard.md) — live posture, attention items, login activity
- [Authentication](administration/authentication.md) — native + OAuth/OIDC SSO + group sync
- [Microsoft Entra setup](administration/entra-setup.md) — app registration, redirect URIs, permissions
- [Access management](administration/access-management.md) — roles, permissions, scoped & time-limited grants
- [Access review & certification](administration/access-review.md)
- [Just-in-Time (JIT) access](administration/jit-access.md)
- [Sessions & session revocation](administration/sessions.md)
- [Break Glass](administration/break-glass.md) — TOTP step-up for sensitive admin actions
- [Notifications](administration/notifications.md) — email, Slack, Teams
- [User lifecycle](administration/user-lifecycle.md) — provisioning, stale cleanup, force password change
- [Provisioning portal](administration/provisioning-portal.md) — self-service access requests + approval
- [Login page branding](administration/login-branding.md)

### Security
- [Security model](security/security-model.md)
- [Audit logging](security/audit-logging.md)

### Operations
- [Configuration as Code (JCasC)](operations/configuration-as-code.md)
- [Backup & restore](operations/backup-and-restore.md)
- [Troubleshooting](operations/troubleshooting.md)

### Developer
- [Architecture](developer/architecture.md)
- [Extension points](developer/extension-points.md)
- [Building & contributing](developer/contributing.md)
- [Releasing & versioning](developer/releasing.md)
- [Platform support & upgrade policy](developer/platform-support.md) — Jenkins/Java baseline + forward upgrade playbook

### Reference
- [Configuration reference](reference/configuration-reference.md)
- [HTTP endpoints](reference/endpoints.md)
- [Permissions reference](reference/permissions.md)
- [Glossary](glossary.md)
- [Changelog](CHANGELOG.md)

---

## Conventions used in this documentation

- `<prefix>` — the Jenkins context path (e.g. `/jenkins`). Omit if Jenkins is served at root.
- `<jenkins-host>` — the externally reachable host and port of your Jenkins.
- **Manage Jenkins → OmniAuth** — the plugin's management console, referenced throughout.
- Code identifiers (`OmniAuthSecurityRealm`, `omniAuth`, …) refer to classes or JCasC
  symbols; see the [Configuration reference](reference/configuration-reference.md).
