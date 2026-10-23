# Overview dashboard

> The at-a-glance landing page for OmniAuth — posture, attention items, and sign-in activity.

The **Overview** (the landing page at **Manage Jenkins → OmniAuth**) is a glance-and-triage
surface: in a few seconds it answers *"is anything wrong, and what's my current posture?"*.
It reads live data and refreshes in place; it is not a reporting tool (use the
[audit log](../security/audit-logging.md) for history).

## KPI counters

A row of live metrics, polled periodically from the `overviewMetrics` endpoint and updated in
place (no full reload; polling pauses when the tab is hidden):

| Metric | Meaning |
|---|---|
| **Total users** | All known user accounts. |
| **Active** / **Stale** | Users within / beyond the inactivity threshold. |
| **Protected** | Accounts shielded from automated cleanup and lockout. |
| **SSO** / **Native** | Entra-backed vs. local-password accounts. |
| **Active sessions** | Currently tracked sign-in sessions. |
| **Failed logins (24h)** | Failed sign-ins in the last day (red when non-zero). |
| **Pending JIT** | Just-in-Time requests awaiting approval. |

## Needs Attention

A single widget that surfaces action items so you don't have to go hunting: stale accounts,
accounts pending deletion, admins without Break-Glass TOTP enrolled, grants expiring soon,
orphaned group accounts, failing notification channels, and "no protected users configured".
It reads **All clear** when there is nothing to act on.

## Login Activity chart

A 14-day, dependency-free chart of sign-ins per day — **SSO success**, **native success**, and
**failures** — so a spike or a cliff is visible at a glance. Click a day to open the
[audit log](../security/audit-logging.md) filtered to that date. (For brute-force source IPs,
targeted accounts, and live lockouts, use the **Security** page.)

## Composition donut

A quick active-vs-stale breakdown of the user base.

## System Configuration

A compact status panel showing the effective security posture:

- **Sign-in methods** — *Native + SSO* or *Native* only, depending on whether Entra is configured.
- **Group Sync** — on / off (read from the Entra config), or *N/A* when SSO isn't configured.
- **PKCE + Nonce** — shown as **Always on** (always enforced on the OAuth flow; not a toggle).

---
_Related: [Administration overview](index.md) · [Audit logging](../security/audit-logging.md)_
