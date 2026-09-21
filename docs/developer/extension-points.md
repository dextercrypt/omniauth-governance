# Extension points

> The Jenkins extension points OmniAuth implements, and where to extend it.

OmniAuth is built entirely on standard Jenkins extension points, which makes its behavior
predictable and its surfaces familiar to plugin developers.

## Implemented extension points

| Extension point | Implementation(s) | Purpose |
|---|---|---|
| `SecurityRealm` (`HudsonPrivateSecurityRealm`) | `OmniAuthSecurityRealm` | Authentication (native + Entra). |
| `AuthorizationStrategy` (`ProjectMatrixAuthorizationStrategy`) | `OmniAuthAuthorizationStrategy` | Authorization decisions. |
| `ACL` | `OmniAuthItemACL` | Per-item permission checks. |
| `GlobalConfiguration` | `OmniAuthGlobalConfig`, `OmniAuthAssignmentConfig`, `OmniAuthRoleConfig`, `OmniAuthJitRequestStore` | Persisted configuration/state. |
| `ManagementLink` | `OmniAuthManagementLink` | The admin console page. |
| `RootAction` / `UnprotectedRootAction` | `OmniAuthJitAction`, `OmniAuthRootAction` | User-facing and pre-auth endpoints. |
| `UserProperty` | `OmniAuthUserProperty`, `OmniAuthForcePasswordProperty`, `LastLoginProperty`, `LoginHistoryProperty` | Per-user data. |
| `SecurityListener` | `LastLoginListener` | React to authentication events. |
| `SaveableListener` | `AdminChangeListener` | React to configuration saves. |
| `AsyncPeriodicWork` | `OmniAuthExpiryWorker`, `OmniAuthJitExpiryWorker`, `AccessReviewWork`, `StaleWarningWork`, `StaleUserCleanupWork` | Scheduled background jobs. |
| `CrumbExclusion` | `NativeLoginCrumbExclusion` | CSRF handling for native login post. |
| `PageDecorator` | `OmniAuthPageDecorator` | Inject assets/behavior into pages. |
| `Descriptor` / `AbstractDescribableImpl` | `EntraOAuthConfig` | Configurable sub-object. |

## JCasC symbols

The descriptors carry `@Symbol` annotations for Configuration as Code — see
[Configuration as Code](../operations/configuration-as-code.md):
`omniAuth`, `entraConfig`, `omniAuthAuthorization`, `omniAuthAssignments`, `omniAuthRoles`,
`omniAuthJitRequests`.

## Where to extend

| Goal | Approach |
|---|---|
| **Add an SSO provider** | Extend the `SsoProvider` preset and the token-helper abstraction (`TokenHelper`/`MsalTokenHelper`); the login page is already provider-parameterized. |
| **New notification channel** | Add a helper alongside `SmtpHelper`/`SlackHelper`/`TeamsHelper` and wire it into `NotificationService`. |
| **New scheduled job** | Add an `AsyncPeriodicWork` and read its settings from `OmniAuthGlobalConfig`. |
| **New admin action** | Add a `do*` method on `OmniAuthManagementLink` with a matching Jelly view. |
| **New governance data** | Add a `GlobalConfiguration` store and (if user-scoped) a `UserProperty`. |

## Testing hooks

The token acquisition path is abstracted behind `TokenHelper`, allowing a fake MSAL helper
in `JenkinsRule`-based integration tests (so SSO logic can be exercised without a live Entra
tenant). Keep new provider integrations behind the same abstraction to stay testable.

---
_Next: [Building & contributing](contributing.md)_
