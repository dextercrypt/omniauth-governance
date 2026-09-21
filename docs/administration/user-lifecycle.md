# User lifecycle

> Provisioning, protecting, force-password-change, and cleaning up accounts over time.

This page covers how accounts come into being, how they're kept safe, and how inactive ones
are retired.

## Provisioning

| Path | How an account is created |
|---|---|
| **Native** | Created by an administrator (Jenkins local account). |
| **Entra SSO** | Provisioned automatically on first successful sign-in, keyed by UPN (email) and anchored to the Azure OID. |

**Pre-provisioning:** because the Jenkins user id is the user's email, you can create
[access assignments](access-management.md) for `user@company.com` *before* their first
login — the permissions apply the moment they sign in. This avoids "ghost" placeholder
accounts and the red-strikethrough state in the permission matrix.

## Force password change {#force-password-change}

For native accounts, you can require a user to set a new password before they are allowed a
session. The implementation is **fail-closed**: until the new password is set, **no session
is issued** on any path — login form, HTTP BASIC, API token, or CLI. The user is presented
with a branded set-password page and cannot proceed until they comply.

Use this for:
- Newly created accounts with a temporary password.
- Forced rotation after a suspected compromise.

> _🖼 screenshot: set-password page_

## Protected users

Some accounts must never be touched by automated cleanup — for example, break-glass admins
or service accounts. Add them to the **Protected users** list
(**Manage Jenkins → OmniAuth → Protected users**). Protected users are exempt from stale
warnings and cleanup.

> _🖼 screenshot: protected users_

## Stale-account warning and cleanup

Two background workers keep the account list healthy:

| Worker | Role |
|---|---|
| `StaleWarningWork` | Warns before an inactive account is cleaned up (grace window). |
| `StaleUserCleanupWork` | Removes/cleans up accounts inactive beyond the threshold. |

Key settings (see the [Configuration reference](../reference/configuration-reference.md)):

| Setting | Effect |
|---|---|
| **Stale threshold (days)** | Inactivity after which an account is stale |
| **Active threshold (days)** | Recent-activity window considered "active" |
| **Cleanup enabled** | Master switch for automated cleanup |
| **Cleanup dry run** | Report what *would* be removed without removing it |
| **Cleanup schedule (cron)** | When the cleanup worker runs |
| **Max deletions per run** | Safety cap on how many accounts a single run may remove |
| **Cleanup notify email** | Where cleanup summaries are sent |
| **Stale warning window (days)** | Grace period before cleanup after a warning |

> **Start in dry-run.** Enable cleanup in dry-run mode first, review the reported
> candidates, add any [protected users](#protected-users), then switch off dry-run.

Last-login data used to determine staleness is tracked per user (`LastLoginProperty` /
login history), updated by a login listener on each sign-in.

## Offboarding checklist

1. Revoke [access assignments](access-management.md) (or let expiry handle it).
2. Revoke active [sessions](sessions.md).
3. Let stale cleanup remove the account, or remove it manually.
4. Confirm the [audit log](../security/audit-logging.md) reflects the changes.

---
_Next: [Login branding](login-branding.md)_
