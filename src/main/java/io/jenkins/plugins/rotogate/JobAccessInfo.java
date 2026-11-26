package io.jenkins.plugins.rotogate;


public final class JobAccessInfo {
    private final String jobName;
    private final boolean canRead;
    private final boolean canBuild;
    private final boolean canConfigure;
    private final boolean canDelete;
    private final boolean canWorkspace;
    private final String source;

    public JobAccessInfo(String jobName, boolean canRead, boolean canBuild, boolean canConfigure,
                         boolean canDelete, boolean canWorkspace, String source) {
        this.jobName      = jobName;
        this.canRead      = canRead;
        this.canBuild     = canBuild;
        this.canConfigure = canConfigure;
        this.canDelete    = canDelete;
        this.canWorkspace = canWorkspace;
        this.source       = source != null ? source : "Global";
    }

    public String getJobName()       { return jobName; }
    public boolean isCanRead()       { return canRead; }
    public boolean isCanBuild()      { return canBuild; }
    public boolean isCanConfigure()  { return canConfigure; }
    public boolean isCanDelete()     { return canDelete; }
    public boolean isCanWorkspace()  { return canWorkspace; }
    public String getSource()        { return source; }
}
