package io.jenkins.plugins.rotogate;

import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import java.time.Instant;
import java.util.Set;

public final class AccessManagementUserInfo {
    private final String sid;
    private final String displayName;
    private final AuthorizationType type;
    private final String roleName;
    private final Set<String> permissionIds;
    private final String lastLoginAt;
    private String provisioningSource = "NATIVE";
    private boolean hasReviewDue = false;

    public AccessManagementUserInfo(String sid, String displayName, AuthorizationType type,
                                    String roleName, Set<String> permissionIds, String lastLoginAt) {
        this.sid           = sid;
        this.displayName   = displayName;
        this.type          = type;
        this.roleName      = roleName;
        this.permissionIds = permissionIds;
        this.lastLoginAt   = lastLoginAt;
    }

    public String getSid()             { return sid; }
    public String getDisplayName()     { return displayName; }
    public AuthorizationType getType() { return type; }
    public String getRoleName()        { return roleName; }
    public Set<String> getPermissionIds() { return permissionIds; }
    public String getLastLoginAt()     { return lastLoginAt; }
    public boolean isUserEntry()       { return type == AuthorizationType.USER; }
    public String getProvisioningSource()       { return provisioningSource; }
    public void setProvisioningSource(String s) { this.provisioningSource = s != null ? s : "NATIVE"; }
    public boolean isGroupManaged()             { return "VIA_ENTRA_GROUP".equals(provisioningSource); }
    public boolean isHasReviewDue()             { return hasReviewDue; }
    public void setHasReviewDue(boolean v)      { this.hasReviewDue = v; }

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

    public String getAvatarLetter() {
        String src = (displayName != null && !displayName.equals(sid)) ? displayName : sid;
        return src != null && !src.isEmpty() ? src.substring(0, 1).toUpperCase() : "?";
    }

    public String getRelativeLastLogin() {
        return lastLoginAt != null ? RotogateManagementLink.relativeTime(lastLoginAt) : null;
    }

    public String getPermissionsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : permissionIds) {
            if (!first) sb.append(",");
            sb.append("\"").append(id.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            first = false;
        }
        return sb.append("]").toString();
    }
}
