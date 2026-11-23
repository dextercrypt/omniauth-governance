package io.jenkins.plugins.rotogate;


public final class UserInfo {
    private final String userId;
    private final String fullName;
    private final String userType;
    private final String lastLoginAt;
    private final String entraOid;
    private String pendingReason; // null = not pending; "Group access revoked" or "Manually marked"

    public UserInfo(String userId, String fullName, String userType,
                    String lastLoginAt, String entraOid) {
        this.userId      = userId;
        this.fullName    = fullName;
        this.userType    = userType;
        this.lastLoginAt = lastLoginAt;
        this.entraOid    = entraOid;
    }

    public String getUserId()        { return userId; }
    public String getFullName()      { return fullName; }
    public String getUserType()      { return userType; }
    public String getLastLoginAt()   { return lastLoginAt; }
    public String getEntraOid()      { return entraOid; }
    public boolean isNeverLoggedIn() { return lastLoginAt == null; }
    public String getPendingReason() { return pendingReason; }
    public void setPendingReason(String r) { this.pendingReason = r; }
}
