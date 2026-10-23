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

Because Break Glass sits *behind* authorization, a true lockout is recovered the standard
Jenkins way, from the controller host (not from the UI):

- Temporarily start Jenkins with security disabled
  (`-Djenkins.model.Jenkins.useSecurity=false`) or edit `config.xml` in `JENKINS_HOME` to
  reset the authorization strategy / security realm, then restart and reconfigure.
- Keep at least one **native** administrator account that does not depend on SSO, so an Entra
  outage never leaves you without a sign-in path.

This is a deliberate design choice: the plugin never ships a hidden, always-on root bypass —
recovery is an auditable, host-level action by whoever controls the server.

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
