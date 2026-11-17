package io.jenkins.plugins.omniauth;

import java.util.List;

public final class PermissionGroupInfo {
    private final String groupName;
    private final String subtitle;
    private final List<PermissionInfo> permissions;
    public PermissionGroupInfo(String groupName, String subtitle, List<PermissionInfo> permissions) {
        this.groupName = groupName;
        this.subtitle = subtitle != null ? subtitle : "";
        this.permissions = permissions;
    }
    public String getGroupName()                { return groupName; }
    public String getSubtitle()                 { return subtitle; }
    public List<PermissionInfo> getPermissions() { return permissions; }
}
