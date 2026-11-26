package io.jenkins.plugins.rotogate;

import java.time.Instant;

public final class UserStatusInfo {
    private final String userId;
    private final String fullName;
    private final String userType;
    private final String lastLoginAt;
    private final String lastJobName;
    private final String lastJobTriggeredAt;
    private final String status;

    // Extended fields
    private String provisioningSource; // NATIVE, INDIVIDUAL, VIA_ENTRA_GROUP
    private String entraUpn;
    private String groupName;          // effective group name if VIA_ENTRA_GROUP
    private String groupOid;           // OID of the group
    private boolean activeGroupUser;
    private boolean staleWarning;

    public UserStatusInfo(String userId, String fullName, String userType,
                          String lastLoginAt, String lastJobName,
                          String lastJobTriggeredAt, String status) {
        this.userId            = userId;
        this.fullName          = fullName;
        this.userType          = userType;
        this.lastLoginAt       = lastLoginAt;
        this.lastJobName       = lastJobName;
        this.lastJobTriggeredAt = lastJobTriggeredAt;
        this.status            = status;
    }

    public String getUserId()                    { return userId; }
    public String getFullName()                  { return fullName; }
    public String getUserType()                  { return userType; }
    public String getLastLoginAt()               { return lastLoginAt; }
    public String getLastJobName()               { return lastJobName; }
    public String getLastJobTriggeredAt()        { return lastJobTriggeredAt; }
    public String getStatus()                    { return status; }
    public boolean isNeverLoggedIn()             { return lastLoginAt == null; }
    public boolean isNeverTriggered()            { return lastJobName == null; }
    public String getRelativeLastLoginAt()        { return RotogateManagementLink.relativeTime(lastLoginAt); }
    public String getRelativeLastJobTriggeredAt() { return RotogateManagementLink.relativeTime(lastJobTriggeredAt); }
    public String getFormattedLastLoginAt()       { return RotogateManagementLink.formatDate(lastLoginAt); }
    public String getFormattedLastJobTriggeredAt(){ return RotogateManagementLink.formatDate(lastJobTriggeredAt); }

    public String getProvisioningSource()        { return provisioningSource != null ? provisioningSource : "NATIVE"; }
    public void setProvisioningSource(String s)  { this.provisioningSource = s; }
    public String getEntraUpn()                  { return entraUpn; }
    public void setEntraUpn(String s)            { this.entraUpn = s; }
    public String getGroupName()                 { return groupName; }
    public void setGroupName(String s)           { this.groupName = s; }
    public String getGroupOid()                  { return groupOid; }
    public void setGroupOid(String s)            { this.groupOid = s; }
    public boolean isActiveGroupUser()           { return activeGroupUser; }
    public void setActiveGroupUser(boolean b)    { this.activeGroupUser = b; }
    public boolean isStaleWarning()              { return staleWarning; }
    public void setStaleWarning(boolean b)       { this.staleWarning = b; }

    private boolean pendingDeletion;
    public boolean isPendingDeletion()           { return pendingDeletion; }
    public void setPendingDeletion(boolean b)    { this.pendingDeletion = b; }

    private LoginEvent latestEvent;
    public void setLatestEvent(LoginEvent e) { this.latestEvent = e; }
    public LoginEvent getLatestEvent()        { return latestEvent; }

    // Time-limited account expiry (from RotogateAccountExpiryProperty; null = permanent)
    private String  accountExpiresAt;
    private boolean accountDisabledByExpiry;
    public void setAccountExpiresAt(String s)        { this.accountExpiresAt = s; }
    public void setAccountDisabledByExpiry(boolean b) { this.accountDisabledByExpiry = b; }
    public String  getAccountExpiresAt()             { return accountExpiresAt; }
    public String  getFormattedExpiresAt()           { return RotogateManagementLink.formatDate(accountExpiresAt); }
    public boolean hasExpiry()                       { return accountExpiresAt != null && !accountExpiresAt.isBlank(); }
    public boolean isAccountDisabledByExpiry()       { return accountDisabledByExpiry; }
    public boolean isAccountExpired() {
        if (!hasExpiry()) return false;
        try { return Instant.parse(accountExpiresAt).isBefore(Instant.now()); }
        catch (Exception e) { return false; }
    }
    /** Days until expiry, rounded UP so "5 days out" reads as 5 (negative if past); 0 when no expiry. */
    public long getExpiresInDays() {
        if (!hasExpiry()) return 0;
        try {
            long hours = java.time.Duration.between(Instant.now(), Instant.parse(accountExpiresAt)).toHours();
            return hours >= 0 ? (hours + 23) / 24 : hours / 24; // ceil forward, floor when past
        } catch (Exception e) { return 0; }
    }
}
