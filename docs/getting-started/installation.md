# Installation & upgrade

> How to install, activate, upgrade, and roll back OmniAuth.

## Before you begin

- Confirm you meet the [requirements](requirements.md).
- **Back up `JENKINS_HOME`** (or at least `config.xml` and the plugin's config files —
  see [Backup & restore](../operations/backup-and-restore.md)).
- Have a way back in: know your native `admin` credentials, or configure
  [Break Glass](../administration/break-glass.md) before switching the security realm.

> ⚠️ Changing the security realm affects who can log in. Always keep a working local admin
> account (or an active session) until you have verified the new configuration.

## Install

### Option A — deploy the `.hpi` (recommended for pre-release builds)

1. Build or obtain `omni-auth.hpi` (see [Building & contributing](../developer/contributing.md)).
2. In Jenkins: **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**.
3. Upload `omni-auth.hpi`.
4. Restart Jenkins when prompted.

### Option B — side-load into `$JENKINS_HOME/plugins`

1. Copy the built artifact into the plugins directory as a `.jpi`:
   ```bash
   cp target/omni-auth.hpi "$JENKINS_HOME/plugins/omni-auth.jpi"
   ```
2. Remove any stale exploded directory or `.hpi` for the same plugin so Jenkins
   re-expands the fresh build:
   ```bash
   rm -rf "$JENKINS_HOME/plugins/omni-auth" "$JENKINS_HOME/plugins/omni-auth.hpi"
   ```
3. Restart Jenkins.

> **Note:** A leftover `omni-auth.hpi` next to `omni-auth.jpi` can shadow your new build.
> Keep exactly one, and prefer `.jpi` for side-loading.

## Activate

1. **Manage Jenkins → Security**.
2. Under **Security Realm**, choose **OmniAuth**.
3. (Optional) Under **Authorization**, choose **OmniAuth Authorization Strategy** to
   enable the access-management features. (You can also keep another strategy; the realm
   works independently, but governance features assume the OmniAuth strategy.)
4. Configure Microsoft Entra if using SSO — see [Entra setup](../administration/entra-setup.md).
5. **Save**, then verify you can still sign in (open a private browser window to test).

## Upgrade

1. Back up `JENKINS_HOME`.
2. Deploy the new `.hpi` (Option A) or replace the `.jpi` and clear the exploded dir
   (Option B).
3. Restart Jenkins.
4. Review the [Changelog](../CHANGELOG.md) for behavior changes and any migration notes.

Configuration stored by the plugin (global config, assignments, roles, JIT requests,
audit log) is preserved across upgrades in `JENKINS_HOME`.

## Roll back

1. Restore the previous plugin artifact (or `.jpi`).
2. Restore the backed-up configuration files if the newer version migrated them.
3. Restart Jenkins.

If you are locked out after an upgrade, use [Break Glass](../administration/break-glass.md)
or the emergency recovery steps in [Troubleshooting](../operations/troubleshooting.md).

---
_Next: [Quick start](quick-start.md)_
