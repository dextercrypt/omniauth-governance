package io.jenkins.plugins.omniauth;


public final class FailedLoginEntry {
    public final String userId;
    public final String fullName;
    public final LoginEvent event;
    public FailedLoginEntry(String userId, String fullName, LoginEvent event) {
        this.userId = userId; this.fullName = fullName; this.event = event;
    }
    public String getUserId()    { return userId; }
    public String getFullName()  { return fullName; }
    public LoginEvent getEvent() { return event; }
}
