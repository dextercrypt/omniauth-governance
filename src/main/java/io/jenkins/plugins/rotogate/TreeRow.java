package io.jenkins.plugins.rotogate;


public final class TreeRow {
    public TreeNode node;
    public int depth;
    public String inheritedRole;       // null if direct grant exists at this node
    public String inheritedFromPath;   // path of the ancestor node where the inherited role originates
    public TreeNode getNode()         { return node; }
    public int getDepth()             { return depth; }
    public String getInheritedRole()  { return inheritedRole; }
    public String getInheritedFromPath() { return inheritedFromPath; }
    public int getIndentPx()          { return depth * 22; }
    public String getDisplayName()    {
        if ("root".equals(node.type)) return node.name;
        return node.name;
    }
}
