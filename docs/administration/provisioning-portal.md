# Provisioning portal (self-service access requests)

> A governed front door for access: people request access, admins approve, and only then is
> an account created — with optional time limits.

The provisioning portal lets prospective users ask for access instead of an administrator
having to pre-create every account by hand. Nothing is created on submission — each request
sits in a queue as **pending** until an admin approves it, at which point OmniAuth creates
the Jenkins account and grants sign-in access. This eliminates ghost accounts and gives a
full request → decision audit trail.

- **Public request page:** `https://<jenkins-host>/<prefix>/request-access/`
- **Admin queue:** **Manage Jenkins → OmniAuth → Access Requests**

## Why it exists

OmniAuth pre-provisions access (an account must exist before someone can sign in). That is
secure but puts the admin in the position of creating every account up front. The portal adds
the missing self-service layer: the user initiates, the admin approves, the account is created
with the right permissions — a governed workflow rather than manual account creation.

## Request flows

The page offers up to two paths, depending on settings:

- **Request access with Microsoft (Entra)** — the user signs in through Microsoft first, so
  their identity (name, email, UPN, object id) is **verified** by the identity provider, then a
  request is created from it. No password, no account — just a pending request.
- **Service-account (native) form** — an anonymous form (username, full name, email, reason,
  duration). Because it is unauthenticated, it is **off by default** and protected by a circuit
  breaker (below). Responses are identical whether or not the account/request already exists, so
  the form can't be used to discover who has an account.

Both paths end at the same place: a **pending `ProvisioningRequest`**.

## Admin queue

At **Manage Jenkins → OmniAuth → Access Requests** you see pending requests with summary tiles
(Pending / Approved / Rejected) and can:

| Action | Result |
|---|---|
| **Approve** | Creates the Jenkins account and grants global Read so the user can sign in. Native accounts get a one-time temporary password (shown once) and must change it on first login; Entra accounts are pre-provisioned. |
| **Reject** | Records the decision with an optional reason. No account is created. |

An existing account is never clobbered — approving a request whose id already exists rejects it
instead.

## Time-limited accounts

If a request carries a non-zero **duration**, the created account is stamped with an expiry. A
background worker (every 6 hours) then enforces it per the **expiry action** setting:

- **Disable** (default) — revokes the account's access (it can no longer do anything); the
  stale-user cleanup removes it later.
- **Delete** — removes the account outright.

[Protected users](user-lifecycle.md#protected-users) are always skipped.

## Native circuit breaker

The anonymous native form is guarded by a global circuit breaker: if more than **N** requests
arrive within **M** minutes, native intake is paused for a **cooldown** and admins are alerted;
it re-opens automatically (or an admin can re-open it from the queue). Approval is the real
backstop, so the breaker only protects the queue from flooding. Entra requests are identity-proven
and never gated by it.

## Settings

Configure the portal from the **Portal settings** panel on the Access Requests page:

| Setting | Default | Purpose |
|---|---|---|
| Enable self-service access requests | off | Master switch |
| Allow Microsoft (Entra) requests | on | Identity-proven SSO requests |
| Allow service-account (native) requests | off | Anonymous form requests |
| Allowed email domains | any | Hard gate on requester email domain |
| Default access duration (days, 0 = permanent) | 0 | Pre-fills the request duration |
| On expiry | Disable | Disable vs delete expired accounts |
| Max pending requests per user (Entra) | 1 | Caps duplicate SSO requests |
| Native breaker — max / window / cooldown | 100 / 10 min / 30 min | Flood protection |

## Security notes

- Approval is **always required** — there is no auto-approve.
- The native form is anonymous; keep it **off** unless you need it, and rely on the breaker plus
  the allowed-domains gate when it is on.
- Every approval/rejection and account creation is recorded in the
  [audit log](../security/audit-logging.md).

---
_Related: [User lifecycle](user-lifecycle.md) · [Access management](access-management.md) · [Authentication](authentication.md)_
