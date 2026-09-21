# Access management

> Grant, scope, time-limit, and revoke what users and groups can do in Jenkins.

OmniAuth's authorization strategy (`OmniAuthAuthorizationStrategy`) builds on Jenkins'
Project-based Matrix Authorization and adds a friendlier permission model, group grants,
job/folder scoping, expiry, and conflict detection — all managed from
**Manage Jenkins → OmniAuth → Access management**.

> Requires the **OmniAuth Authorization Strategy** to be selected under
> **Manage Jenkins → Security → Authorization**.

## Concepts

| Concept | Meaning |
|---|---|
| **Assignment** | A grant of a role or permission to a subject (user or group). |
| **Subject** | A user (by UPN/email or native username) or an Azure AD group. |
| **Scope** | Where the grant applies: **global**, or a specific **job/folder**. |
| **Role** | A named bundle of permissions (see [Roles](#roles)). |
| **Expiry** | Optional date after which the assignment is auto-revoked. |

Assignments are stored by `OmniAuthAssignmentConfig`; role definitions by
`OmniAuthRoleConfig`. Permission decisions at request time are made by `OmniAuthItemACL`.

## Granting access

1. **Manage Jenkins → OmniAuth → Access management**.
2. Select an existing user/group, or add a subject.
3. Choose a **scope** — global, or pick a job/folder from the tree picker.
4. Choose a **role** and/or individual permissions.
5. (Optional) Set an **expiry date**.
6. Save the grant.

> _🖼 screenshot: grant access drawer_

### Scoped access

When you scope a grant to a job or folder:

- The permission applies to that item (and, for folders, items beneath it per Jenkins'
  inheritance rules).
- **Parent folders are automatically granted Read** so the user can navigate to the scoped
  item.

Scoped grants are the recommended default — grant the narrowest scope that lets the user do
their job.

### Group grants {#group-grants}

Assign a role/permissions to an **Azure AD group** instead of an individual. At login,
[group sync](authentication.md#group-synchronization) resolves the user's group memberships
and the ACL applies any group-scoped grants. This keeps access tied to directory
membership rather than per-user maintenance.

### Time-limited access

Set an **expiry date** on any grant. The `OmniAuthExpiryWorker` background job runs on a
schedule and automatically revokes assignments whose expiry has passed, writing the change
to the [audit log](../security/audit-logging.md). This prevents access from silently
outliving its purpose.

For on-demand elevation with approval, see [JIT access](jit-access.md).

## Roles {#roles}

Roles are named permission bundles you define once and reuse across assignments. Manage
them alongside access management; definitions are stored by `OmniAuthRoleConfig`.

Typical roles:
- **Admin** — full control (use sparingly; watch for [conflicts](#conflict-detection)).
- **Developer** — configure/build jobs within a scope.
- **Reader** — read-only visibility.

See the [Permissions reference](../reference/permissions.md) for the grouped permission
catalog OmniAuth presents.

## Conflict detection

When you grant scoped restrictions to a subject who **also** holds global Admin, the UI
warns you: a global admin already has full control, so a narrower scoped grant is
contradictory (or a sign the admin grant should be removed). Resolve conflicts by either
removing the global admin grant or acknowledging the intent.

## Reviewing and revoking

- **Revoke** any assignment from the access-management UI; the change is audited.
- Certify access in bulk on a schedule via [Access review](access-review.md).
- Inspect effective access per user/job in the user detail views.

## Related governance

| Need | Feature |
|---|---|
| Periodic re-certification of access | [Access review](access-review.md) |
| Temporary elevation with approval | [JIT access](jit-access.md) |
| Remove inactive accounts | [User lifecycle](user-lifecycle.md) |
| Track every change | [Audit logging](../security/audit-logging.md) |

---
_Next: [Access review](access-review.md) · [JIT access](jit-access.md)_
