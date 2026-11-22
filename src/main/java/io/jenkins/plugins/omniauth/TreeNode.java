package io.jenkins.plugins.omniauth;

import java.util.ArrayList;
import java.util.List;

public final class TreeNode {
    public final String path;
    public final String name;
    public final String type;          // root | folder | job | other
    public List<String> directPerms = new ArrayList<>();
    public String directRole;          // ADMIN/DEVELOPER/VIEWER/CUSTOM (root) or CONFIGURE/BUILD/READ/CUSTOM (item)
    public String effectiveRole;
    public List<TreeNode> children = new ArrayList<>();
    public TreeNode(String path, String name, String type) {
        this.path = path; this.name = name; this.type = type;
    }
    public String getPath()          { return path; }
    public String getName()          { return name; }
    public String getType()          { return type; }
    public String getDirectRole()    { return directRole; }
    public String getEffectiveRole() { return effectiveRole; }
    public List<String> getDirectPerms() { return directPerms; }
    public List<TreeNode> getChildren() { return children; }
    public boolean isHasDirect()     { return directRole != null; }
    public int getChildCount()       { return children.size(); }
}
