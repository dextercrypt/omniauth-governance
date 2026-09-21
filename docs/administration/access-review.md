# Access review & certification

> Periodically confirm that existing access is still needed — a core compliance control.

Access review turns your permission matrix into a recurring certification exercise:
administrators are prompted to **keep** or **revoke** each standing grant, with a schedule
and email digest so nothing is forgotten. It is driven by the `AccessReviewWork` background
job and surfaced at **Manage Jenkins → Omni Auth → Access review**.

## Why it matters

Standing access accumulates: people change teams, projects end, contractors leave. Access
review provides the audit-friendly answer to *"who has access to what, and did someone
recently confirm they still need it?"*

## How it works

1. On a schedule, eligible assignments are surfaced for review (assignments already bounded
   by an [expiry date](access-management.md#time-limited-access) can be excluded, since they
   self-clean).
2. Reviewers see each grant with subject, scope, and role, and choose:
   - **Keep** — certify the access; the review timestamp is recorded.
   - **Revoke** — remove the access (audited).
3. Bulk actions (e.g. **Keep all**) speed up large reviews.
4. A summary/digest can be emailed to administrators.

> _🖼 screenshot: access review queue_

## Configuration

Set these under the plugin settings / notifications (see the
[Configuration reference](../reference/configuration-reference.md) for exact keys):

| Setting | Effect |
|---|---|
| **Access review enabled** | Turns the review cycle on/off |
| **Review threshold (days)** | How old a certification must be before a grant is due for review again |
| Digest email recipients | Who receives the review summary (via [Notifications](notifications.md)) |

## Recommended cadence

| Environment | Suggested threshold |
|---|---|
| Highly regulated | 30 days |
| Typical enterprise | 90 days |
| Low-risk internal | 180 days |

## Relationship to other features

- Use [time-limited access](access-management.md#time-limited-access) for grants with a
  known end date — they auto-expire and need no review.
- Use access review for **standing** access that must be periodically justified.
- Every keep/revoke is written to the [audit log](../security/audit-logging.md).

---
_Next: [JIT access](jit-access.md)_
