# HTTP endpoints reference

> The plugin's HTTP endpoints, grouped by function. Paths are relative to the Jenkins
> context (`/<prefix>`).

These are a mix of Stapler `do*` action handlers and management-console **pages** (Jelly views
served at `rotogate-management/<name>`, with no `do` prefix), exposed by the security realm, the
root action, and the management console. Rows written as `doXxx` are action handlers; rows written
as a bare name are page views. Most administrative endpoints require appropriate permissions and
are reached through the console UI rather than directly.

## Authentication & login (security realm)

| Endpoint | Purpose |
|---|---|
| `securityRealm/login` | The custom login page (native + SSO). |
| `rotogate/nativeLogin` | Native username/password form POST target. |
| `rotogate/commenceLogin` | Start the Entra OAuth2 flow. |
| `rotogate/finishLogin` | OAuth2 redirect/callback (must match the Entra redirect URI). |
| `securityRealm/loginLogo` (`doLoginLogo`) | Serve the branded login logo pre-authentication. |
| `descriptorByName/io.jenkins.plugins.rotogate.sso.EntraOAuthConfig/testConnection` (`doTestConnection`) | Validate Entra credentials via a real MSAL client-credentials token grant. `@RequirePOST`, admin-gated. |

> **Login error codes.** Failed or rejected sign-ins redirect to `loginError?error=<code>`:
> `locked` (brute-force lockout), `activation` (account not yet activated), `entra` (Entra/SSO
> error), `notProvisioned` (user not pre-provisioned), `domainNotAllowed` (email domain not in
> the sign-in allowlist), `session` (session/state problem).

## Native account / password

| Endpoint | Purpose |
|---|---|
| `doChangePassword` | Change password. |
| `doSubmitPasswordChange` / `doCheckNativeUser` | Force-password-change submission and checks. |
| `rotogate/activate` (`doActivate`) | Native account activation link (one-time). |

## Break Glass

| Endpoint | Purpose |
|---|---|
| `emergencyAccess` | Break-glass / emergency-access page. |
| `doBreakGlassEnrollInit` / `doBreakGlassEnrollVerify` / `doBreakGlassEnrollRemove` | TOTP enrollment lifecycle. |
| `doBreakGlassActivate` / `doBreakGlassDeactivate` | Activate/deactivate emergency access. |
| `totpReminder` (page) / `doTotpReminderSkip` | TOTP enrollment reminder page and its skip action. |

## Access management

| Endpoint | Purpose |
|---|---|
| `accessManagement` | Access management page. |
| `doAddUser` / `doDeleteUser` | Manage user entries. |
| `doGrantAssignment` / `doEditAssignment` / `doRevokeAssignment` | User assignment lifecycle. |
| `doGrantGroupAssignment` / `doRevokeGroupAssignment` / `groupDetail` (page) / `doRemoveGroup` | Group assignment lifecycle. |
| `doGrantScopedAccess` / `doRevokeScopedAccess` / `doScopedAccess` / `doAllScopedAccess` | Scoped (job/folder) access. |
| `doSetNodePermission` / `doRemoveNodePermission` | Node-level permissions. |
| `doCheckAccess` / `doConfirmAccess` / `doConfirmAllAccess` / `doRemoveUserAccess` | Access checks/confirmation. |
| `doSaveRole` / `doDeleteRole` / `doSetUserRole` | Role management. |

## Access review

| Endpoint | Purpose |
|---|---|
| `accessReview` | Access review queue. |
| `doConfirmAccess` / `doConfirmAllAccess` / `doRevokeFromReview` | Keep / keep-all / revoke during review. |

## JIT access

| Endpoint | Purpose |
|---|---|
| `doRequest` | Submit a JIT request. |
| `doStatus` | JIT request status for the current user. |
| `doCancel` | Cancel a pending JIT request. |
| `jitRequests` | JIT requests queue (admin). |
| `doApproveJit` / `doDenyJit` / `doRevokeActiveJit` | Approve / deny / revoke JIT. |
| `doSuggestApprovers` | Approver suggestions. |

## Sessions

| Endpoint | Purpose |
|---|---|
| `sessions` | Active sessions view. |
| `doRevokeSession` | Revoke a session. |

## Users & cleanup

| Endpoint | Purpose |
|---|---|
| `staleUsers` | Stale users view. |
| `protectedUsers` (page) / `doSaveProtectedUsers` | Manage protected users. |
| `doMarkForDeletion` / `doUnmarkForDeletion` | Cleanup marking. |
| `doRunCleanupNow` | Trigger a cleanup run. |
| `userOverview` / `userStatus` | User overview and status pages. |

## Notifications

| Endpoint | Purpose |
|---|---|
| `notifications` (page) / `doSaveNotifications` | Notification settings page and save action. |
| `doPreviewEmail` | Email template preview. |
| `doSendTestEmail` / `doSendTestSlack` / `doSendTestTeams` | Channel test sends. |
| `notificationLog` (page) / `doClearNotificationLog` | Notification delivery log and clear action. |

## Provisioning (admin queue)

| Endpoint | Purpose |
|---|---|
| `provisioning` | Provisioning request queue. |
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
| `backup` | Backup & restore page (download via `doExportConfig`). |
| `doExportConfig` / `doImportConfig` | Export / import roles, scoped assignments, groups and settings. |
| `doPreviewImport` / `doPreviewRevert` | Preview an import or a rollback before applying. |
| `doRevertImport` | Roll back to a prior restore point. |

## Audit, security & settings

| Endpoint | Purpose |
|---|---|
| `auditLog` (page) / `doPurgeAuditLog` | View / purge audit log. |
| `security` | Security overview page. |
| `doClearBruteForce` | Reset brute-force counters. |
| `settings` (page) / `doSaveSettings` | Plugin settings (incl. branding). |
| `doUploadLoginLogo` / `doRemoveLoginLogo` | Manage the login logo. |
| `doOverviewMetrics` | JSON KPI metrics for dashboard polling (admin-gated). |
| `about` | About/info page. |

> This reference lists both `do*` action handlers and the management-console page views
> (served at `rotogate-management/<name>`). Exact HTTP method (GET/POST) and required
> permission are defined on each handler; interact through the console UI unless integrating directly.

---
_Related: [Architecture](../developer/architecture.md) · [Permissions reference](permissions.md)_
