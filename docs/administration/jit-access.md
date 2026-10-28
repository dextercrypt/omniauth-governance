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

JIT access is requested **per job/folder**, not from a central role picker. A user asks for
elevation against a **specific job or folder** that is covered by a scoped JIT assignment:

1. On a job/folder that has a covering scoped JIT assignment
   (`findCoveringJitAssignment` walks up the item hierarchy), the user requests elevation and
   provides a **reason** and a **duration**.
2. The requested duration is **clamped to the assignment's `maxDurationHours`** cap.
3. The request enters the **pending** queue and the assignment's approvers are notified.

> **JIT is scoped-only.** It applies to a specific job/folder scope, never to global access.
> There is no central "request page" where users pick an arbitrary role or scope — the eligible
> scope is whatever a scoped JIT assignment already covers.

## Admin flow

A JIT assignment requires **at least two approvers**, and approval is **AND-logic**: the grant
activates only when **every** approver has approved (`isFullyApproved` uses `allMatch`). A single
approval does not activate access.

Approvers can act two ways:

- **Tokenized email links** — each approver receives a unique per-entry link
  `…/omniauth-jit/action?token=<token>` (served by a `RootAction`, so it bypasses the
  `ADMINISTER` management gate — no Jenkins login required to decide).
- **In-product** — at **Manage Jenkins → OmniAuth → JIT requests**, admins approve or deny via
  `doApproveJit` / `doDenyJit`.

| Action | Result |
|---|---|
| **Approve** | Records this approver's decision — optionally for a **shorter window** than requested (see below); once **all** approvers have approved, the elevated access becomes active for the granted window; audited. |
| **Deny** | The request is rejected (with optional reason); the requester is notified. |
| **Revoke** | An active JIT grant is ended early. |

### Approval duration

When approving, each approver can **grant a shorter window than the user requested**. Both the
in-product Approve form and the tokenized email page show a **"grant for"** selector offering
`1 … requestedHours`, defaulting to the full request.

With multiple approvers, the activated grant uses the **minimum** of their chosen durations — the
shortest cap wins, and no approver can grant more than was requested
(`OmniAuthJitRequest.effectiveGrantedDurationHours()`). An approver who leaves the default grants
the full requested window, so a plain Approve behaves exactly as before. The management queue's
Recent Activity shows the granted window, noting **"(of N h)"** when it was shortened below the
request, and the audit log records the granted duration (not the requested one).

## Expiry and cleanup

`OmniAuthJitExpiryWorker` drives two separate timeouts:

- **Approval timeout.** A pending request not fully approved before its `approvalDeadline`
  transitions to **`TIMED_OUT`** (audit `logJitTimedOut`) — it never activates.
- **Grant expiry.** An active grant past its duration transitions to **`EXPIRED`** (audit
  `logJitExpired`) and the elevated access is removed automatically.

JIT history is retained for a configurable number of days (see the
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
