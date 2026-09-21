# Configuration reference

> A consolidated reference of OmniAuth's configurable settings.

Settings live in two places: the **security realm** (authentication) and the **plugin global
config** (`OmniAuthGlobalConfig`, most operational settings). This page lists the settings
grouped by area. Field names shown are the underlying config properties; the UI labels may
differ slightly.

> Some settings' exact semantics (units, defaults) should be confirmed against the running
> instance and the descriptor help text; this table documents intent.

## Authentication — Microsoft Entra (`EntraOAuthConfig`)

| Field | Type | Description |
|---|---|---|
| `tenantId` | String | Entra Directory (tenant) ID. |
| `clientId` | String | Application (client) ID. |
| `clientSecret` | Secret (encrypted) | Client secret value. |
| `enableGroupSync` | boolean | Fetch Azure AD groups via MS Graph after login. |

See [Entra setup](../administration/entra-setup.md).

## Users & cleanup

| Field | Type | Description |
|---|---|---|
| `protectedUsers` | List<String> | Accounts exempt from stale cleanup. |
| `staleThresholdDays` | int | Inactivity (days) after which an account is stale. |
| `activeThresholdDays` | int | Recent-activity window (days) considered active. |
| `cleanupEnabled` | boolean | Master switch for automated stale cleanup. |
| `cleanupDryRun` | boolean | Report candidates without deleting. |
| `cleanupCron` | String | Cron spec for the cleanup worker. |
| `cleanupMaxDeletions` | int | Safety cap on deletions per run. |
| `cleanupNotifyEmail` | String | Recipient for cleanup summaries. |
| `staleWarningEnabled` | boolean | Warn before cleanup. |
| `staleWarningCron` | String | Cron spec for the warning worker. |
| `staleWarningWindowDays` | int | Grace period (days) between warning and cleanup. |

See [User lifecycle](../administration/user-lifecycle.md).

## Access review

| Field | Type | Description |
|---|---|---|
| `accessReviewEnabled` | boolean | Enable the access-review cycle. |
| `accessReviewThresholdDays` | int | Age (days) before a grant is due for re-certification. |

See [Access review](../administration/access-review.md).

## JIT access

| Field | Type | Description |
|---|---|---|
| `jitHistoryRetentionDays` | int | How long JIT request history is retained. |

See [JIT access](../administration/jit-access.md).

## Brute-force tracking

| Field | Type | Description |
|---|---|---|
| `bruteForceThreshold` | int | Failed-login count that triggers tracking/alerting. |

> Tracking/alerting only — no hard account lockout. See [Security model](../security/security-model.md).

## Notifications

**Global**

| Field | Type | Description |
|---|---|---|
| `notificationsEnabled` | boolean | Master notifications switch. |
| `notifyEmails` | String | Default admin recipients. |
| `notificationLogoUrl` | String | Logo used in email templates. |
| `notificationFooterNote` | String | Footer note in email templates. |

**Email (SMTP)**

| Field | Type | Description |
|---|---|---|
| `smtpEnabled` | boolean | Enable email channel. |
| `smtpHost` | String | SMTP server host. |
| `smtpPort` | int | SMTP server port. |
| `smtpTls` | boolean | Use TLS. |
| `smtpUsername` | String | SMTP auth username. |
| `smtpPassword` | Secret (encrypted) | SMTP auth password. |
| `smtpFromAddress` | String | From address. |
| `smtpFromName` | String | From display name. |
| `smtpReplyTo` | String | Reply-to address. |
| `smtpEvents` | List<String> | Event types routed to email. |

**Slack**

| Field | Type | Description |
|---|---|---|
| `slackEnabled` | boolean | Enable Slack channel. |
| `slackWebhookUrl` | String | Incoming webhook URL. |
| `slackEvents` | List<String> | Event types routed to Slack. |

**Microsoft Teams**

| Field | Type | Description |
|---|---|---|
| `teamsEnabled` | boolean | Enable Teams channel. |
| `teamsWebhookUrl` | String | Incoming webhook URL. |
| `teamsEvents` | List<String> | Event types routed to Teams. |

See [Notifications](../administration/notifications.md).

## Login branding

| Field | Type | Description |
|---|---|---|
| `loginHeading` | String | Main heading on the login panel. |
| `loginTabTitle` | String | Browser tab title for the login page. |
| `loginAnnouncementText` | String | Announcement banner text. |
| `loginFooterText` | String | Footer text. |
| `loginBackground` | String | Background style selector. |
| `loginLogoPosition` | String | Logo position. |
| `loginLogoSize` | String | Logo size. |
| `loginButler` | String | Butler style / rotation mode. |

See [Login branding](../administration/login-branding.md).

## Diagnostics

| Field | Type | Description |
|---|---|---|
| `cleanupHistory` | List | Record of past cleanup runs (for review). |

---
_Related: [Configuration as Code](../operations/configuration-as-code.md) · [Endpoints](endpoints.md)_
