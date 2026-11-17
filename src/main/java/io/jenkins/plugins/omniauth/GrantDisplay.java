package io.jenkins.plugins.omniauth;

import hudson.model.Job;

public final class GrantDisplay {
    private final String principalType;
    private final String principalId;
    private final String principalName;
    private final String roleId;
    private final String roleName;
    private final String scope;
    private final String scopeType;
    private final String expiresAt;
    private final boolean expired;

    public GrantDisplay(String principalType, String principalId, String principalName,
                        String roleId, String roleName, String scope, String scopeType,
                        String expiresAt, boolean expired) {
        this.principalType = principalType;
        this.principalId   = principalId;
        this.principalName = principalName;
        this.roleId        = roleId;
        this.roleName      = roleName;
        this.scope         = scope;
        this.scopeType     = scopeType;
        this.expiresAt     = expiresAt;
        this.expired       = expired;
    }

    public String getPrincipalType() { return principalType; }
    public String getPrincipalId()   { return principalId; }
    public String getPrincipalName() { return principalName; }
    public String getRoleId()        { return roleId; }
    public String getRoleName()      { return roleName; }
    public String getScope()         { return scope; }
    public String getScopeType()     { return scopeType; }
    public String getExpiresAt()     { return expiresAt; }
    public boolean isExpired()       { return expired; }
    public boolean isGlobal()        { return scope == null || scope.isEmpty(); }
    public String getScopeDisplay()  {
        if (scope == null || scope.isEmpty()) return "Global";
        return scope;
    }
    public String getScopeTypeDisplay() {
        if (scope == null || scope.isEmpty()) return "Global";
        if ("FOLDER".equals(scopeType)) return "Folder";
        if ("JOB".equals(scopeType)) return "Job";
        return scopeType;
    }
}
