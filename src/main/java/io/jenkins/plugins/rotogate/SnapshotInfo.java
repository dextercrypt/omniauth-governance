package io.jenkins.plugins.rotogate;


public final class SnapshotInfo {
    private final String fileName, displayTime;
    private final int roles, assignments, groups;
    SnapshotInfo(String fileName, String displayTime, int roles, int assignments, int groups) {
        this.fileName = fileName; this.displayTime = displayTime;
        this.roles = roles; this.assignments = assignments; this.groups = groups;
    }
    public String getFileName()    { return fileName; }
    public String getDisplayTime() { return displayTime; }
    public int getRoles()          { return roles; }
    public int getAssignments()    { return assignments; }
    public int getGroups()         { return groups; }
}
