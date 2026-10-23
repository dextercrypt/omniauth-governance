# Configuration as Code (JCasC)

> Codify OmniAuth's configuration declaratively so environments are reproducible and
> reviewable.

OmniAuth's descriptors expose `@Symbol` names for the
[Jenkins Configuration as Code](https://plugins.jenkins.io/configuration-as-code/) plugin,
so the security realm, Entra connection, authorization strategy, and stores can be defined
in YAML.

> Install the **Configuration as Code** plugin to use these symbols.

## Symbols

| Symbol | Component | Backing class |
|---|---|---|
| `omniAuth` | Security realm | `OmniAuthSecurityRealm` |
| `entraConfig` | Microsoft Entra OAuth config | `EntraOAuthConfig` |
| `omniAuthAuthorization` | Authorization strategy | `OmniAuthAuthorizationStrategy` |
| `omniAuthAssignments` | Access assignments store | `OmniAuthAssignmentConfig` |
| `omniAuthRoles` | Role definitions | `OmniAuthRoleConfig` |
| `omniAuthJitRequests` | JIT request store | `OmniAuthJitRequestStore` |
| `omniAuthProvisioningRequests` | Provisioning request store | `ProvisioningRequestConfig` |

> `OmniAuthGlobalConfig` (global settings: brute-force lockout, notifications, branding,
> audit retention) has **no** `@Symbol` — it is a plain `GlobalConfiguration`, so it
> round-trips under its global-configuration descriptor rather than a named symbol. Its
> settings are still JCasC-definable.

## Example (illustrative)

> The exact key names under each block follow the descriptor's data-bound properties.
> Export your live configuration (below) to get the authoritative schema before committing
> a file to source control.

```yaml
jenkins:
  securityRealm:
    omniAuth:
      entraConfig:
        tenantId: "${ENTRA_TENANT_ID}"
        clientId: "${ENTRA_CLIENT_ID}"
        clientSecret: "${ENTRA_CLIENT_SECRET}"
        enableGroupSync: true
        allowedDomains: "example.com, example.org"
  authorizationStrategy:
    omniAuthAuthorization:
      # matrix-style permission entries, per the strategy's schema

unclassified:
  omniAuthAssignments:
    # access assignments
  omniAuthRoles:
    # role definitions
```

## Handling secrets

Never commit the Entra client secret or SMTP password in plaintext. Use JCasC secret
resolution:

- Environment variables: `${ENTRA_CLIENT_SECRET}`
- A secrets file / vault integration supported by JCasC.

Secrets are stored encrypted in Jenkins once applied.

## Recommended workflow

1. Configure OmniAuth once through the UI on a scratch instance.
2. **Export** the live configuration:
   **Manage Jenkins → Configuration as Code → View Configuration** (or `Download`).
3. Extract the OmniAuth sections, replace secrets with `${VARS}`, and commit to source
   control.
4. Point production at the YAML (via `CASC_JENKINS_CONFIG`) and apply.
5. Re-export after any UI change to keep the file authoritative.

## Notes

- Assignments, roles, and JIT requests are also persisted as XML in `JENKINS_HOME`; JCasC is
  the declarative front door, while those files are the runtime store — see
  [Backup & restore](backup-and-restore.md).
- Global settings on `OmniAuthGlobalConfig` round-trip under the global-configuration section,
  including `auditRetentionDays` (audit-log retention in days).
- Validate changes on a non-production instance before applying to production.

---
_Related: [Configuration reference](../reference/configuration-reference.md) · [Backup & restore](backup-and-restore.md)_
