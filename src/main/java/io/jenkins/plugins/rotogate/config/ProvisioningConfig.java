package io.jenkins.plugins.rotogate.config;

import org.kohsuke.stapler.StaplerRequest2;

/**
 * Self-service provisioning-portal configuration: who may request access, how long granted accounts
 * live, the expiry action and reminders, the native-request rate-limiter ("breaker") and the
 * one-time activation-link lifetime.
 *
 * <p>Owned by {@link io.jenkins.plugins.rotogate.RotogateGlobalConfig}, which keeps delegating
 * accessors onto it. These were already set field-by-field from the Provisioning page; {@link
 * #updateFrom} simply gathers that binding in one place.
 */
public class ProvisioningConfig {

    private boolean provisioningEnabled            = false;
    private boolean provisioningAllowEntra         = true;
    private boolean provisioningAllowNative        = false;
    private String  provisioningAllowedDomains     = "";   // comma/space separated; empty = any
    private int     provisioningDefaultDurationDays = 0;   // 0 = permanent
    private String  provisioningExpiryAction       = "DISABLE"; // DISABLE | DELETE
    private int     provisioningMaxPendingPerUser  = 1;    // Entra: pending cap per requester
    private int     provisioningBreakerMax         = 100;  // native: max requests in the window
    private int     provisioningBreakerWindowMin   = 10;   // native: rolling window (minutes)
    private int     provisioningBreakerCooldownMin = 30;   // native: pause duration once tripped
    private int     provisioningExpiryReminderDays = 7;    // email the account this many days before expiry (0 = off)
    private int     activationLinkTtlHours         = 72;   // native approval: one-time activation link lifetime

    public ProvisioningConfig() { }

    public boolean isProvisioningEnabled()            { return provisioningEnabled; }
    public void    setProvisioningEnabled(boolean v)  { this.provisioningEnabled = v; }

    public boolean isProvisioningAllowEntra()           { return provisioningAllowEntra; }
    public void    setProvisioningAllowEntra(boolean v) { this.provisioningAllowEntra = v; }

    public boolean isProvisioningAllowNative()           { return provisioningAllowNative; }
    public void    setProvisioningAllowNative(boolean v) { this.provisioningAllowNative = v; }

    public String  getProvisioningAllowedDomains()          { return provisioningAllowedDomains != null ? provisioningAllowedDomains : ""; }
    public void    setProvisioningAllowedDomains(String v)  { this.provisioningAllowedDomains = v != null ? v.trim() : ""; }

    public int     getProvisioningDefaultDurationDays()         { return Math.max(0, provisioningDefaultDurationDays); }
    public void    setProvisioningDefaultDurationDays(int v)    { this.provisioningDefaultDurationDays = Math.max(0, v); }

    public String  getProvisioningExpiryAction()         { return "DELETE".equals(provisioningExpiryAction) ? "DELETE" : "DISABLE"; }
    public void    setProvisioningExpiryAction(String v) { this.provisioningExpiryAction = "DELETE".equalsIgnoreCase(v) ? "DELETE" : "DISABLE"; }

    public int     getProvisioningExpiryReminderDays()      { return Math.max(0, provisioningExpiryReminderDays); }
    public void    setProvisioningExpiryReminderDays(int v) { this.provisioningExpiryReminderDays = Math.max(0, v); }

    public int     getActivationLinkTtlHours()      { return activationLinkTtlHours > 0 ? activationLinkTtlHours : 72; }
    public void    setActivationLinkTtlHours(int v) { this.activationLinkTtlHours = v > 0 ? v : 72; }

    public int     getProvisioningMaxPendingPerUser()        { return provisioningMaxPendingPerUser > 0 ? provisioningMaxPendingPerUser : 1; }
    public void    setProvisioningMaxPendingPerUser(int v)   { this.provisioningMaxPendingPerUser = v > 0 ? v : 1; }

    public int     getProvisioningBreakerMax()          { return provisioningBreakerMax > 0 ? provisioningBreakerMax : 100; }
    public void    setProvisioningBreakerMax(int v)     { this.provisioningBreakerMax = v > 0 ? v : 100; }

    public int     getProvisioningBreakerWindowMin()        { return provisioningBreakerWindowMin > 0 ? provisioningBreakerWindowMin : 10; }
    public void    setProvisioningBreakerWindowMin(int v)   { this.provisioningBreakerWindowMin = v > 0 ? v : 10; }

    public int     getProvisioningBreakerCooldownMin()        { return provisioningBreakerCooldownMin > 0 ? provisioningBreakerCooldownMin : 30; }
    public void    setProvisioningBreakerCooldownMin(int v)   { this.provisioningBreakerCooldownMin = v > 0 ? v : 30; }

    /** True when the portal is on and at least one request type is allowed. */
    public boolean isProvisioningUsable() {
        return provisioningEnabled && (provisioningAllowEntra || provisioningAllowNative);
    }

    /** Apply all fields submitted by the Provisioning page. */
    public void updateFrom(StaplerRequest2 req) {
        setProvisioningEnabled(req.getParameter("provisioningEnabled") != null);
        setProvisioningAllowEntra(req.getParameter("provisioningAllowEntra") != null);
        setProvisioningAllowNative(req.getParameter("provisioningAllowNative") != null);
        setProvisioningAllowedDomains(req.getParameter("provisioningAllowedDomains"));
        setProvisioningDefaultDurationDays(parseIntOr(req.getParameter("provisioningDefaultDurationDays"), 0));
        setProvisioningExpiryAction(req.getParameter("provisioningExpiryAction"));
        setProvisioningMaxPendingPerUser(parseIntOr(req.getParameter("provisioningMaxPendingPerUser"), 1));
        setProvisioningBreakerMax(parseIntOr(req.getParameter("provisioningBreakerMax"), 100));
        setProvisioningBreakerWindowMin(parseIntOr(req.getParameter("provisioningBreakerWindowMin"), 10));
        setProvisioningBreakerCooldownMin(parseIntOr(req.getParameter("provisioningBreakerCooldownMin"), 30));
        setProvisioningExpiryReminderDays(parseIntOr(req.getParameter("provisioningExpiryReminderDays"), 7));
        setActivationLinkTtlHours(parseIntOr(req.getParameter("activationLinkTtlHours"), 72));
    }

    private static int parseIntOr(String v, int fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
