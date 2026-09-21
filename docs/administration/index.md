# Administration overview

> The OmniAuth management console and how the administration topics fit together.

Once the [security realm is enabled](../getting-started/installation.md), all
administration happens in two places:

1. **Manage Jenkins → Security** — the security realm and authorization strategy
   selection, and the Microsoft Entra connection.
2. **Manage Jenkins → OmniAuth** — the management console for everything else.

## The management console

The console (`OmniAuthManagementLink`) is the day-to-day home for administrators. It
groups the governance and operations features into sections:

| Section | Purpose | Docs |
|---|---|---|
| **Overview** | At-a-glance status, attention items, key metrics | this page |
| **Access management** | Grant/edit/revoke roles and permissions per user and group | [Access management](access-management.md) |
| **Access review** | Certify existing access on a schedule | [Access review](access-review.md) |
| **JIT requests** | Approve/deny/revoke Just-in-Time elevation | [JIT access](jit-access.md) |
| **Sessions** | View and revoke active user sessions | [Sessions](sessions.md) |
| **Protected users** | Exempt accounts from automated cleanup | [User lifecycle](user-lifecycle.md) |
| **Stale users** | Review and clean up inactive accounts | [User lifecycle](user-lifecycle.md) |
| **Break Glass** | Emergency access enrollment and activation | [Break Glass](break-glass.md) |
| **Notifications** | Email / Slack / Teams channels and events | [Notifications](notifications.md) |
| **Audit log** | Access and security change history | [Audit logging](../security/audit-logging.md) |
| **Settings** | Login branding and plugin-wide options | [Login branding](login-branding.md) |

> _🖼 screenshot: management console overview_

## How the pieces relate

```
                        ┌─────────────────────────────┐
   Sign in  ──────────► │  OmniAuthSecurityRealm       │  authentication
   (native or Entra)    │  (extends HudsonPrivate…)    │
                        └──────────────┬──────────────┘
                                       │ identity + groups
                                       ▼
                        ┌─────────────────────────────┐
   Every request  ────► │ OmniAuthAuthorizationStrategy│  authorization
                        │ + OmniAuthItemACL            │
                        └──────────────┬──────────────┘
                                       │ reads
                                       ▼
      assignments · roles · JIT grants · expiry · audit  (stored in JENKINS_HOME)
                                       ▲
                                       │ managed via
                        ┌─────────────────────────────┐
                        │ Manage Jenkins → OmniAuth   │  administration
                        └─────────────────────────────┘
```

## Background workers

Several features run on a schedule via Jenkins `AsyncPeriodicWork`:

| Worker | Responsibility |
|---|---|
| `OmniAuthExpiryWorker` | Auto-revoke expired access assignments |
| `OmniAuthJitExpiryWorker` | Expire elapsed JIT grants |
| `AccessReviewWork` | Drive access-review cycles and digests |
| `StaleWarningWork` | Warn owners before stale-account cleanup |
| `StaleUserCleanupWork` | Remove/cleanup inactive accounts |

See the [Configuration reference](../reference/configuration-reference.md) for the settings
that control each worker.

---
_Next: [Authentication](authentication.md)_
