# Omni Auth Plugin

A Jenkins security plugin that lets **native username/password login** and
**Microsoft Entra (Azure AD) single sign-on** work side by side on one login page —
and layers a full identity-and-access governance suite on top of it: access
management, just-in-time elevation, access reviews, session control, emergency
break-glass access, notifications, and a branded login experience.

- **Artifact:** `io.jenkins.plugins:omni-auth` (packaging `hpi`)
- **Jenkins:** 2.541.2+ · **Java:** 21 · **Parent POM:** `org.jenkins-ci.plugins:plugin:4.80`
- **License:** MIT

---

## Introduction

Omni Auth replaces the standard Jenkins security realm with a single
`OmniAuthSecurityRealm` that extends Jenkins' built-in
`HudsonPrivateSecurityRealm`. Native accounts keep working exactly as before
(bcrypt password hashes, API tokens, CLI), while an additional
**"Sign in with Microsoft"** path authenticates users against Microsoft Entra via
the OAuth2 / OIDC authorization-code flow (MSAL4J), hardened with **PKCE** and
**ID-token nonce** validation.

Around authentication, the plugin ships a matching authorization strategy
(`OmniAuthAuthorizationStrategy`, built on Matrix Authorization) and a dedicated
management console under **Manage Jenkins → Omni Auth**.

### Feature overview

**Authentication**
- Dual login: native username/password **and** Microsoft Entra SSO on the same page.
- OAuth2 authorization-code flow via MSAL4J, with PKCE and ID-token nonce validation.
- Users provisioned by UPN (email) as the Jenkins user id, anchored to the stable
  Azure Object ID (OID) so identity survives email changes.
- Optional Azure AD **group sync** via MS Graph (`/me/memberOf`), surfaced as
  Spring Security granted authorities.
- Pluggable **SSO provider preset** (`SsoProvider`; Microsoft today) driving the
  login button label/branding — extensible to further providers.

**Access management**
- `OmniAuthAuthorizationStrategy` extends `ProjectMatrixAuthorizationStrategy` with a
  custom `OmniAuthItemACL` and a friendlier, grouped permission model.
- Per-user and **per-group** role/permission assignments, global or scoped to a
  job/folder via a tree picker (parent folders auto-granted Read).
- **Time-limited grants** with expiry dates, auto-revoked by a background worker.
- **Access review / certification**: a review queue with keep/revoke, bulk actions,
  a configurable staleness threshold, and a weekly email digest.
- **Conflict detection** warning when a global admin is also given scoped restrictions.
- Full **audit log** of access changes with purge controls.

**Just-in-Time (JIT) access / PIM**
- Users request time-bound elevated access; admins approve/deny; access auto-expires
  and can be revoked mid-flight. Backed by a request store and expiry worker.

**Sessions & account safety**
- Active-session view and **force logout / session revocation**.
- **Force password change** (fail-closed): a flagged native user gets no session until
  a new password is set — closes form, BASIC, API-token and CLI paths.
- **Break Glass** emergency access with TOTP second factor (QR enrollment) for
  recovering admin access.
- **Brute-force tracking** with a configurable threshold and alerting (tracks/alerts;
  hard lockout enforcement is intentionally not enabled).
- **Stale user cleanup** and pre-cleanup **warning** workers (dry-run supported).

**Notifications**
- SMTP email, Slack, and Microsoft Teams channels, each with a per-event subscription
  list, retry-on-failure, test buttons, and an email-template preview.

**Login page branding**
- Configurable heading, browser tab title, announcement banner, footer text, uploaded
  logo (position/size), animated background, and a selectable "Butler Style" with
  daily/random rotation (and a festive December lock).

---

## Requirements

- Jenkins **2.541.2** or newer (the plugin uses the Jakarta servlet API introduced in
  this line).
- **Java 21** (Amazon Corretto 21 is the tested runtime).
- For SSO: a Microsoft Entra **OAuth2 / OIDC App Registration** (not a SAML Enterprise
  Application).

Bundled/relied-upon plugins include Matrix Authorization Strategy, CloudBees Folder
(folder-scoped grants), and Jakarta Mail API (notifications).

---

## Getting started

### 1. Install

Build the plugin and drop the resulting `target/omni-auth.hpi` into your Jenkins via
**Manage Jenkins → Plugins → Advanced → Deploy Plugin**, then restart.

### 2. Enable the security realm

**Manage Jenkins → Security → Security Realm → "Omni Auth"**.

For authorization, select **"Omni Auth Authorization Strategy"** to use the plugin's
access-management features (it is a superset of Project-based Matrix Authorization).

### 3. Configure Microsoft Entra (optional, for SSO)

Register an **OAuth2 / OIDC** app in Entra with:

| Setting | Value |
|---|---|
| Redirect URI | `https://<jenkins-host>/<prefix>/securityRealm/finishLogin` |
| Account types | Single tenant (recommended) |
| Tokens | ID token + access token |
| API permissions | `openid`, `profile`, `email`, `User.Read` (add `GroupMember.Read.All` for group sync) |

Then fill in the plugin's Entra config:

- **Tenant ID** — Entra → Overview
- **Client ID** — App Registration → Overview
- **Client Secret** — App Registration → Certificates & Secrets (stored encrypted)
- **Enable Group Sync** — toggle to pull group memberships from MS Graph

### 4. Manage users and access

Everything else lives under **Manage Jenkins → Omni Auth**: access management, JIT
requests, access review, sessions, protected users, notifications, audit log, stale
users, break-glass, and login-page settings.

### Login endpoints (reference)

- Custom login page: `/<prefix>/securityRealm/login`
- Native form post: `/<prefix>/omniauth/nativeLogin`
- SSO start / callback: `/<prefix>/securityRealm/commenceLogin` → `/finishLogin`

---

## Configuration as Code (JCasC)

Core descriptors expose `@Symbol` names for JCasC:

| Symbol | Component |
|---|---|
| `omniAuth` | Security realm |
| `entraConfig` | Microsoft Entra OAuth config |
| `omniAuthAuthorization` | Authorization strategy |
| `omniAuthAssignments` | Access assignments store |
| `omniAuthRoles` | Role definitions |
| `omniAuthJitRequests` | JIT request store |

---

## Building from source

```bash
export JAVA_HOME=/path/to/jdk-21
mvn package -DskipTests   # produces target/omni-auth.hpi
```

**Build notes** (already configured in `pom.xml`): the Jenkins license-maven-plugin
and Spotless checks are skipped because their Groovy/ASM stack cannot parse some
newer class files on Maven 3.9.13's classpath.

### Running for development

`mvn hpi:run` is not used here (its embedded Jetty does not register the Jenkins
web-fragment). Instead run the standalone WAR against a local `JENKINS_HOME`:

```bash
JENKINS_HOME=./work \
  java -jar jenkins-war-2.541.2.war --httpPort=9090 --prefix=/jenkins
# → http://localhost:9090/jenkins/
```

---

## Issues

Report issues and enhancements in the
[Jenkins issue tracker](https://issues.jenkins.io/).

## Contributing

Refer to the Jenkins
[contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

## License

Licensed under MIT, see [LICENSE](LICENSE.md).
