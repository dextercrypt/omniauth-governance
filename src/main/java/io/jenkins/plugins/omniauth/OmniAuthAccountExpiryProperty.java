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

    private String  expiresAt; // ISO-8601 UTC; null/blank = no expiry
    private boolean disabled;  // set once the worker has disabled this account (so it isn't reprocessed)

    public OmniAuthAccountExpiryProperty() {}

    public OmniAuthAccountExpiryProperty(String expiresAt) { this.expiresAt = expiresAt; }

    public String getExpiresAt()            { return expiresAt; }
    public void   setExpiresAt(String v)    { this.expiresAt = v; }

    public boolean isDisabled()             { return disabled; }
    public void    setDisabled(boolean v)   { this.disabled = v; }

    public boolean isExpired() {
        if (expiresAt == null || expiresAt.isBlank()) return false;
        try { return java.time.Instant.parse(expiresAt).isBefore(java.time.Instant.now()); }
        catch (Exception e) { return false; }
    }

    @Extension
    public static final class DescriptorImpl extends UserPropertyDescriptor {
        @Override public String getDisplayName() { return ""; }
        // Not shown in the user-config UI and never auto-attached.
        @Override public UserProperty newInstance(User user) { return null; }
    }
}
