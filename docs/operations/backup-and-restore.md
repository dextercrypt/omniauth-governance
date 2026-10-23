# Backup & restore

> What to back up, how to restore, and how to migrate OmniAuth configuration between
> instances.

OmniAuth stores its configuration and governance data inside `JENKINS_HOME`. A standard
`JENKINS_HOME` backup captures everything; this page identifies the specific files so you
can back them up granularly or migrate them.

## What to back up

| Data | Location (under `JENKINS_HOME`) |
|---|---|
| Security realm + authorization strategy | `config.xml` |
| Plugin global config (branding, notifications, cleanup, thresholds) | `io.jenkins.plugins.omniauth.OmniAuthGlobalConfig.xml` |
| Access assignments | `io.jenkins.plugins.omniauth.OmniAuthAssignmentConfig.xml` |
| Role definitions | `io.jenkins.plugins.omniauth.OmniAuthRoleConfig.xml` |
| JIT requests/history | `io.jenkins.plugins.omniauth.OmniAuthJitRequestStore.xml` |
| Provisioning requests | `io.jenkins.plugins.omniauth.ProvisioningRequestConfig.xml` |
| Per-user properties (OID/UPN, last login, force-password, break-glass) | `users/<user>/config.xml` |
| Audit log | newline-delimited JSON monthly files `omniauth-audit-YYYY-MM.json` under `JENKINS_HOME/logs` |
| The plugin artifact | `plugins/omni-auth.jpi` |

> Exact filenames follow the `GlobalConfiguration` class names. Verify against your instance;
> confirm the precise audit-log path on a live system when finalizing your backup scope.

## In-product Backup & Restore UI

Beyond raw file copying, OmniAuth ships an **in-product Backup & Restore** console under
**Manage Jenkins → OmniAuth**, with **Export**, **Import**, and **Revert** actions,
restore-point history, and a colour-coded, Terraform-style plan modal shown before both
import and rollback so you can review the exact field-level changes first. See the
[Backup & restore design](../developer/backup-restore-design.md) for the details.

- **Covers:** roles, scoped access assignments, groups, and settings — exported as
  JCasC-shaped YAML.
- **Does NOT cover:** the security realm and secrets (no client secret / SMTP password).
- **Known scope gap:** the global permission matrix is **not** captured, so a bare native
  user holding only matrix grants shows "no changes" in the plan.

Use the UI for governance-data snapshots and reviewable restores; use the file/`JENKINS_HOME`
procedures below for a complete instance backup including the realm and secrets.

## Secrets

The Entra client secret and SMTP password are stored **encrypted** and are only decryptable
with the instance's `secret.key` / `secrets/` material. **Back up `JENKINS_HOME/secrets/`
together with the config**, or the encrypted values cannot be recovered on restore.

## Backup procedure

1. Optionally quiet down / stop Jenkins for a consistent snapshot.
2. Archive `JENKINS_HOME` (or at minimum the files above **plus** `secrets/`).
3. Store the archive securely — it contains security configuration and encrypted secrets.

## Restore procedure

1. Install the same (or newer) Jenkins and the `omni-auth` plugin version.
2. Restore the config files, `users/`, plugin data, and `secrets/` into `JENKINS_HOME`.
3. Start Jenkins and verify:
   - You can sign in (native and/or SSO).
   - Access assignments and roles are present.
   - Notifications send a test message.

## Migration between instances

- Prefer [Configuration as Code](configuration-as-code.md) for the declarative parts
  (realm, Entra, strategy) — commit YAML with secrets as `${VARS}`.
- For assignments/roles/JIT/audit, copy the corresponding XML files, or re-create via JCasC
  where supported.
- If the target instance has a different `secret.key`, re-enter secrets (client secret, SMTP
  password) after migration rather than copying encrypted values.

## Disaster recovery tips

- Keep at least one **native admin** credential outside the SSO dependency — this, not Break
  Glass, is your real recovery path if Entra is unavailable (Break Glass requires you to
  already be signed in as admin; see [Break Glass](../administration/break-glass.md)).
- Know the host-level recovery steps (disable security / edit `config.xml`) in case the
  authorization strategy itself is misconfigured.
- Test your restore on a scratch instance periodically.

---
_Related: [Configuration as Code](configuration-as-code.md) · [Troubleshooting](troubleshooting.md)_
