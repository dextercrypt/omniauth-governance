# Break Glass emergency access

> An MFA-protected recovery path for when normal admin or SSO login is unavailable.

"Break Glass" is the emergency access pattern: a rarely-used, tightly-controlled way back
into Jenkins when the usual doors are closed — an expired client secret, a
misconfigured authorization strategy, an Entra outage, or a lost admin password.

Access is protected by a **TOTP** second factor (enrolled via QR code), so possession of the
break-glass path alone is not enough.

Managed at **Manage Jenkins → Omni Auth → Break Glass**.

## When to use it

- SSO is down or misconfigured and no native admin can sign in.
- The authorization strategy change locked everyone out.
- An admin credential was lost and must be recovered under control.

Break Glass is **not** for day-to-day administration. Every use should be deliberate,
logged, and reviewed.

## Enrollment

Before an emergency, enroll the TOTP factor:

1. **Manage Jenkins → Omni Auth → Break Glass → enroll**.
2. Scan the displayed **QR code** with an authenticator app (Microsoft Authenticator,
   Google Authenticator, etc.).
3. Verify a generated code to confirm enrollment.

> _🖼 screenshot: break-glass QR enrollment_

You can remove and re-enroll the factor if a device is lost.

## Activation

In an emergency:

1. Go to the Break Glass activation path.
2. Provide the current **TOTP code** from your authenticator.
3. On success, emergency access is activated for recovery actions.
4. **Deactivate** as soon as normal access is restored.

Activation and deactivation are recorded in the [audit log](../security/audit-logging.md).

## Operational guidance

- **Enroll before you need it.** Enrollment during an outage may be impossible.
- **Protect the enrolled device/secret** like a privileged credential; store recovery
  material in your secrets vault.
- **Deactivate promptly** and review the audit trail after every use.
- Consider enrolling more than one trusted administrator (per your policy).

## Reminders

The plugin can surface TOTP reminders so enrollment doesn't lapse; reminders can be skipped
per user. See the [Configuration reference](../reference/configuration-reference.md).

---
_Next: [Notifications](notifications.md)_
