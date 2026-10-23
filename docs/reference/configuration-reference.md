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
| `allowedDomains` | String | Comma/space-separated email-domain allowlist for SSO sign-in; empty = allow all. |

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

## Audit log

| Field | Type | Description |
|---|---|---|
| `auditRetentionDays` | int | Days of audit history retained before pruning (default 365); clamps to >= 0; 0 = keep forever. |

## Provisioning portal

Self-service access requests. These gate whether and how users may request an account.

| Field | Type | Description |
|---|---|---|
| `provisioningEnabled` | boolean | Master switch for the self-service provisioning portal (default off). |
| `provisioningAllowEntra` | boolean | Allow Entra/SSO users to request access (default on). |
| `provisioningAllowNative` | boolean | Allow native (username/password) access requests (default off). |
| `provisioningAllowedDomains` | String | Comma/space-separated email-domain allowlist for who may **request** access; empty = any. Distinct from Entra's `allowedDomains`, which gates SSO **sign-in**. |
| `provisioningDefaultDurationDays` | int | Default lifetime of a provisioned account in days (default 0 = no expiry). |
| `provisioningExpiryAction` | String | Action at expiry: `DISABLE` or `DELETE` (default `DISABLE`). |
| `provisioningExpiryReminderDays` | int | Email the account this many days before expiry (default 7; 0 = off). |
| `activationLinkTtlHours` | int | Lifetime of a native approval's one-time activation link, in hours (default 72). |
| `provisioningMaxPendingPerUser` | int | Entra: maximum pending requests per requester (default 1). |
| `provisioningBreakerMax` | int | Native: maximum requests within the window before the breaker trips (default 100). |
| `provisioningBreakerWindowMin` | int | Native: rolling breaker window in minutes (default 10). |
| `provisioningBreakerCooldownMin` | int | Native: pause duration in minutes once the breaker trips (default 30). |

See [Provisioning portal](../administration/provisioning-portal.md).

## Brute-force lockout

| Field | Type | Description |
|---|---|---|
| `bruteForceThreshold` | int | Failed-login count that triggers lockout (default 5). |
| `bruteForceLockoutEnabled` | boolean | Enforce account lockout once the threshold is reached (default on). |
| `bruteForceLockoutMinutes` | int | Lockout duration in minutes (default 15). |

> Lockout is enforced: once a user exceeds `bruteForceThreshold` failed logins within the
> 15-minute window, the authentication layer throws `LockedException` before the password
> check, across all login channels. Protected users are exempt. See
> [Security model](../security/security-model.md).

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
| `slackWebhookUrl` | Secret (encrypted) | Incoming webhook URL. |
| `slackEvents` | List<String> | Event types routed to Slack. |

**Microsoft Teams**

| Field | Type | Description |
|---|---|---|
| `teamsEnabled` | boolean | Enable Teams channel. |
| `teamsWebhookUrl` | Secret (encrypted) | Incoming webhook URL. |
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
