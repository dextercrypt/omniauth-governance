package io.jenkins.plugins.omniauth;


public final class ExpiringGrant {
    public final String userId;
    public final String roleId;
    public final String scope;
    public final String expiresAt;
    public final long daysLeft;
    public ExpiringGrant(String userId, String roleId, String scope, String expiresAt, long daysLeft) {
        this.userId = userId; this.roleId = roleId; this.scope = scope;
        this.expiresAt = expiresAt; this.daysLeft = daysLeft;
    }
    public String getUserId()   { return userId; }
    public String getRoleId()   { return roleId; }
    public String getScope()    { return scope == null || scope.isEmpty() ? "Global" : scope; }
    public String getExpiresAt(){ return expiresAt; }
    public long getDaysLeft()   { return daysLeft; }
}
