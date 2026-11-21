package io.jenkins.plugins.omniauth;


public final class HistoryEntry {
    private final String displayTime, user, action, summary;
    HistoryEntry(String displayTime, String user, String action, String summary) {
        this.displayTime = displayTime; this.user = user; this.action = action; this.summary = summary;
    }
    public String getDisplayTime() { return displayTime; }
    public String getUser()        { return user; }
    public String getAction()      { return action; }
    public String getSummary()     { return summary; }
}
