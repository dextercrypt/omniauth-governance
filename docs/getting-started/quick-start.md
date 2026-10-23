# Quick start

> Get OmniAuth running with native login and OAuth/OIDC SSO (Microsoft Entra) in about 15 minutes.

This guide assumes a non-production Jenkins you can safely reconfigure. For production,
read [Installation](installation.md) and [Security model](../security/security-model.md) first.

## 1. Install the plugin

Deploy `omni-auth.hpi` (**Manage Jenkins → Plugins → Advanced → Deploy Plugin**) and
restart. See [Installation](installation.md) for alternatives.

## 2. Enable the security realm

1. **Manage Jenkins → Security**.
2. **Security Realm → OmniAuth**.
3. **Authorization → OmniAuth Authorization Strategy**.
4. **Save**.

> Keep your current admin session open in this tab. Verify login in a **separate private
> window** before closing it.

## 3. (Optional) Wire up OAuth/OIDC SSO

SSO today uses **Microsoft Entra (Azure AD)** (other providers are listed as *planned*). You'll
need a **Tenant ID**, **Client ID**, and **Client Secret** from an Entra OAuth2/OIDC app
registration whose redirect URI is:

```
https://<jenkins-host>/<prefix>/omniauth/finishLogin
```

Full walkthrough: [Microsoft Entra setup](../administration/entra-setup.md).

Then, in the security realm configuration:

1. Enter **Tenant ID**, **Client ID**, **Client Secret**.
2. (Optional) Enable **Group Sync** to import Azure AD groups.
3. **Save**.

## 4. Test both login paths

Open `https://<jenkins-host>/<prefix>/securityRealm/login` in a private window:

- **Native:** sign in with a local username/password.
- **SSO:** click **Sign in with Microsoft** and complete the Entra flow.

## 5. Grant some access

1. **Manage Jenkins → OmniAuth**.
2. Open a user (or add one), then grant a role/permission — global or scoped to a
   job/folder, optionally with an expiry date.

See [Access management](../administration/access-management.md).

## 6. Set up a safety net

Before you rely on SSO, configure at least one of:

- A working **native admin** account.
- [Break Glass](../administration/break-glass.md) emergency access (TOTP-protected).

## 7. (Recommended) Turn on notifications

**Manage Jenkins → OmniAuth → Notifications** — enable email, Slack, or Teams and send a
test message. See [Notifications](../administration/notifications.md).

---

## Where to go next

- [Authentication](../administration/authentication.md)
- [Access management](../administration/access-management.md)
- [Configuration as Code](../operations/configuration-as-code.md) to codify all of the above
