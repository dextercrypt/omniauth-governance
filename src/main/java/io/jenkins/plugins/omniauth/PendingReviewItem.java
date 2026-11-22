package io.jenkins.plugins.omniauth;


public final class PendingReviewItem {
    private final String userId;
    private final String displayName;
    private final String authType;
    private final String roleId;
    private final String scope;
    private final String displayScope;
    private final String scopeType;
    private final int daysAgo;
    private final boolean everReviewed;

    public PendingReviewItem(String userId, String displayName, String authType,
                             String roleId, String scope, String displayScope,
                             String scopeType, int daysAgo, boolean everReviewed) {
        this.userId       = userId;
        this.displayName  = displayName;
        this.authType     = authType;
        this.roleId       = roleId;
        this.scope        = scope;
        this.displayScope = displayScope;
        this.scopeType    = scopeType;
        this.daysAgo      = daysAgo;
        this.everReviewed = everReviewed;
    }

    public String getUserId()       { return userId; }
    public String getDisplayName()  { return displayName; }
    public String getAuthType()     { return authType; }
    public String getRoleId()       { return roleId; }
    public String getScope()        { return scope; }
    public String getDisplayScope() { return displayScope; }
    public String getScopeType()    { return scopeType; }
    public int getDaysAgo()         { return daysAgo; }
    public boolean isEverReviewed() { return everReviewed; }
    public boolean isGroup()        { return "GROUP".equalsIgnoreCase(authType); }

    public String getAvatarLetter() {
        String src = (displayName != null && !displayName.equals(userId)) ? displayName : userId;
        return (src != null && !src.isEmpty()) ? src.substring(0, 1).toUpperCase() : "?";
    }
}
