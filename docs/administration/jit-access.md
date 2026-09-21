# Just-in-Time (JIT) access

> Grant elevated access only when needed, only for as long as needed — with approval and
> automatic expiry.

JIT access (also known as PIM — Privileged Identity Management) removes standing privilege.
Instead of permanently holding an elevated role, a user **requests** it for a bounded
window; an admin **approves**; the access **auto-expires**; and everything is **audited**.

It is exposed to users via the JIT action and managed by admins at
**Manage Jenkins → OmniAuth → JIT requests**. Requests are stored by
`OmniAuthJitRequestStore`, and elapsed grants are cleaned up by the
`OmniAuthJitExpiryWorker` background job.

## User flow

1. A user opens the Just-in-Time access request page.
2. They select the access they need (role/scope) and a **duration**, and provide a reason.
3. The request enters the **pending** queue and admins are notified.

## Admin flow

At **Manage Jenkins → OmniAuth → JIT requests**:

| Action | Result |
|---|---|
| **Approve** | The elevated access becomes active for the requested window; audited. |
| **Deny** | The request is rejected (with optional reason); the requester is notified. |
| **Revoke** | An active JIT grant is ended early. |

Suggested approvers can be surfaced to streamline routing.

## Expiry and cleanup

- Approved grants carry an expiry; when it passes, `OmniAuthJitExpiryWorker` removes the
  elevated access automatically.
- JIT history is retained for a configurable number of days (see the
  [Configuration reference](../reference/configuration-reference.md)) for later audit.

## Design considerations

- **Combined access.** A user may hold both permanent and JIT-granted access; the effective
  permission at any moment is the union, with the JIT portion removed on expiry.
- **Notifications.** Wire JIT events into [Notifications](notifications.md) so requests and
  approvals reach the right channel (email/Slack/Teams).
- **Least privilege.** Prefer short durations and narrow scopes; combine with
  [access review](access-review.md) for any remaining standing access.

## Prerequisites

JIT relies on the same authorization strategy and on session handling used elsewhere in the
plugin — see [Access management](access-management.md) and [Sessions](sessions.md).

---
_Next: [Sessions](sessions.md)_
