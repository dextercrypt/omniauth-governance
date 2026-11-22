package io.jenkins.plugins.omniauth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class UserAssignmentInfo {
    private final String scope;
    private final String displayScope;
    private final String scopeType;
    private final String roleName;
    private final List<String> permissionIds;
    private final String expiresAt;
    private final List<String> customPermissions;

    public UserAssignmentInfo(String scope, String displayScope, String scopeType,
                              String roleName, List<String> permissionIds,
                              String expiresAt, List<String> customPermissions) {
        this.scope             = scope;
        this.displayScope      = displayScope;
        this.scopeType         = scopeType;
        this.roleName          = roleName;
        this.permissionIds     = permissionIds;
        this.expiresAt         = expiresAt;
        this.customPermissions = customPermissions != null ? customPermissions : Collections.emptyList();
    }

    public String getScope()                     { return scope; }
    public String getDisplayScope()              { return displayScope; }
    public String getScopeType()                 { return scopeType; }
    public String getRoleName()                  { return roleName; }
    public List<String> getPermissionIds()       { return permissionIds; }
    public String getExpiresAt()                 { return expiresAt; }
    public List<String> getCustomPermissions()   { return customPermissions; }
    private boolean reviewDue = false;
    // lgtm false positive: Not a credential: an enum-like marker holding "STANDING"/"JIT" on a view-model row.
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String accessType;
    private String approverGroup;
    private int maxDurationHours;
    private int approvalTimeoutHours;

    public boolean isGlobal()                    { return scope == null || scope.isEmpty(); }
    public int getPermissionCount()              { return permissionIds != null ? permissionIds.size() : 0; }
    public boolean isReviewDue()                 { return reviewDue; }
    public void setReviewDue(boolean v)          { this.reviewDue = v; }
    public String getAccessType()                { return accessType != null ? accessType : "STANDING"; }
    public void setAccessType(String v)          { this.accessType = v; }
    public boolean isJit()                       { return "JIT".equalsIgnoreCase(accessType); }
    public String getApproverGroup()             { return approverGroup != null ? approverGroup : ""; }
    public void setApproverGroup(String v)       { this.approverGroup = v; }
    public int getMaxDurationHours()             { return maxDurationHours > 0 ? maxDurationHours : 4; }
    public void setMaxDurationHours(int v)       { this.maxDurationHours = v; }
    public int getApprovalTimeoutHours()         { return approvalTimeoutHours > 0 ? approvalTimeoutHours : 4; }
    public void setApprovalTimeoutHours(int v)   { this.approvalTimeoutHours = v; }

    public String getCustomPermissionsJson() {
        if (customPermissions == null || customPermissions.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < customPermissions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(customPermissions.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    public String getPermissionSummary() {
        if (permissionIds == null || permissionIds.isEmpty()) return "none";
        List<String> names = new ArrayList<>();
        for (String pid : permissionIds) {
            int dot = pid.lastIndexOf('.');
            names.add(dot >= 0 ? pid.substring(dot + 1) : pid);
        }
        if (names.size() <= 3) return String.join(", ", names);
        return names.get(0) + ", " + names.get(1) + " + " + (names.size() - 2) + " more";
    }
}
