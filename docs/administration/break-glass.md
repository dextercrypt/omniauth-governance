# Break Glass (step-up confirmation)

> A TOTP step-up that an already-signed-in administrator activates before performing
> high-risk actions.

"Break Glass" is a **deliberate, audited step-up**: an administrator who is **already
authenticated with Overall/Administer** proves presence with a one-time TOTP code to open a
short, logged window for sensitive operations. It exists so that the riskiest actions require
a fresh, second-factor confirmation and leave a clear trail — not so that someone can get *in*.

Managed at **Manage Jenkins → OmniAuth → Break Glass**.

> **Important — this is NOT a lockout recovery path.** Activating Break Glass requires you to
> already hold Overall/Administer (the handler checks `ADMINISTER` before anything else). If
> you are genuinely locked out — no admin can sign in, the authorization strategy misfired, or
> the last admin password is lost — Break Glass cannot help, because you can't reach it. Use
> Jenkins' standard out-of-band recovery instead: see [If you are actually locked
> out](#if-you-are-actually-locked-out) below.

## What it is (and isn't)

- **Is:** a second-factor (TOTP) confirmation for an administrator who is already logged in,
  opening a **15-minute** elevated/confirmed window that auto-expires. Every activation and
  deactivation is recorded in the [audit log](../security/audit-logging.md).
- **Isn't:** a way back in when you cannot authenticate. It does not bypass the security
  realm or the authorization strategy, and it grants nothing to a user who lacks admin.

## Enrollment

Enroll a TOTP factor before you need it:

1. **Manage Jenkins → OmniAuth → Break Glass → enroll**.
2. Scan the displayed **QR code** with an authenticator app (Microsoft Authenticator,
   Google Authenticator, etc.).
3. Verify a generated code to confirm enrollment.

**Multiple devices:** you can enrol up to **three** devices per administrator (so a lost
phone doesn't strand you), and remove any of them individually. Enrolling more than one
trusted administrator is recommended per your policy.

## Activation

1. Go to **Break Glass** and choose **Activate**.
2. Enter a **reason** (required, for the audit trail) and the current **6-digit TOTP code**.
3. On success, the confirmed window is open for **15 minutes**, then expires automatically.
4. **Deactivate** as soon as you are done — don't wait for the timeout.

Activation, deactivation, and failed attempts are recorded in the
[audit log](../security/audit-logging.md).

## If you are actually locked out

Because Break Glass sits *behind* authorization, a true lockout — every admin is SSO-only and
Entra is down, or the authorization strategy misfired so no one holds **Administer** — is
recovered from the **controller host**, not the UI. The best first line of defence is simply to
keep at least one **native** administrator account that does not depend on SSO.

If even that is unavailable, OmniAuth ships an **SOS Mode** recovery path (see below) that is
safer and more targeted than disabling security wholesale.

### SOS Mode (recommended)

SOS Mode turns host access to `JENKINS_HOME` into a narrow, audited recovery window instead of a
blunt "turn security off". Each administrator holds a set of **SOS recovery codes** (generated
once under **Manage Jenkins → OmniAuth → Emergency Access**; enrollment is mandatory and codes
are stored only as salted hashes, so a copy of `JENKINS_HOME` cannot reveal them).

1. On the controller host, create an empty trigger file in `JENKINS_HOME`:
   ```
   touch "$JENKINS_HOME/omniauth-sos.request"
   ```
   No restart is needed — the file is checked live. SOS Mode arms immediately and stays armed for
   **30 minutes** from the file's timestamp.
2. In a browser, go to **`/sos/recover`** (it is dark — redirects to the login page — unless SOS
   is armed). A site-wide banner on every Jenkins page also announces that SOS Mode is active.
3. Enter one of your **SOS recovery codes**. No username is asked: the matching code identifies
   which admin you are. That admin is re-granted global **Administer** (repairing the "nobody is
   admin" case) and signed in.
4. Repair a real admin account, then **Deactivate** SOS from the banner or from
   **Settings** (it also auto-closes after 30 minutes, or after 5 failed code attempts).

**Realm gate — SOS only arms when OmniAuth is your Security Realm.** SOS is a recovery path *into
OmniAuth logins*, so it stays completely inert on any instance that does not use OmniAuth to
authenticate. If the plugin is merely installed (a different realm is active), or you have switched
away from OmniAuth (leaving stale recovery-code hashes on disk), dropping the trigger file does
**nothing** — `/sos/recover` never leaves its dark state. This is what keeps an idle or abandoned
install from becoming a backdoor. The genuine lockout cases are unaffected: when your identity
provider is merely unreachable, or the authorization strategy is misconfigured, OmniAuth is still
the *configured* realm, so SOS arms normally.

**Restoring admin depends on the authorization strategy** (a separate setting from the realm). SOS
re-grants **Administer** into any *matrix-based* strategy — OmniAuth's own, or the stock
*Matrix-based* / *Project-based Matrix* strategies. If the active strategy is **not** matrix-based
(Role-Based Strategy, a custom strategy, "anyone can do anything"), SOS still signs you in but tells
you plainly that admin could not be restored, and you repair it via the host-level steps below.

Every transition is recorded in the [audit log](../security/audit-logging.md): `sos_armed`,
`sos_recovery_success` (with the resolved admin and IP), `sos_recovery_failed`, `sos_deactivated`,
`sos_expired`, `sos_locked`. SOS Mode can also be turned off (**Settings → SOS Mode**, `sosEnabled`)
for environments that forbid it; note that anyone with filesystem access could re-enable it, so
that toggle is a policy control, not a security boundary — the realm gate above is the real one.

### Last-resort host recovery

If SOS Mode cannot be used (no admin ever enrolled recovery codes, OmniAuth is not the active
Security Realm so SOS never arms, or the authorization strategy is not matrix-based so admin cannot
be restored), fall back to the standard Jenkins procedure: start with security disabled
(`-Djenkins.model.Jenkins.useSecurity=false`) or edit `config.xml` in `JENKINS_HOME` to reset the
authorization strategy / security realm, then restart and reconfigure.

This is a deliberate design choice: the plugin never ships a hidden, always-on root bypass — every
recovery path is an auditable, host-level action by whoever controls the server.

## Operational guidance

- **Enroll before you need it.** Enrollment still requires being signed in as admin.
- **Protect the enrolled device/secret** like a privileged credential.
- **Deactivate promptly** and review the audit trail after every use.
- Keep a break-glass **native admin** account outside SSO as your real outage safety net.

## Reminders

The plugin can surface TOTP enrollment reminders so enrollment doesn't lapse; reminders can
be skipped per user. See the [Configuration reference](../reference/configuration-reference.md).

---
_Next: [Notifications](notifications.md)_
