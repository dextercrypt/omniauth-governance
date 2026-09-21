# Audit logging

> A durable record of access and security-relevant changes for compliance and investigation.

Omni Auth records security-relevant actions to an audit log, viewable at
**Manage Jenkins → Omni Auth → Audit log**. It answers *who did what, to whom, and when* —
the questions auditors and incident responders ask first.

## What is recorded

Representative events (non-exhaustive):

- **Access changes** — grants, edits, and revocations of assignments (user and group),
  including scope and role.
- **Automated revocations** — expiry-driven removals by the background workers.
- **JIT decisions** — requests, approvals, denials, and revocations.
- **Break Glass** — activation and deactivation.
- **Access review** — keep/revoke certifications.
- **Lifecycle** — stale-account warnings and cleanup outcomes.

Each entry captures the actor, the affected subject/target, the action, and a timestamp.

> _🖼 screenshot: audit log_

## Viewing and filtering

Browse the audit log from the console. Use it to:

- Reconstruct the history of a specific user's access.
- Verify that an offboarding or revocation actually took effect.
- Confirm break-glass usage was expected and was deactivated.

## Retention and purge

- The audit log persists in `JENKINS_HOME` and survives restarts and plugin upgrades.
- Administrators can **purge** the log from the console when required by policy. JIT history
  has its own configurable retention (see the
  [Configuration reference](../reference/configuration-reference.md)).

> Purging is irreversible. Export or back up before purging if your retention policy
> requires long-term records — see [Backup & restore](../operations/backup-and-restore.md).

## Real-time visibility

Pair the audit log (system of record) with [Notifications](../administration/notifications.md)
(real-time alerts) so high-signal events — break-glass activation, JIT approvals, admin
grants — reach the right channel immediately, while the full history remains queryable in
the log.

## Compliance mapping (guidance)

| Control area | Where Omni Auth helps |
|---|---|
| Access certification | [Access review](../administration/access-review.md) + audit entries |
| Least privilege / JIT | [JIT access](../administration/jit-access.md) + expiry |
| Segregation & change tracking | Audit log of all grants/revocations |
| Emergency access control | [Break Glass](../administration/break-glass.md) + audited activation |

---
_Related: [Security model](security-model.md) · [Notifications](../administration/notifications.md)_
