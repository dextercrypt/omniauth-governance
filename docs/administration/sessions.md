# Sessions & session revocation

> See who is currently signed in and forcibly end sessions when needed.

Rotogate tracks active user sessions and lets administrators revoke them - essential for
incident response (a compromised account, an offboarded user, or a revoked grant that must
take effect immediately).

Managed at **Manage Jenkins → Rotogate → Sessions**.

## Active sessions view

The sessions page lists current authenticated sessions with identifying details so you can
tell who is active. Use it to spot unexpected sessions and to confirm the impact of a
revocation.

## Revoking a session

- **Revoke a single session** to force that session to re-authenticate on its next request.
- Revocation is the enforcement mechanism behind other features - for example, ending a
  user's access should be paired with revoking their live sessions so the change is
  immediate rather than waiting for the session to expire naturally.

Revoking a session invalidates the user's Jenkins session seed; it does **not** sign the
user out of Microsoft (their Entra/browser session is independent - see below).

## "Keep me signed in" duration

Native sign-in offers a **Keep me signed in** (remember-me) option on the login page. How long
that cookie stays valid is configurable at **Manage Jenkins → Rotogate → Settings**, under the
login-security settings:

- Set a **number** and a **unit** - Minutes, Hours, or Days. The default is **8 hours**.
- The duration is clamped to Jenkins' own 14-day remember-me ceiling. If you enter a value that
  would exceed 14 days for the chosen unit, it is capped; an empty or invalid value falls back to
  8 hours. The settings page shows the running maximum for the selected unit.
- The lifetime is applied **per login**, so changing it takes effect on the next sign-in without a
  realm reload.

This applies only to the **native** sign-in path. **Sign in with Microsoft** sessions are
session-only (no remember-me cookie) and are unaffected - their lifetime is governed by Entra.

## Interaction with Entra SSO

Ending a Jenkins session does not end the user's Microsoft session. On the next
**Sign in with Microsoft**, Entra may silently re-authenticate them into Jenkins (subject to
Entra's own session and MFA policies). Full Microsoft logout is intentionally **not**
performed, to avoid signing users out of Teams/Outlook/etc. Manage SSO session lifetime and
MFA in Entra Conditional Access.

## Related

- [JIT access](jit-access.md) - revoking an approved grant early relies on session control.
- [User lifecycle](user-lifecycle.md) - offboarding and stale-account cleanup.
- [Break Glass](break-glass.md) - emergency access if you revoke your own session by mistake.

---
_Next: [Break Glass](break-glass.md)_
