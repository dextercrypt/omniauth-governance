# Glossary

> Terminology used across OmniAuth documentation.

| Term | Definition |
|---|---|
| **Access assignment** | A grant of a role or permission to a user or group, optionally scoped to a job/folder and optionally time-limited. Stored by `OmniAuthAssignmentConfig`. |
| **Access review** | A periodic certification exercise where admins confirm (keep) or revoke existing access. See [Access review](administration/access-review.md). |
| **Audit log** | Immutable record of access and security-relevant changes. See [Audit logging](security/audit-logging.md). |
| **Break Glass** | Emergency, MFA-protected access path used when normal admin/SSO login is unavailable. |
| **Client ID / Secret** | Credentials of the Entra OAuth2 app registration used by the plugin. |
| **Entra (Microsoft Entra)** | Microsoft's cloud identity platform, formerly Azure Active Directory (Azure AD). |
| **Grant** | See *access assignment*. |
| **Group sync** | Importing a user's Azure AD group memberships via MS Graph and exposing them as authorities. |
| **JCasC** | Jenkins Configuration as Code — declarative YAML configuration. See [Configuration as Code](operations/configuration-as-code.md). |
| **JIT (Just-in-Time) access** | Time-bounded elevated access granted on request and auto-expired. Also known as PIM. |
| **MSAL4J** | Microsoft Authentication Library for Java, used for the OAuth2 code flow. |
| **Native login** | Jenkins' built-in username/password authentication (bcrypt), inherited from `HudsonPrivateSecurityRealm`. |
| **Nonce** | A single-use value bound into the OIDC ID token to prevent replay. |
| **OID (Object ID)** | The stable Azure identifier for a user; used as the identity anchor even if the email/UPN changes. |
| **PIM** | Privileged Identity Management — Microsoft's term for JIT elevation. |
| **PKCE** | Proof Key for Code Exchange — an OAuth2 hardening for the authorization-code flow. |
| **Pre-provisioning** | Creating access assignments for a user by email **before** their first login, so permissions apply immediately. |
| **Protected user** | A user exempted from automated actions such as stale-account cleanup. |
| **Scope** | The target of an assignment: global, or a specific job/folder (parents auto-granted Read). |
| **Security realm** | The Jenkins component that authenticates users. Here, `OmniAuthSecurityRealm`. |
| **Authorization strategy** | The Jenkins component that decides permissions. Here, `OmniAuthAuthorizationStrategy`. |
| **SSO** | Single Sign-On, provided here via Microsoft Entra OAuth2/OIDC. |
| **Stale user** | An account inactive beyond a configured threshold, eligible for warning/cleanup. |
| **TOTP** | Time-based One-Time Password, the second factor used by Break Glass. |
| **UPN** | User Principal Name — the user's Entra sign-in name (typically their email). Used as the Jenkins user id. |

---
_See also: [Configuration reference](reference/configuration-reference.md) · [Architecture](developer/architecture.md)_
