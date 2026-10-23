# Security model

> How OmniAuth authenticates, authorizes, and protects sessions — and the guarantees and
> limits you should design around.

## Authentication assurances

| Mechanism | Protection |
|---|---|
| **Native login** | Jenkins-managed bcrypt password hashing (inherited from core). |
| **OAuth2/OIDC code flow** | No credentials touch Jenkins for SSO users; authentication happens at the identity provider (Microsoft Entra today). |
| **PKCE** | Binds the authorization code to the initiating client, defeating code interception. |
| **State parameter** | CSRF protection across the OAuth redirect. |
| **ID token nonce** | Single-use value validated on return; prevents token replay. |
| **Encrypted secrets** | The Entra client secret and SMTP password are stored as Jenkins `Secret` (encrypted at rest). |

MFA for SSO users is enforced by **Microsoft Entra** (Conditional Access), not by Jenkins.

## Session integrity

- Sessions rely on Jenkins' session-seed mechanism; revoking a session invalidates the
  seed so the next request re-authenticates.
- Session revocation is available to administrators for incident response — see
  [Sessions](../administration/sessions.md).
- Logout ends the Jenkins session but not the Microsoft session (by design; see
  [Sessions → Entra interaction](../administration/sessions.md#interaction-with-entra-sso)).

## Authorization

- Permission decisions are made by `OmniAuthItemACL` under
  `OmniAuthAuthorizationStrategy`, a superset of Project-based Matrix Authorization.
- Access is grantable per user or group, global or job/folder-scoped, and can carry an
  expiry that is auto-enforced.
- **Least privilege** is the intended default: scope narrowly, prefer
  [time-limited](../administration/access-management.md#time-limited-access) and
  [JIT](../administration/jit-access.md) access over standing privilege, and re-certify with
  [access review](../administration/access-review.md).

## Account safety controls

| Control | Purpose |
|---|---|
| **Force password change (fail-closed)** | No session on any path until a new password is set. |
| **Break Glass (TOTP)** | Second-factor step-up for an already-authenticated admin before sensitive actions — not a lockout recovery. |
| **Brute-force lockout (enforced)** | After a threshold of failures, locks the account for a cooldown window (protected users exempt); rejected before the password check. |
| **SSO domain allowlist** | Rejects a Microsoft-verified sign-in whose email domain isn't on the allowed list, even if pre-provisioned or in an allowed group. |
| **Protected users** | Shields critical accounts from automated cleanup and lockout. |
| **Stale-user cleanup** | Retires inactive accounts (dry-run supported). |

## Auditability

All access changes, JIT decisions, break-glass activation, and security-relevant actions
are written to the [audit log](audit-logging.md). Notifications can mirror key events to
email/Slack/Teams in real time.

## Known limits (design around these)

- **Brute-force lockout is local to native authentication.** It enforces a cooldown lockout
  for native accounts past the failure threshold (protected users exempt); SSO sign-ins are
  governed by the identity provider's own protections, not this threshold.
- **No provider-side logout on Jenkins logout** — a signed-out user may be silently
  re-authenticated by the identity provider on the next SSO attempt (subject to the
  provider's session/MFA policy).
- **SSO is Microsoft Entra today.** The realm's provider selector lists other OAuth2/OIDC
  providers as *planned* — they are on the roadmap, not yet available. Don't assume
  vendor-neutral SSO: the sign-in path is Entra-coupled (MSAL4J / Microsoft Graph).

## Hardening checklist

- [ ] Serve Jenkins over **HTTPS** only; set the correct external URL.
- [ ] Register redirect URIs precisely; use single-tenant app registration.
- [ ] Enforce **MFA** in Entra Conditional Access for the Jenkins app.
- [ ] Keep at least one **native admin** and enroll **Break Glass** before relying on SSO.
- [ ] Scope grants narrowly; set expiries; enable **access review**.
- [ ] Rotate the **client secret** before expiry; store it only in Jenkins/secrets vault.
- [ ] Enable **notifications** for break-glass, JIT, and access changes.
- [ ] Start stale cleanup in **dry-run**; populate **protected users** first.

---
_Related: [Audit logging](audit-logging.md) · [Authentication](../administration/authentication.md)_
