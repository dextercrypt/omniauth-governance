package io.jenkins.plugins.rotogate.config;

import io.jenkins.plugins.rotogate.RotogateGlobalConfig;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Account-lifecycle configuration: activity thresholds, the automated stale-account cleanup and its
 * run history, the stale-account warning job, periodic access review and the audit / JIT retention
 * windows. These are the "how long things live and when we act on them" knobs, grouped together.
 *
 * <p>Owned by {@link RotogateGlobalConfig}, which keeps delegating accessors onto it. The cleanup
 * history is kept as {@link RotogateGlobalConfig.CleanupRunRecord} so the record type stays where its
 * existing callers already reference it.
 */
public class LifecycleConfig {

    private static final int MAX_HISTORY = 10;

    // ── Thresholds ────────────────────────────────────────────────────────────
    private int staleThresholdDays  = 90;
    private int activeThresholdDays = 30;

    // ── Audit log retention (days; 0 = keep forever) ──────────────────────────
    private int auditRetentionDays = 365;

    // ── Automated cleanup ─────────────────────────────────────────────────────
    private boolean cleanupEnabled      = false;
    private boolean cleanupDryRun       = true;
    private String  cleanupCron         = "0 2 * * 0";
    private int     cleanupMaxDeletions = 50;
    private String  cleanupNotifyEmail  = ""; // legacy - kept for migration, replaced by notifyEmails

    // ── Stale warning ─────────────────────────────────────────────────────────
    private boolean staleWarningEnabled    = false;
    private String  staleWarningCron       = "0 9 * * 1";
    private int     staleWarningWindowDays = 14;

    // ── Access review ─────────────────────────────────────────────────────────
    private boolean accessReviewEnabled       = false;
    private int     accessReviewThresholdDays = 90;

    // ── JIT history retention ─────────────────────────────────────────────────
    private int jitHistoryRetentionDays = 90;

    // ── Cleanup history ───────────────────────────────────────────────────────
    private List<RotogateGlobalConfig.CleanupRunRecord> cleanupHistory = new ArrayList<>();

    public LifecycleConfig() { }

    // Thresholds
    public int getStaleThresholdDays()  { return staleThresholdDays; }
    public void setStaleThresholdDays(int v)  { this.staleThresholdDays = v; }
    public int getActiveThresholdDays() { return activeThresholdDays; }
    public void setActiveThresholdDays(int v) { this.activeThresholdDays = v; }

    // Audit retention
    public int getAuditRetentionDays()  { return auditRetentionDays; }
    public void setAuditRetentionDays(int v) { this.auditRetentionDays = Math.max(0, v); }

    // Cleanup
    public boolean isCleanupEnabled()   { return cleanupEnabled; }
    public void setCleanupEnabled(boolean v) { this.cleanupEnabled = v; }
    public boolean isCleanupDryRun()    { return cleanupDryRun; }
    public void setCleanupDryRun(boolean v)  { this.cleanupDryRun = v; }
    public String getCleanupCron()      { return cleanupCron; }
    public void setCleanupCron(String v)     { this.cleanupCron = v; }
    public int getCleanupMaxDeletions() { return cleanupMaxDeletions; }
    public void setCleanupMaxDeletions(int v) { this.cleanupMaxDeletions = v; }
    public String getCleanupNotifyEmail() { return cleanupNotifyEmail; }
    public void setCleanupNotifyEmail(String v) { this.cleanupNotifyEmail = v; }

    // Stale warning
    public boolean isStaleWarningEnabled()    { return staleWarningEnabled; }
    public void setStaleWarningEnabled(boolean v)  { this.staleWarningEnabled = v; }
    public String  getStaleWarningCron()      { return staleWarningCron; }
    public void setStaleWarningCron(String v)      { this.staleWarningCron = v; }
    public int     getStaleWarningWindowDays(){ return staleWarningWindowDays; }
    public void setStaleWarningWindowDays(int v)   { this.staleWarningWindowDays = v; }

    // Access review
    public boolean isAccessReviewEnabled()       { return accessReviewEnabled; }
    public void setAccessReviewEnabled(boolean v)     { this.accessReviewEnabled = v; }
    public int     getAccessReviewThresholdDays() { return accessReviewThresholdDays; }
    public void setAccessReviewThresholdDays(int v)   { this.accessReviewThresholdDays = v; }

    // JIT history retention
    public int getJitHistoryRetentionDays() { return jitHistoryRetentionDays > 0 ? jitHistoryRetentionDays : 90; }
    public void setJitHistoryRetentionDays(int v) { this.jitHistoryRetentionDays = v > 0 ? v : 90; }

    // Cleanup history
    public List<RotogateGlobalConfig.CleanupRunRecord> getCleanupHistory() {
        return Collections.unmodifiableList(cleanupHistory);
    }
    /** Prepend a run record (newest first) and cap the history. Caller persists. */
    public synchronized void addCleanupRecord(RotogateGlobalConfig.CleanupRunRecord record) {
        cleanupHistory.add(0, record);
        if (cleanupHistory.size() > MAX_HISTORY) {
            cleanupHistory = new ArrayList<>(cleanupHistory.subList(0, MAX_HISTORY));
        }
    }

    /** Apply the thresholds / cleanup / access-review fields submitted by the Settings page. */
    public void updateSettingsFrom(StaplerRequest2 req) {
        if (req.getParameter("staleThresholdDays")  != null) staleThresholdDays  = parseInt(req.getParameter("staleThresholdDays"),  staleThresholdDays);
        if (req.getParameter("activeThresholdDays") != null) activeThresholdDays = parseInt(req.getParameter("activeThresholdDays"), activeThresholdDays);
        if (req.getParameter("auditRetentionDays")  != null) auditRetentionDays  = Math.max(0, parseInt(req.getParameter("auditRetentionDays"), auditRetentionDays));

        cleanupEnabled = req.getParameter("cleanupEnabled") != null;
        cleanupDryRun  = !cleanupEnabled || req.getParameter("cleanupDryRun") != null;
        if (req.getParameter("cleanupCron")         != null) cleanupCron         = trim(req.getParameter("cleanupCron"));
        if (req.getParameter("cleanupMaxDeletions") != null) cleanupMaxDeletions = parseInt(req.getParameter("cleanupMaxDeletions"), cleanupMaxDeletions);

        accessReviewEnabled = req.getParameter("accessReviewEnabled") != null;
        if (req.getParameter("accessReviewThresholdDays") != null)
            accessReviewThresholdDays = parseInt(req.getParameter("accessReviewThresholdDays"), accessReviewThresholdDays);
    }

    /** Apply the stale-warning fields submitted by the Notifications page. */
    public void updateStaleWarningFrom(StaplerRequest2 req) {
        staleWarningEnabled = req.getParameter("staleWarningEnabled") != null;
        if (req.getParameter("staleWarningCron")       != null) staleWarningCron       = trim(req.getParameter("staleWarningCron"));
        if (req.getParameter("staleWarningWindowDays") != null) staleWarningWindowDays = parseInt(req.getParameter("staleWarningWindowDays"), staleWarningWindowDays);
    }

    private static String trim(String v) { return v != null ? v.trim() : ""; }

    private static int parseInt(String v, int fallback) {
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
