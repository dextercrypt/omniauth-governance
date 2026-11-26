package io.jenkins.plugins.rotogate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

public final class AccessInfo {
    private final String userId;
    private final String fullName;
    private final String userType;
    private final String entraOid;
    private final String entraUpn;
    private final String lastLoginAt;
    private final List<JobAccessInfo> jobAccess;
    private final String authStrategy;
    private final boolean isAdmin;
    private final boolean canRead;
    private final boolean canBuild;
    private final boolean canCreate;
    private final boolean canConfigure;
    private final List<LoginEvent> loginHistory;
    private String groupName;
    private String groupOid;
    private String provisioningSource;
    private List<GrantDisplay> rotogateGrants;

    public AccessInfo(String userId, String fullName, String userType,
                      String entraOid, String entraUpn, String lastLoginAt,
                      List<JobAccessInfo> jobAccess, String authStrategy,
                      boolean isAdmin, boolean canRead, boolean canBuild,
                      boolean canCreate, boolean canConfigure,
                      List<LoginEvent> loginHistory) {
        this.userId        = userId;
        this.fullName      = fullName;
        this.userType      = userType;
        this.entraOid      = entraOid;
        this.entraUpn      = entraUpn;
        this.lastLoginAt   = lastLoginAt;
        this.jobAccess     = jobAccess != null ? jobAccess : Collections.emptyList();
        this.authStrategy  = authStrategy;
        this.isAdmin       = isAdmin;
        this.canRead       = canRead;
        this.canBuild      = canBuild;
        this.canCreate     = canCreate;
        this.canConfigure  = canConfigure;
        this.loginHistory  = loginHistory != null ? loginHistory : Collections.emptyList();
    }

    public String getUserId()                  { return userId; }
    public String getFullName()                { return fullName; }
    public String getUserType()                { return userType; }
    public String getEntraOid()                { return entraOid; }
    public String getEntraUpn()                { return entraUpn; }
    public String getLastLoginAt()             { return lastLoginAt; }
    public List<JobAccessInfo> getJobAccess()  { return jobAccess; }
    public String getAuthStrategy()            { return authStrategy; }
    public boolean isAdmin()                   { return isAdmin; }
    public boolean isCanRead()                 { return canRead; }
    public boolean isCanBuild()                { return canBuild; }
    public boolean isCanCreate()               { return canCreate; }
    public boolean isCanConfigure()            { return canConfigure; }
    public List<LoginEvent> getLoginHistory()  { return loginHistory; }
    public boolean isEntraUser()               { return "Entra".equals(userType); }
    public String getGroupName()               { return groupName; }
    public void setGroupName(String s)         { this.groupName = s; }
    public String getGroupOid()                { return groupOid; }
    public void setGroupOid(String s)          { this.groupOid = s; }
    public String getProvisioningSource()      { return provisioningSource != null ? provisioningSource : "NATIVE"; }
    public void setProvisioningSource(String s){ this.provisioningSource = s; }
    public boolean isViaGroup()                { return "VIA_ENTRA_GROUP".equals(provisioningSource); }
    public List<GrantDisplay> getRotogateGrants() { return rotogateGrants != null ? rotogateGrants : Collections.emptyList(); }
    public void setRotogateGrants(List<GrantDisplay> grants) { this.rotogateGrants = grants; }

    // Time-limited account expiry (provisioning portal duration; null = permanent)
    private String  accountExpiresAt;
    private boolean accountDisabledByExpiry;
    public void setAccountExpiresAt(String s)         { this.accountExpiresAt = s; }
    public void setAccountDisabledByExpiry(boolean b) { this.accountDisabledByExpiry = b; }
    public String  getAccountExpiresAt()              { return accountExpiresAt; }
    public String  getFormattedExpiresAt()            { return RotogateManagementLink.formatDate(accountExpiresAt); }
    public boolean hasExpiry()                        { return accountExpiresAt != null && !accountExpiresAt.isBlank(); }
    public boolean isAccountDisabledByExpiry()        { return accountDisabledByExpiry; }
    public boolean isAccountExpired() {
        if (!hasExpiry()) return false;
        try { return Instant.parse(accountExpiresAt).isBefore(Instant.now()); }
        catch (Exception e) { return false; }
    }
    public long getExpiresInDays() {
        if (!hasExpiry()) return 0;
        try {
            long hours = java.time.Duration.between(Instant.now(), Instant.parse(accountExpiresAt)).toHours();
            return hours >= 0 ? (hours + 23) / 24 : hours / 24;
        } catch (Exception e) { return 0; }
    }
    public boolean isPerJobSupported() {
        return authStrategy != null &&
               (authStrategy.toLowerCase().contains("projectmatrix") ||
                authStrategy.toLowerCase().contains("rotogate"));
    }
}
