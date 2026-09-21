# Architecture

> How Omni Auth is put together: the security realm, authorization strategy, stores,
> background workers, and login flow.

- **Group id / artifact:** `io.jenkins.plugins:omni-auth`
- **Java package:** `io.jenkins.plugins.omniauth`
- **Parent POM:** `org.jenkins-ci.plugins:plugin:4.80`
- **Target platform:** Jenkins 2.541.2+, Java 21 (Jakarta servlet API)

## Component map

### Authentication

| Class | Role |
|---|---|
| `OmniAuthSecurityRealm` | The security realm; extends `HudsonPrivateSecurityRealm` so native login is inherited. Adds Entra OAuth2 endpoints (`commenceLogin`/`finishLogin`) and the native login post handler. |
| `EntraOAuthConfig` | Describable holding `tenantId`, `clientId`, `clientSecret` (encrypted `Secret`), and `enableGroupSync`. |
| `EntraUserDetails` | Spring Security `UserDetails` for an Entra-authenticated user. |
| `EntraAuthenticationToken` | The `Authentication` token placed in the security context. |
| `EntraGroupDetails` | `GrantedAuthority` for each Azure AD group. |
| `SsoProvider` | Provider preset enum (Microsoft today) driving login-button branding. |
| `MsalTokenHelper` / `TokenHelper` | MSAL4J wrapper: build auth URL, exchange code (PKCE), parse/validate ID token (nonce). |
| `util/GraphApiHelper` | MS Graph `/me/memberOf` calls via `java.net.http.HttpClient`. |
| `util/MsalTokenHelper`, `util/TokenHelper` | Token acquisition abstraction (enables test doubles). |

### Authorization

| Class | Role |
|---|---|
| `OmniAuthAuthorizationStrategy` | Extends `ProjectMatrixAuthorizationStrategy`; the plugin's authorization strategy. |
| `OmniAuthItemACL` | The `ACL` consulted per item to make permission decisions, applying assignments, scopes, groups, and JIT grants. |
| `OmniAuthAssignment` | A single access assignment (subject, scope, role/permissions, expiry). |
| `OmniAuthGroupEntity` | Group subject representation for group grants. |
| `OmniAuthRoleConfig` | Store of role definitions. |
| `OmniAuthAssignmentConfig` | Store of access assignments. |

### Governance & lifecycle

| Class | Role |
|---|---|
| `OmniAuthManagementLink` | The **Manage Jenkins → Omni Auth** console (most `do*` admin endpoints live here). |
| `OmniAuthJitAction` / `OmniAuthJitRequest` / `OmniAuthJitRequestStore` | JIT request UI, model, and store. |
| `OmniAuthUserProperty` | Per-user data (OID/UPN, break-glass, etc.). |
| `OmniAuthForcePasswordProperty` | Marks a native user as requiring a password change. |
| `LastLoginProperty` / `LastLoginListener` / `LoginHistoryProperty` | Track last-login and history for staleness and analytics. |
| `PasswordChangeRequiredException` | Thrown in the auth path to enforce fail-closed force-password-change. |
| `OmniAuthAuditLog` | Audit record store. |
| `NotificationService` / `SmtpHelper` / `SlackHelper` / `TeamsHelper` / `NotificationLog` / `NotifyRetry` | Notification pipeline with retry and logging. |
| `BruteForceTracker` | Counts failed logins against a threshold (tracks/alerts). |
| `ActiveSessionManager` | Tracks and revokes active sessions. |

### Filters, listeners, actions

| Class | Role |
|---|---|
| `LoginContextFilter` | Request filter supporting the login/session context. |
| `LoginErrorInterceptor` | Serves the branded, theming-consistent login error page. |
| `NativeLoginCrumbExclusion` | CSRF crumb exclusion for the native login post. |
| `AdminChangeListener` | `SaveableListener` reacting to config changes. |
| `OmniAuthRootAction` (`UnprotectedRootAction`) | Pre-auth endpoints (e.g. serving the login logo). |
| `OmniAuthPageDecorator` | Injects plugin assets/behavior into pages. |

### Background workers (`AsyncPeriodicWork`)

| Worker | Responsibility |
|---|---|
| `OmniAuthExpiryWorker` | Auto-revoke expired assignments. |
| `OmniAuthJitExpiryWorker` | Expire elapsed JIT grants. |
| `AccessReviewWork` | Access-review cycles and digest. |
| `StaleWarningWork` | Warn before stale-account cleanup. |
| `StaleUserCleanupWork` | Clean up inactive accounts. |

## Persistence

State is stored under `JENKINS_HOME` via Jenkins `GlobalConfiguration` and `UserProperty`
mechanisms (see [Backup & restore](../operations/backup-and-restore.md)):

- Global config → `OmniAuthGlobalConfig`
- Assignments → `OmniAuthAssignmentConfig`
- Roles → `OmniAuthRoleConfig`
- JIT requests → `OmniAuthJitRequestStore`
- Per-user → user `config.xml` properties

## SSO login flow (detailed)

See the sequence in [Authentication → flow](../administration/authentication.md#flow). Key
implementation points:

1. `commenceLogin` generates and session-stores **state**, **nonce**, and the **PKCE**
   verifier, then redirects to Entra.
2. `finishLogin` validates state, exchanges the code with the PKCE verifier, validates the
   ID token (including nonce), reads `oid`/`upn`/`name`/`email`, optionally fetches groups,
   provisions/updates the Jenkins user, and establishes the session.
3. Identity is anchored on **OID**; the Jenkins user id is the **UPN** to enable
   pre-provisioning.

## Design decisions of note

- **Extends `HudsonPrivateSecurityRealm`** rather than reimplementing native login — full
  native compatibility for free.
- **`java.net.http.HttpClient`** for Graph (no OkHttp) to avoid a Kotlin/ASM build issue.
- **Explicit session-seed handling** across the OAuth redirect to keep the authenticated
  session from being wiped by Jenkins' session filter.
- **Fail-closed force-password-change** enforced in the authentication layer (via
  `PasswordChangeRequiredException`) so it covers form, BASIC, API-token, and CLI paths.

---
_Next: [Extension points](extension-points.md) · [Building & contributing](contributing.md)_
