# Requirements

> Supported platforms, dependencies, and prerequisites before installing OmniAuth.

## Platform

| Component | Requirement |
|---|---|
| **Jenkins** | 2.541.2 or newer (Jakarta servlet API line) |
| **Java** | 21 (tested on Amazon Corretto 21) |
| **Servlet API** | Jakarta Servlet 6.x (provided by Jenkins core) |

> OmniAuth targets the Jenkins release line that migrated from `javax.*` to `jakarta.*`.
> It will not load on Jenkins versions predating that migration.

## Required Jenkins plugins

These are declared as dependencies and must be present (installed automatically when you
deploy the `.hpi` through the Update Center, or bundled when side-loaded):

| Plugin | Why it's needed |
|---|---|
| **Matrix Authorization Strategy** | Base for `OmniAuthAuthorizationStrategy` |
| **CloudBees Folder** | Folder-scoped permission grants |
| **Jakarta Mail API** | Email notifications (correct classloader at runtime) |
| **Ionicons API** | Icon assets (version pinned for CloudBees Folder compatibility) |

## Third-party libraries (bundled)

The plugin bundles these libraries in the `.hpi`; no separate installation is required:

| Library | Purpose |
|---|---|
| **MSAL4J** | Microsoft Entra OAuth2/OIDC token acquisition |
| **Nimbus JOSE+JWT** | ID token parsing and validation |
| **Jackson Databind** | JSON parsing for MS Graph responses |
| **AeroGear OTP** | TOTP generation/validation for Break Glass |
| **ZXing (core + javase)** | QR code generation for Break Glass enrollment |

## For single sign-on

SSO uses an OAuth2 / OIDC provider through a pluggable framework. **Microsoft Entra (Azure AD)
is the provider integrated today**; the requirements below are for the Entra integration.

- A Microsoft Entra tenant with permission to create an **OAuth2 / OIDC App Registration**
  (not a SAML Enterprise Application).
- Ability to configure a **redirect URI** pointing at your Jenkins.
- For group sync: the `GroupMember.Read.All` Graph permission (admin-consented).

See [Microsoft Entra setup](../administration/entra-setup.md) for the full procedure.

## For notifications (optional)

| Channel | Requirement |
|---|---|
| **Email** | Reachable SMTP host/port; credentials if authenticated; TLS as needed |
| **Slack** | Incoming webhook URL |
| **Microsoft Teams** | Incoming webhook URL |

## Network

- Outbound HTTPS from the Jenkins controller to `login.microsoftonline.com` and
  `graph.microsoft.com` (for SSO and group sync).
- Outbound to your SMTP relay and/or Slack/Teams webhook endpoints (if notifications used).
- Inbound access to the Jenkins redirect URI from users' browsers.

---
_Next: [Installation](installation.md)_
