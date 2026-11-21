package io.jenkins.plugins.omniauth;


public final class BruteForceEntry {
    public final String userId;
    public final String fullName;
    public final int failureCount;
    public final String alertedAt;
    public boolean locked;
    public long lockRemainingMinutes;
    public BruteForceEntry(String userId, String fullName, int failureCount, String alertedAt) {
        this.userId = userId; this.fullName = fullName;
        this.failureCount = failureCount; this.alertedAt = alertedAt;
    }
    public String getUserId()     { return userId; }
    public String getFullName()   { return fullName; }
    public int getFailureCount()  { return failureCount; }
    public String getAlertedAt()  { return alertedAt; }
    public boolean isAlerted()    { return alertedAt != null; }
    public String getRelativeAlertedAt() { return alertedAt != null ? OmniAuthManagementLink.relativeTime(alertedAt) : null; }
    public boolean isLocked()     { return locked; }
    public long getLockRemainingMinutes() { return lockRemainingMinutes; }
}
