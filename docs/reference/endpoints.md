# HTTP endpoints reference

> The plugin's HTTP endpoints, grouped by function. Paths are relative to the Jenkins
> context (`/<prefix>`).

These are Stapler `do*` handlers exposed by the security realm, the JIT action, and the
management console. Most administrative endpoints require appropriate permissions and are
reached through the console UI rather than directly.

## Authentication & login (security realm)

| Endpoint | Purpose |
|---|---|
| `securityRealm/login` | The custom login page (native + SSO). |
| `omniauth/nativeLogin` | Native username/password form POST target. |
| `securityRealm/commenceLogin` | Start the Entra OAuth2 flow. |
| `omniauth/finishLogin` | OAuth2 redirect/callback (must match the Entra redirect URI). |
| `omniauth/loginLogo` (`doLoginLogo`) | Serve the branded login logo pre-authentication. |
| `descriptorByName/io.jenkins.plugins.omniauth.EntraOAuthConfig/testConnection` (`doTestConnection`) | Validate Entra credentials via a real MSAL client-credentials token grant. `@RequirePOST`, admin-gated. |

> **Login error codes.** Failed or rejected sign-ins redirect to `loginError?error=<code>`:
> `locked` (brute-force lockout), `activation` (account not yet activated), `entra` (Entra/SSO
> error), `notProvisioned` (user not pre-provisioned), `domainNotAllowed` (email domain not in
> the sign-in allowlist), `session` (session/state problem).

## Native account / password

| Endpoint | Purpose |
|---|---|
| `doChangePassword` | Change password. |
| `doSubmitPasswordChange` / `doCheckNativeUser` | Force-password-change submission and checks. |
| `omniauth/activate` (`doActivate`) | Native account activation link (one-time). |

## Break Glass

| Endpoint | Purpose |
|---|---|
| `doBreakGlass` | Break-glass entry page. |
| `doBreakGlassEnrollInit` / `doBreakGlassEnrollVerify` / `doBreakGlassEnrollRemove` | TOTP enrollment lifecycle. |
| `doBreakGlassActivate` / `doBreakGlassDeactivate` | Activate/deactivate emergency access. |
| `doTotpReminder` / `doTotpReminderSkip` | TOTP enrollment reminders. |

## Access management

| Endpoint | Purpose |
|---|---|
| `doAccessManagement` | Access management page. |
| `doAddUser` / `doDeleteUser` | Manage user entries. |
| `doGrantAssignment` / `doEditAssignment` / `doRevokeAssignment` | User assignment lifecycle. |
| `doGrantGroupAssignment` / `doRevokeGroupAssignment` / `doGroupDetail` / `doRemoveGroup` | Group assignment lifecycle. |
| `doGrantScopedAccess` / `doRevokeScopedAccess` / `doScopedAccess` / `doAllScopedAccess` | Scoped (job/folder) access. |
| `doSetNodePermission` / `doRemoveNodePermission` | Node-level permissions. |
| `doCheckAccess` / `doConfirmAccess` / `doConfirmAllAccess` / `doRemoveUserAccess` | Access checks/confirmation. |
| `doSaveRole` / `doDeleteRole` / `doSetUserRole` | Role management. |

## Access review

| Endpoint | Purpose |
|---|---|
| `doAccessReview` | Access review queue. |
| `doConfirmAccess` / `doConfirmAllAccess` / `doRevokeFromReview` | Keep / keep-all / revoke during review. |

## JIT access

| Endpoint | Purpose |
|---|---|
| `doRequest` | Submit a JIT request. |
| `doStatus` | JIT request status for the current user. |
| `doCancel` | Cancel a pending JIT request. |
| `doJitRequests` | JIT requests queue (admin). |
| `doApproveJit` / `doDenyJit` / `doRevokeActiveJit` | Approve / deny / revoke JIT. |
| `doSuggestApprovers` | Approver suggestions. |

## Sessions

| Endpoint | Purpose |
|---|---|
| `doSessions` | Active sessions view. |
| `doRevokeSession` | Revoke a session. |

## Users & cleanup

| Endpoint | Purpose |
|---|---|
| `doStaleUsers` | Stale users view. |
| `doProtectedUsers` / `doSaveProtectedUsers` | Manage protected users. |
| `doMarkForDeletion` / `doUnmarkForDeletion` | Cleanup marking. |
| `doRunCleanupNow` | Trigger a cleanup run. |
| `doUserOverview` / `doUserStatus` / `doStatus` | User status views. |

## Notifications

| Endpoint | Purpose |
|---|---|
| `doNotifications` / `doSaveNotifications` | Notification settings. |
| `doPreviewEmail` | Email template preview. |
| `doSendTestEmail` / `doSendTestSlack` / `doSendTestTeams` | Channel test sends. |
| `doNotificationLog` / `doClearNotificationLog` | Notification delivery log. |

## Provisioning (admin queue)

| Endpoint | Purpose |
|---|---|
| `doProvisioning` | Provisioning request queue. |
| `doApproveProvisioning` / `doRejectProvisioning` | Approve / reject a request. |
| `doResendActivation` | Resend a native account's activation link. |
| `doResetProvisioningBreaker` | Reset the native request rate-limit breaker. |
| `doSaveProvisioningSettings` | Save provisioning-portal settings. |

## Provisioning portal (self-service)

Served under `request-access` (`ProvisioningPortalAction`).

| Endpoint | Purpose |
|---|---|
| `doSubmitNative` | Submit a native (username/password) access request. |
| `doSubmitEntraReason` | Submit an Entra/SSO access request with a reason. |

## Backup & restore

| Endpoint | Purpose |
|---|---|
| `doBackup` | Download a configuration backup. |
| `doExportConfig` / `doImportConfig` | Export / import roles, scoped assignments, groups and settings. |
| `doPreviewImport` / `doPreviewRevert` | Preview an import or a rollback before applying. |
| `doRevertImport` | Roll back to a prior restore point. |

## Audit, security & settings

| Endpoint | Purpose |
|---|---|
| `doAuditLog` / `doPurgeAuditLog` | View / purge audit log. |
| `doSecurity` | Security overview page. |
| `doClearBruteForce` | Reset brute-force counters. |
| `doSettings` / `doSaveSettings` | Plugin settings (incl. branding). |
| `doUploadLoginLogo` / `doRemoveLoginLogo` | Manage the login logo. |
| `doOverviewMetrics` | JSON KPI metrics for dashboard polling (admin-gated). |
| `doAbout` | About/info page. |

> This reference is generated from the plugin's `do*` handlers. Exact HTTP method
> (GET/POST) and required permission are defined on each handler; interact through the
> console UI unless integrating directly.

---
_Related: [Architecture](../developer/architecture.md) · [Permissions reference](permissions.md)_
