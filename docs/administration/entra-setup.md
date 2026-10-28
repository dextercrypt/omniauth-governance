# Microsoft Entra setup

> Register and configure the Microsoft Entra (Azure AD) OAuth2 / OIDC application that
> backs SSO, then connect it to OmniAuth.

> **Microsoft Entra is the SSO provider integrated today.** OmniAuth's SSO is built on a
> pluggable OAuth2/OIDC provider framework, so more providers are planned; this page covers
> the Entra integration specifically.

> Use an **OAuth2 / OIDC App Registration** — **not** a SAML Enterprise Application.

## 1. Create the app registration

In the [Microsoft Entra admin center](https://entra.microsoft.com/) → **Applications → App
registrations → New registration**:

| Field | Recommended value |
|---|---|
| **Name** | `Jenkins — OmniAuth` (any descriptive name) |
| **Supported account types** | *Accounts in this organizational directory only* (single tenant) |
| **Redirect URI** | Platform **Web**, value below |

**Redirect URI:**

```
https://<jenkins-host>/<prefix>/omniauth/finishLogin
```

Examples:
- Root context: `https://jenkins.example.com/omniauth/finishLogin`
- With prefix: `https://ci.example.com/jenkins/omniauth/finishLogin`

> The redirect URI must match **exactly** (scheme, host, port, path). Register additional
> URIs if you have multiple hostnames (e.g. internal vs. external).

## 2. Collect the identifiers

From the app registration **Overview**:

- **Application (client) ID** → the plugin's **Client ID**
- **Directory (tenant) ID** → the plugin's **Tenant ID**

## 3. Create a client secret

**Certificates & secrets → Client secrets → New client secret**:

1. Add a description and expiry.
2. Copy the **Value** immediately (it is shown only once) → the plugin's **Client Secret**.

> Set a calendar reminder before the secret expires; SSO breaks when it lapses. The secret
> is stored encrypted in Jenkins (`Secret`).

## 4. Configure API permissions {#api-permissions}

**API permissions → Add a permission → Microsoft Graph → Delegated permissions**:

| Permission | Needed for |
|---|---|
| `openid` | OIDC sign-in |
| `profile` | Name/profile claims |
| `email` | Email claim |
| `User.Read` | Basic profile read |
| `GroupMember.Read.All` | **Group sync only** |

Click **Grant admin consent** for the tenant (required for `GroupMember.Read.All`).

## 5. Token configuration

Ensure the app issues **ID tokens** (and access tokens for Graph). Under
**Authentication**, enable **ID tokens** for the implicit/hybrid flow checkbox set if your
tenant requires it; the plugin uses the authorization-code flow with PKCE.

## 6. Connect OmniAuth

In Jenkins: **Manage Jenkins → Security → Security Realm → OmniAuth**, then turn on **"Connect a
single sign-on provider (SSO)"** to reveal the provider settings (it's off by default — OmniAuth
is native-first). Fill the Entra configuration:

| Field | Value |
|---|---|
| **Tenant ID** | Directory (tenant) ID |
| **Client ID** | Application (client) ID |
| **Client Secret** | Secret value from step 3 |
| **Enable Group Sync** | On, if you completed the `GroupMember.Read.All` grant |
| **Allowed email domains** | Optional comma/space-separated allowlist of email domains; empty = allow any |

### Test Connection

Before the first sign-in, click **Test Connection** to validate the tenant ID, client ID and
client secret without saving. It performs a **real** MSAL client-credentials token grant for
the Graph `.default` scope against your tenant and reports a green success or a red error
inline. The button is admin-only and POST-only. Note it exercises **app-only** (client-credentials)
authentication, whereas interactive sign-in uses **delegated** permissions — so a passing test
confirms the credentials and app registration, and sign-in additionally depends on the delegated
permissions and consent from [step 4](#api-permissions).

### Allowed email domains

**Allowed email domains** is an optional allowlist (`allowedDomains` on the Entra config,
`@DataBoundSetter`, round-trips through JCasC). Leave it empty to allow any domain. When set, it
is enforced at `finishLogin` **after** the pre-provisioning check: a Microsoft-verified identity
whose email domain is not on the list is rejected (redirected to `loginError?error=domainNotAllowed`,
audit event `login_denied_domain`). This is **distinct** from the
[provisioning portal](provisioning-portal.md)'s own domain allowlist, which gates who may
*request* access rather than who may *sign in*.

**Save**, then test SSO in a private browser window (see
[Quick start → test both login paths](../getting-started/quick-start.md)).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `AADSTS50011` redirect mismatch | Redirect URI in Entra ≠ `.../omniauth/finishLogin` (check prefix/host/scheme) |
| Login loops back to the login page | Session/seed issue behind a reverse proxy — verify external URL and cookies |
| Groups not appearing | `GroupMember.Read.All` not admin-consented, or Group Sync disabled |
| `AADSTS7000215` invalid secret | Client secret expired or mistyped |

More: [Troubleshooting](../operations/troubleshooting.md).

---
_Related: [Authentication](authentication.md) · [Security model](../security/security-model.md)_
