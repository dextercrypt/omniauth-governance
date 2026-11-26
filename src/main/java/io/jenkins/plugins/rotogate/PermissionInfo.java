package io.jenkins.plugins.rotogate;

import hudson.security.Permission;
import jenkins.model.Jenkins;

public final class PermissionInfo {
    private final String id;
    private final String name;
    private final String shortId;
    private final String description;
    private final String impliedById;

    public PermissionInfo(String id, String name, String shortId, String description, String impliedById) {
        this.id = id;
        this.name = name;
        this.shortId = shortId;
        this.description = description != null ? description : "";
        this.impliedById = impliedById != null ? impliedById : "";
    }

    /** Build from a Jenkins {@link Permission}, qualified by its group's display title. */
    static PermissionInfo of(String groupTitle, Permission p) {
        String desc = "";
        try {
            if (p.description != null) {
                desc = p.description.toString();
            }
        } catch (RuntimeException ignore) {
            // a plugin's Localizable can throw if its resource bundle is missing — skip the tooltip
        }
        // Shown as the de-emphasised qualifier, e.g. "Lockable Resources/Steal" — the form CasC
        // understands, instead of a raw class name like "LockableResourcesManager.Steal".
        String qualified = groupTitle + "/" + p.name;
        String impliedBy = p.impliedBy != null ? p.impliedBy.getId() : "";
        return new PermissionInfo(p.getId(), p.name, qualified, desc, impliedBy);
    }

    public String getId()          { return id; }
    public String getName()        { return name; }
    public String getShortId()     { return shortId; }
    public String getDescription() { return description; }
    /** Id of the permission that implies this one (grants it automatically), or "" if none. */
    public String getImpliedById() { return impliedById; }
}
