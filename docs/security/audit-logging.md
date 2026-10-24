# Audit logging

> A durable record of state-changing and security-relevant actions for compliance and
> investigation.

OmniAuth records security-relevant actions to an audit log, viewable at
**Manage Jenkins → OmniAuth → Audit log**. It answers *who did what, to whom, and when* —
the questions auditors and incident responders ask first. Every state-changing or
security-relevant action is written, not just a curated subset.

## What is recorded

Events are written with a fixed `action` discriminator. The full vocabulary:

**Authentication**
- `login_success`, `login_failure`
- `login_denied_domain` — a Microsoft-verified identity rejected by the SSO domain allowlist
- `password_changed`

**Users & lifecycle**
- `user_created`, `user_deleted`
- `user_marked_deletion`, `user_unmarked_deletion`
- `activation_resent`

**Access & authorization**
- `permission_granted`, `permission_revoked` (user and group, with scope/role detail)
- `role_created`, `role_deleted`
- `expired` — an expiry-driven auto-revocation by a background worker

**Just-in-Time (JIT)**
- `jit_requested`, `jit_approved`, `jit_denied`, `jit_revoked`, `jit_cancelled`
- `jit_timed_out` — a pending request that passed its approval deadline
- `jit_expired` — an active grant whose duration elapsed

**Provisioning portal**
- `provisioning_requested`, `provisioning_rejected`

**Sessions & security response**
- `session_revoked`
- `bruteforce_cleared` — an admin cleared/unlocked a brute-force counter

**Break Glass**
- `break_glass_enrolled`, `break_glass_unenrolled`
- `break_glass_activate`, `break_glass_deactivate`, `break_glass_failed`

**SOS Mode (lockout recovery)**
- `sos_armed` (trigger file opened the recovery window — filesystem-driven, no HTTP identity)
- `sos_recovery_success` (a recovery code matched; records the resolved admin and IP)
- `sos_recovery_failed` (a submitted code matched no admin; records IP only)
- `sos_deactivated` (an admin closed the window from the banner or Settings), `sos_expired`
  (30-minute window elapsed), `sos_locked` (disarmed after 5 failed attempts). Recovery codes
  themselves are never logged.

**Configuration**
- `config_changed` (settings / notifications / protected users / provisioning / branding / …)
- `config_exported`, `config_imported`, `config_reverted`
- `audit_purged`

Each entry captures a timestamp (`ts`, UTC), the `action`, and action-specific fields — the
actor (`by`), the affected subject (`user`), scope, detail, and the source IP where relevant.

## Storage format

- The log is **newline-delimited JSON**, one event per line, in **monthly files**
  `omniauth-audit-YYYY-MM.json` under `JENKINS_HOME/logs`. It survives restarts and plugin
  upgrades.
- Each event is small — on the order of **~110 bytes** — so volume is rarely a concern:
  roughly 1 million events ≈ 110 MB. Retention is a compliance decision, not a storage one.

## Retention and purge

- **Configurable retention.** `auditRetentionDays` (default **365**) is set on
  **OmniAuth → Settings**. A daily background worker (`OmniAuthAuditRetentionWork`) prunes
  events older than the cutoff. Set it to **`0` to keep events forever** (see the
  [Configuration reference](../reference/configuration-reference.md)). JIT request history has
  its own separate retention.
- **Manual purge.** Administrators can **purge** the entire log from the console when required
  by policy. Because SSO/Entra admins have no local password, the purge is confirmed by
  **typing `DELETE`**; native admins **re-authenticate with their password**. The `audit_purged`
  event is written *after* the purge, so the action itself is recorded in the fresh log.

> Purging is irreversible. Export or back up before purging if your retention policy
> requires long-term records — see [Backup & restore](../operations/backup-and-restore.md).

## Viewing and filtering

Browse and filter the audit log from the console (by action, date range, and free-text). Use it to:

- Reconstruct the history of a specific user's access.
- Verify that an offboarding or revocation actually took effect.
- Confirm break-glass usage was expected and was deactivated.
- Spot domain-denied sign-in attempts or repeated login failures.

## Real-time visibility

Pair the audit log (system of record) with [Notifications](../administration/notifications.md)
(real-time alerts) so high-signal events — break-glass activation, JIT approvals, admin
grants — reach the right channel immediately, while the full history remains queryable in
the log.

## Compliance mapping (guidance)

| Control area | Where OmniAuth helps |
|---|---|
| Access certification | [Access review](../administration/access-review.md) + audit entries |
| Least privilege / JIT | [JIT access](../administration/jit-access.md) + expiry |
| Segregation & change tracking | Audit log of all grants/revocations and config changes |
| Emergency access control | [Break Glass](../administration/break-glass.md) + audited activation |
| Sign-in monitoring | login success/failure/domain-denied events + the Security page |

---
_Related: [Security model](security-model.md) · [Notifications](../administration/notifications.md)_
