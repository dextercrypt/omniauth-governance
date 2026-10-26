# Backup & Restore (UI import/export) — design

> Design and rationale for an OmniAuth-scoped configuration **backup/restore** exposed as
> Export/Import buttons in OmniAuth Management, aimed at admins who do **not** use JCasC.
>
> **Status: BUILT (v1), on branch `feature/backup-restore-ui`.** The shipped scope is the *simple*
> version agreed after discussion: **no security realm**, so the break-glass/realm-gating machinery
> below is **not** needed and was not built. What shipped: Export + Import + Revert for roles,
> assignments, groups and global settings, with validate-before-apply, an auto pre-import snapshot
> (keep last 5) + one-click Revert, and a lockout guard implemented as *apply → verify the acting
> admin still has ADMINISTER → auto-revert if not*. The "preview exact delta" is a client-side
> confirmation dialog in v1 (the real safety is snapshot + guard + revert, not the preview). The
> rest of this document is retained as the fuller design, should realm restore ever be added.
>
> Files: `OmniAuthConfigBackup.java` (serializer), `doExportConfig`/`doImportConfig`/`doRevertImport`
> in `OmniAuthManagementLink`, `backup.jelly` + sidebar link, `OmniAuthConfigBackupTest`.

- **Group id / artifact:** `io.jenkins.plugins:omniauth-governance`
- **Java package:** `io.jenkins.plugins.omniauth`
- **Depends on JCasC?** No. Reuses the JCasC *file shape*, not the JCasC *plugin*. See [JCasC support](#relationship-to-jcasc).

## Why this exists

### Audience
The one audience JCasC and the raw XML files do **not** serve: **web-only admins** — hosted/managed
Jenkins, no shell access to `$JENKINS_HOME`, and no appetite to install the JCasC plugin or learn
YAML. For them a one-click Export/Import in the UI is the *only* way to back up and restore OmniAuth
configuration.

### Relationship to JCasC
This is **not** a second config system. It reuses the same model built for JCasC — the
`@DataBoundSetter`/getter surface on `OmniAuthGlobalConfig`, `OmniAuthRoleConfig`,
`OmniAuthAssignmentConfig`, `OmniAuthGroupEntity`, `OmniAuthSecurityRealm`/`EntraOAuthConfig`. So:

| | JCasC | Backup/Restore button |
|---|---|---|
| Audience | GitOps / IaC teams | Web-only admins (no shell, no JCasC) |
| Trigger | `jenkins.yaml` on boot / Reload | Buttons in OmniAuth Management |
| Needs JCasC plugin | Yes | **No** (self-contained) |
| File shape | JCasC YAML (whole controller) | Same YAML shape, **OmniAuth sections only** |
| Underlying apply | Setters + `save()` | **Same** setters + `save()` |

They are **one config engine with two front doors**, not two parallel systems. A file exported by the
button is a valid JCasC fragment; a JCasC admin can paste it into their `jenkins.yaml`.

### Is this overengineering alongside JCasC?
Honest answer: **no** — different audiences, one shared engine. The earlier "ship Export alone first"
idea is withdrawn: **export without import is half a feature** (you can record/diff/hand-off config
but can only "restore" by hand-retyping it). The value is the *round trip*, so build both together.
Export is still not wasted — it is the backup half *and* the primitive that makes import reversible
(the pre-import snapshot is the export function pointed at disk). See [Phasing](#phasing).

## Scope

### In scope (config)
- Security realm + Entra config (`tenantId`, `clientId`, `enableGroupSync`, `ssoProvider`, `allowsSignup`) — **secret excluded**
- Roles (`OmniAuthRoleConfig`)
- Assignments + Access-Management groups (`OmniAuthAssignmentConfig`)
- All of `OmniAuthGlobalConfig` (brute-force, provisioning policy, notifications settings, SMTP server, branding, schedules) — **secrets excluded**

### Out of scope (operational data — never backed up)
- JIT request queue (`OmniAuthJitRequestStore`)
- Provisioning request queue (`ProvisioningRequestConfig`)
- Notification log (`omniauth-notification-log.xml`)
- Cleanup history

### Secrets — never in the file
Export **strips** every secret (Entra `clientSecret`, `smtpPassword`, Slack/Teams webhook URLs).
The file carries placeholder comments, and the post-import screen lists exactly which to re-enter.
This is the agreed policy: **a portable backup never contains a usable secret.** (JCasC's own
`${ENV}`/encrypted paths are unaffected; this rule is about the portable backup file.)

## File format

JCasC-shaped YAML, OmniAuth sections only, secrets as placeholder comments:

```yaml
# OmniAuth configuration backup — 2026-10-19T09:00:00Z — from jenkins.example.com
# Restore: OmniAuth Management > Backup & Restore > Import.
# Secrets are NOT included. After import, re-enter:
#   - Entra client secret           (Security realm)
#   - SMTP password                 (Notifications)
#   - Slack / Teams webhook URLs    (Notifications)
securityRealm:
  omniAuth:
    ssoProvider: ENTRA
    allowsSignup: false
    entraConfig:
      tenantId: "c5b6..."
      clientId: "06ad..."
      enableGroupSync: true
      # clientSecret: <re-enter after import>
unclassified:
  omniAuthRoles:
    roles: [ ... ]
  omniAuthAssignments:
    groups: [ ... ]
    assignments: [ ... ]
  omniAuthGlobalConfig:
    bruteForceThreshold: 5
    provisioningEnabled: true
    smtpHost: "smtp.contoso.com"
    smtpEnabled: true
    # smtpPassword: <re-enter>
    # slackWebhookUrl: <re-enter>
```

A header block records when/where it was taken and the secrets to re-enter. A small
`omniauthBackupVersion` key allows forward-compatible parsing.

## Export design

- **UI:** a "Backup & Restore" card in OmniAuth Management with an **Export configuration** button.
- **Handler:** `doExportConfig` — `@POST`, `Jenkins.ADMINISTER` required (the file is sensitive even
  without secrets: roles, assignments, allowed domains).
- **Behaviour:** read live config via getters → build the YAML (secrets stripped) → respond with
  `Content-Disposition: attachment; filename="omniauth-backup-<host>-<yyyyMMdd-HHmm>.yaml"`.
- **Risk:** effectively none — read-only, no secrets, admin-gated.

## Import design — risk-free apply

Import is destructive (replace semantics) and the dangerous half. The goal is not the impossible
"zero risk" of any config overwrite, but a stronger, achievable guarantee: **no unrecoverable
outcome** — every failure mode has an undo or a recovery, with no dead end. The two real fears and
how each is neutralised:

- **"It wipes my config"** → the automatic pre-import snapshot + one-click **Revert last import**
  make every import undoable (mechanism 2).
- **"It locks me out"** → the realm is left untouched by default so the login path cannot change
  (realm restore is explicit opt-in); the lockout guard refuses a dangerous import before applying;
  and **native login is the real recovery net** — `OmniAuthSecurityRealm extends
  HudsonPrivateSecurityRealm`, so a native admin account always works even if Entra is broken.

> **Note on break-glass TOTP.** Break-glass is *not* a lockout-recovery path. It is the TOTP gate
> that unlocks editing the (OmniAuth-locked) security realm on `/configureSecurity` — you must
> already be logged in to use it. The genuine "always get back in" guarantee is a **native admin
> account** (inherited username/password login). Keep one.

Five mechanisms make it safe:

### 1. Validate-then-apply (two phase)
Parse the whole YAML and **validate before touching anything**: known keys only, enum values legal
(`ssoProvider`, `scopeType`, `accessType`, `provisioningExpiryAction`), permission IDs resolvable,
required fields present, no malformed entries. Build the target objects in memory. **Apply only if
validation passes** — never half-apply. On any apply-time error, auto-revert (mechanism 2).

### 2. Automatic pre-import snapshot (the undo)
Immediately before applying, serialize the current config (same export format, but retaining the
*encrypted* secrets internally) to `$JENKINS_HOME/omniauth-backups/pre-import-<timestamp>.yaml`,
keeping the last N. Expose a **"Revert last import"** button. This is the primary safety net and
makes the destructive replace reversible.

### 3. Preview / confirm
Before applying, show the exact delta: e.g. *"roles 3 → 2 (removing `Legacy`); assignments 12 → 14;
groups 2 → 2; realm → Entra tenant `c5b6…`; 5 global settings change."* Destructive deltas (removing
the acting admin's own grant, changing the realm) render as loud warnings. Require explicit confirm.

### 4. Lockout guard
Before applying, simulate the resulting ACL and check:
- Would the **acting admin** still hold `Jenkins.ADMINISTER`?
- Would **at least one** principal retain administer (global Administer, or an Administrator-equiv
  role assigned to a still-present user/group)?
- Does a usable **native admin account** survive (the real recovery path — see below)?

If not → **refuse**, or offer to **preserve/add the acting admin as an administrator** before
applying. This is the dominant risk when reimporting onto a *different* controller (see
[Cross-controller reimport](#cross-controller-reimport)), where the imported assignments were
written for the old controller's identities and may not cover the new admin.

The genuine recovery net is **native login**, not break-glass: `OmniAuthSecurityRealm extends
HudsonPrivateSecurityRealm`, so a native admin account keeps working even if Entra/authz is broken.
Break-glass TOTP is only the gate for *editing the realm while logged in* — not a way back in.

### Realm restore is gated by break-glass TOTP
OmniAuth locks its security-realm config behind break-glass TOTP on `/configureSecurity`. The import
must honour the same gate: **applying a realm change requires an active break-glass session**, so
import is not a backdoor around that protection. If the admin has no TOTP device enrolled, realm
restore is unavailable — they import everything *except* the realm and set the realm by hand (exactly
as the manual realm edit already behaves).

### Cross-controller reimport
Fresh Jenkins, web-only admin, importing a backup from an old controller:
- Native login carries the admin through the realm switch (inherited), so the realm is **not** the
  main risk; Entra simply won't work until the secret is re-entered.
- The main risk is that the old controller's **assignments reference old identities** and may not
  grant the *new* acting admin — handled by the lockout guard above (refuse / offer to add them).
- Flow: install OmniAuth + keep a native admin → import roles/assignments/groups/global (realm
  opt-in, break-glass-gated) → guard confirms the acting admin keeps admin → re-enter the stripped
  secrets → Entra login works, native admin stays as the permanent fallback.

### 5. Secret preservation
Because secrets are never in the file, import **must skip** them, not clear them:
- List properties (`roles`, `assignments`, `groups`) → **replace** (restore semantics).
- Scalar config fields present in the YAML → **set**.
- Secret fields (absent from the YAML) → **skip** — existing `clientSecret`/`smtpPassword`/webhooks
  are **preserved**. On a same-host restore nothing needs re-entry; only a fresh/clone install does.

### Realm handling (most sensitive)
Changing the security realm re-initialises authentication. Options:
- **A — include realm, guarded:** restore the realm with the lockout guard + hard confirmation.
- **B — realm opt-in:** import roles/assignments/groups/global by default and leave the realm
  untouched unless the admin ticks *"also restore sign-in / realm config."*

**Open decision** — see below. Default leaning: **B** (safer), with A available via the checkbox.

### Apply order & atomicity
Apply in a fixed order (global config → roles → groups → assignments → realm last), each `set…()`
then `save()`, then `OmniAuthAuthorizationStrategy.invalidateCache()`. Wrap in try/catch; on failure,
restore the pre-import snapshot. No restart required (live, via the object model).

### Permissions, CSRF, audit
`Jenkins.ADMINISTER` + `@POST` + crumb on both handlers. Every import writes an audit entry (who,
when, counts changed, whether realm was touched) to the existing audit/notification log.

## Phasing

Build the **round trip together** — export-only is half a feature. Export is sequenced first only as
a *build step*, because the snapshot/undo reuses it:

1. **Export primitive** — serialize live config → YAML (secrets stripped). Also the backup half.
2. **Auto-snapshot** — the export primitive pointed at `$JENKINS_HOME/omniauth-backups/`.
3. **Validated apply** — parse + validate, then set + `save()` + cache invalidate (secret-preserving).
4. **Preview + lockout guard + Revert** — the safety UX around apply.

### Worst-case walkthrough (no dead end)
1. Sketchy file uploaded → validation rejects malformed input; config untouched.
2. Valid → preview shows the exact delta with warnings on destructive changes.
3. Confirm → lockout guard refuses if it would strip the last admin.
4. Applied live. Looks wrong → **Revert last import** → exact prior state.
5. Entra not yet working (secret not re-entered) or realm misconfigured → **native admin login**
   still works (inherited realm) → fix and move on.

## v2 improvements — BUILT (2026-10-19)

Captured after v1 shipped, then built the same day. All three live-verified.

1. **Revert = a restore-point history list (built).** The "Restore points" card lists the last 5
   pre-import snapshots, each with its timestamp and a one-line summary of what it holds (N roles /
   assignments / groups), and a "Roll back to this" button per row. The revert endpoint takes a
   `snapshot` parameter; absent → most recent (back-compat). The name is validated against
   `pre-import-\d{8}-\d{6}\.yaml` and must resolve inside the snapshot dir (path-traversal guard —
   verified by refusing `../../secrets/master.key`). **Each roll-back previews first** (`doPreviewRevert`)
   with the *same* plan modal as import — diffing the chosen snapshot against the live config — so a
   restore is as auditable as an import; "Apply rollback" then submits. (`form.submit()` bypasses the
   preview submit-handler, so Apply doesn't re-trigger it.)
2. **Import = a Terraform-style plan modal (built).** The import button is now "Review changes &
   import". It POSTs to `doPreviewImport`, which returns the plan as JSON (never applies anything):
   roles / groups / assignments added (`+`, green) / removed (`-`, red) / changed (`~`, amber), and
   each changed global setting as `key: from → to`. A **changed** item is expanded to field-level
   detail (`ItemChange.lines`) — e.g. a role shows `description: "x" → "y"` and `permissions:
   +A, -B`; an assignment shows role / custom-permissions / access-type / approvers / duration /
   expiry deltas; each line is `current → incoming`. A modal renders the plan; "Apply import"
   submits the real import form; empty plan shows "No changes — the live configuration already
   matches." Engine: `OmniAuthConfigBackup.diff(Parsed)` vs `OmniAuthConfigBackup.current()`.
3. **Import history (built).** The "Recent activity" card below Restore points shows the last 5
   import/revert events (when, who, what changed). Persisted to
   `$JENKINS_HOME/omniauth-backups/import-history.json` (newest first, keep 20, show 5) via
   `recordHistory(action, summary)`. Import logs the diff summary; revert logs the point restored.

New code: `OmniAuthConfigBackup` — `current()`, `Diff`/`SettingChange`, `diff()`.
`OmniAuthManagementLink` — `doPreviewImport`, `getSnapshots()`/`SnapshotInfo`,
`getImportHistory()`/`HistoryEntry`/`recordHistory`, `resolveSnapshot()`, snapshot-aware
`doRevertImport`. Tests: 4 added to `OmniAuthConfigBackupTest` (diff empty / add-remove-change /
setting change / readable summary); full suite **520, 0 failures**.

## Scope clarification — global permission matrix is NOT captured (by design, 2026-10-19)

Access lives in three places; the backup covers only the third:
1. **Security realm** — the native user *account* (username/password). Excluded by design (so an
   import can't change sign-in / lock you out).
2. **Global permission matrix** — held by `OmniAuthAuthorizationStrategy` (extends
   `ProjectMatrixAuthorizationStrategy`); this is where `grantGlobalRead`/`grantGlobalRole` put every
   native user's **Hudson.Read login grant** and any role granted at **global** scope.
   **NOT exported.**
3. **OmniAuth assignments** — `OmniAuthAssignmentConfig`: **scoped** role grants (user/group @
   folder/job). **Exported.**

Consequence: creating a bare native user, or granting purely at global scope, shows **"no changes"**
in a plan — the account is realm data and its login grant is a global-matrix entry, neither in scope.
This is expected. A **v3** could add global-matrix capture (global role/Hudson.Read grants) for
fuller replication; the lockout guard + realm-exclusion would still prevent an import from locking
the admin out. Deliberately deferred — not bolted onto v2.

## Open decisions

1. **Realm on import:** default-include-with-guard (A) vs opt-in checkbox (B). *Leaning B.*
2. **Snapshot retention:** how many pre-import snapshots to keep (proposed: last 5).
3. **Cross-host clone:** explicitly a supported use (secrets re-entered) or discourage? (Affects
   wording only; mechanism is identical.)
4. **Scope toggle:** allow exporting/importing a subset (e.g. roles+assignments only) or always
   whole-config? (Proposed: whole-config to keep it simple.)

## Testing plan

- Export: YAML has no secret material; contains all config groups; is parseable; filename shape.
- Import round-trip: export → wipe/alter → import → state matches the file.
- Validation: malformed YAML, unknown keys, illegal enum, unknown permission ID → rejected, config
  untouched.
- Secret preservation: import with secrets absent → existing secrets unchanged.
- Lockout guard: an import removing the last admin → refused (or guarded).
- Snapshot/revert: import then "Revert last import" restores prior state.
- Permissions: non-admin blocked; GET blocked (POST + crumb only).
