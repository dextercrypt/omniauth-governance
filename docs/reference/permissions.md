# Permissions reference

> How Omni Auth presents and evaluates permissions.

Omni Auth's authorization strategy (`OmniAuthAuthorizationStrategy`) is a superset of
Jenkins' Project-based Matrix Authorization. It uses the **same underlying Jenkins
permissions** (Overall, Job, Run, View, Agent/Computer, Credentials, SCM, etc.), but
presents them in a **grouped, labeled** form and evaluates them through `OmniAuthItemACL`
with support for scoping, groups, expiry, and JIT grants.

## Permission model

| Aspect | Behavior |
|---|---|
| **Source permissions** | Standard Jenkins `Permission` objects contributed by core and plugins. |
| **Grouping** | Permissions are grouped/labeled for a clearer admin experience. |
| **Subjects** | Users (by UPN/email or native username) and Azure AD groups. |
| **Scope** | Global, or a specific job/folder (parent folders auto-granted Read). |
| **Roles** | Named bundles of permissions defined in `OmniAuthRoleConfig`. |
| **Expiry** | Optional per-assignment; auto-enforced by the expiry worker. |
| **JIT** | Time-bounded grants layered on top at evaluation time. |

## Common permission groups (Jenkins core)

The exact catalog depends on installed plugins. The commonly-used groups are:

| Group | Representative permissions |
|---|---|
| **Overall** | Administer, Read, RunScripts, ConfigureUpdateCenter |
| **Job** | Create, Configure, Read, Build, Delete, Cancel, Workspace, Move |
| **Run** | Delete, Update, Replay |
| **View** | Create, Configure, Read, Delete |
| **Agent (Computer)** | Create, Configure, Connect, Disconnect, Delete, Build |
| **Credentials** | Create, Update, View, Delete, ManageDomains |
| **SCM** | Tag |

> **Overall/Administer** is full control — grant it to as few subjects as possible and watch
> for [conflicts](../administration/access-management.md#conflict-detection).

## Evaluation order (conceptual)

For a given user, item, and permission, `OmniAuthItemACL` grants access if **any** applicable
source says yes:

1. Global grants for the user.
2. Global grants for any of the user's groups.
3. Scoped grants for the user on this item (or an ancestor folder, per inheritance).
4. Scoped grants for the user's groups on this item.
5. Any active JIT grant covering this permission/scope.

Assignments whose expiry has passed are not considered (and are cleaned up by the worker).

## Recommendations

- Grant the **narrowest scope** and the **smallest role** that does the job.
- Prefer **group grants** for team-wide access to reduce per-user churn.
- Prefer **time-limited** or **JIT** access over standing Administer.
- Re-certify standing access with [access review](../administration/access-review.md).

---
_Related: [Access management](../administration/access-management.md) · [Security model](../security/security-model.md)_
