# Troubleshooting

> Common problems and how to resolve them, including emergency recovery from a lockout.

## Emergency: locked out of Jenkins

If no one can sign in (bad authorization change, SSO outage, lost admin password):

1. **Use [Break Glass](../administration/break-glass.md)** if enrolled — activate with your
   TOTP code and restore access.
2. **Fall back to native admin** if you have one.
3. **Last resort — edit `config.xml`:** stop Jenkins, edit `$JENKINS_HOME/config.xml` to a
   permissive realm/strategy (e.g. temporarily disable security), restart, fix the
   configuration, then re-enable OmniAuth. Treat this as a break-glass action and audit it.

> Prevention: always keep a working native admin **and** enroll Break Glass before relying on
> SSO. Test login in a private window after any security change.

## SSO issues

| Symptom | Cause / fix |
|---|---|
| `AADSTS50011` (redirect mismatch) | The Entra redirect URI must be exactly `https://<host>/<prefix>/omniauth/finishLogin`. Check scheme, host, port, and prefix. |
| `AADSTS7000215` (invalid client secret) / `AADSTS700016` (wrong client/tenant) | Secret expired or mistyped, or wrong tenant/client — use the **Test Connection** button on the Entra config card (it performs a real client-credentials token grant) to confirm tenant + client + secret before anything else, then create a new secret in Entra and update the plugin if needed. |
| Login redirects back to the login page (loop) | Session/seed not persisted — verify the Jenkins **external URL**, reverse-proxy headers, and cookie settings. |
| SSO succeeds but redirects to `loginError?error=domainNotAllowed` | The user's email domain is not in **Allowed email domains** on the Entra config; the login is rejected and audited as `login_denied_domain`. Add the domain to the allowlist (empty = allow all). This is a rejected login, distinct from the row below where login *succeeds* but no access is granted. |
| SSO succeeds but user has no permissions | No [access assignment](../administration/access-management.md) for that email — pre-provision or grant access. |
| Groups missing | `GroupMember.Read.All` not admin-consented, or **Group Sync** disabled. |

## Login page not customized / default page shows

- The **custom** login page is at `/<prefix>/securityRealm/login`, not `/<prefix>/login`.
- After changing branding, do a hard refresh; some assets are cached.

## Notifications not sending

| Channel | Checks |
|---|---|
| Email | Correct host/port/TLS; credentials; from-address allowed by relay; use **Send test email**. Check the **notification log**. |
| Slack / Teams | Webhook URL valid and not revoked; use the channel's **test** action. |

Delivery retries on failure; persistent failures appear in the notification log.

## Stale cleanup removed unexpected accounts

- Run with **Cleanup dry run** enabled first and review candidates.
- Add critical accounts to [Protected users](../administration/user-lifecycle.md#protected-users).
- Lower **Max deletions per run** as a safety cap.

## Build / deployment issues

| Symptom | Fix |
|---|---|
| Maven build fails in the license/spotless plugins | These are intentionally skipped in `pom.xml` (`license.skip`, `spotless.check.skip`); ensure you build the project's own POM. |
| New build doesn't take effect | A stale `omniauth-governance.hpi` next to `omniauth-governance.jpi`, or a leftover exploded `plugins/omniauth-governance/` dir, can shadow the new build. Remove them and restart. See [Installation](../getting-started/installation.md). |
| Plugin won't load | Check the Jenkins version (2.541.2+) and Java 21; review the controller log for missing dependency plugins. |

## Where to look

- **Jenkins system log:** Manage Jenkins → System Log.
- **Audit log:** Manage Jenkins → OmniAuth → Audit log (what changed).
- **Notification log:** Manage Jenkins → OmniAuth → Notifications (delivery attempts).

If you file an issue, include the Jenkins version, plugin version, and relevant controller
log excerpts. See [Contributing](../developer/contributing.md).

---
_Related: [Security model](../security/security-model.md) · [Backup & restore](backup-and-restore.md)_
