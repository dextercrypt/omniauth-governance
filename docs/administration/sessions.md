# Sessions & session revocation

> See who is currently signed in and forcibly end sessions when needed.

Omni Auth tracks active user sessions and lets administrators revoke them — essential for
incident response (a compromised account, an offboarded user, or a revoked grant that must
take effect immediately).

Managed at **Manage Jenkins → Omni Auth → Sessions**.

## Active sessions view

The sessions page lists current authenticated sessions with identifying details so you can
tell who is active. Use it to spot unexpected sessions and to confirm the impact of a
revocation.

> _🖼 screenshot: active sessions_

## Revoking a session

- **Revoke a single session** to force that session to re-authenticate on its next request.
- Revocation is the enforcement mechanism behind other features — for example, ending a
  user's access should be paired with revoking their live sessions so the change is
  immediate rather than waiting for the session to expire naturally.

Revoking a session invalidates the user's Jenkins session seed; it does **not** sign the
user out of Microsoft (their Entra/browser session is independent — see below).

## Interaction with Entra SSO

Ending a Jenkins session does not end the user's Microsoft session. On the next
**Sign in with Microsoft**, Entra may silently re-authenticate them into Jenkins (subject to
Entra's own session and MFA policies). Full Microsoft logout is intentionally **not**
performed, to avoid signing users out of Teams/Outlook/etc. Manage SSO session lifetime and
MFA in Entra Conditional Access.

## Related

- [JIT access](jit-access.md) — revoking an approved grant early relies on session control.
- [User lifecycle](user-lifecycle.md) — offboarding and stale-account cleanup.
- [Break Glass](break-glass.md) — emergency access if you revoke your own session by mistake.

---
_Next: [Break Glass](break-glass.md)_
