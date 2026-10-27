package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;

/**
 * Marks a provisioned account as time-limited. Attached when a provisioning request with a
 * non-zero duration is approved; the {@link OmniAuthAccountExpiryWorker} acts on it once the
 * expiry passes (disable or delete, per the portal's expiry-action setting).
 *
 * <p>Deliberately decoupled from {@link OmniAuthUserProperty} so it applies uniformly to native
 * and Entra accounts without touching the SSO pre-provisioning logic.
 */
public class OmniAuthAccountExpiryProperty extends UserProperty {

    private String  expiresAt;    // ISO-8601 UTC; null/blank = no expiry
    private boolean disabled;     // set once the worker has disabled this account (so it isn't reprocessed)
    private boolean reminderSent; // set once the pre-expiry reminder email has gone out

    public OmniAuthAccountExpiryProperty() {}

    public OmniAuthAccountExpiryProperty(String expiresAt) { this.expiresAt = expiresAt; }

    public String getExpiresAt()            { return expiresAt; }
    public void   setExpiresAt(String v)    {
        // Changing the expiry (e.g. an admin extends the account) starts a fresh reminder
        // window — otherwise the already-sent flag would suppress the reminder for the new date.
        if (v == null ? this.expiresAt != null : !v.equals(this.expiresAt)) {
            this.reminderSent = false;
        }
        this.expiresAt = v;
    }

    public boolean isDisabled()             { return disabled; }
    public void    setDisabled(boolean v)   { this.disabled = v; }

    public boolean isReminderSent()          { return reminderSent; }
    public void    setReminderSent(boolean v) { this.reminderSent = v; }

    public boolean isExpired() {
        if (expiresAt == null || expiresAt.isBlank()) return false;
        try { return java.time.Instant.parse(expiresAt).isBefore(java.time.Instant.now()); }
        catch (Exception e) { return true; } // corrupt expiry -> fail closed (treat as expired)
    }

    /** Whole days until expiry (rounded up), or -1 if no/invalid expiry or already past. */
    public long daysUntilExpiry() {
        if (expiresAt == null || expiresAt.isBlank()) return -1;
        try {
            long ms = java.time.Instant.parse(expiresAt).toEpochMilli() - System.currentTimeMillis();
            if (ms <= 0) return -1;
            return (ms + 86_399_999L) / 86_400_000L;
        } catch (Exception e) { return -1; }
    }

    @Extension
    public static final class DescriptorImpl extends UserPropertyDescriptor {
        @Override public String getDisplayName() { return ""; }
        // Operational property (admin-set account expiry): hide it from the per-user config UI. Without
        // this, the core "Account" settings page (UserPropertyCategoryAccountAction) tries to
        // <st:include> a config.jelly this property does not have and the whole page render throws. All the
        // other operational properties here disable themselves the same way.
        @Override public boolean isEnabled() { return false; }
        // Not shown in the user-config UI and never auto-attached.
        @Override public UserProperty newInstance(User user) { return null; }
    }
}
