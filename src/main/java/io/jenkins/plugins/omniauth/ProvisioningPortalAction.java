package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

import java.util.logging.Logger;

/**
 * Public, unauthenticated self-service access request portal at {@code /request-access/}.
 *
 * <p>Renders a page (index.jelly) offering, per the admin settings:
 * <ul>
 *   <li>"Request access via Microsoft" — identity-proven Entra flow (wired in a later increment), and</li>
 *   <li>a native/service-account request form (this increment).</li>
 * </ul>
 *
 * <p>Nothing is created here — a submission produces a {@code PENDING}
 * {@link ProvisioningRequest} for an admin to approve. The native form is guarded by the
 * {@link ProvisioningBreaker} and responds identically whether or not the account/request
 * already exists, to avoid account enumeration.
 *
 * <p>Like {@link OmniAuthRootAction}, being an {@link UnprotectedRootAction} puts this path on
 * Jenkins' Spring Security permit list so anonymous users can reach it.
 */
@Extension
public class ProvisioningPortalAction implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(ProvisioningPortalAction.class.getName());

    @Override public String getUrlName()      { return "request-access"; }
    @Override public String getIconFileName() { return null; }
    @Override public String getDisplayName()  { return null; }

    // ── State exposed to index.jelly ──────────────────────────────────────────

    private OmniAuthGlobalConfig cfg() { return OmniAuthGlobalConfig.get(); }

    public boolean isPortalEnabled() {
        OmniAuthGlobalConfig c = cfg();
        return c != null && c.isProvisioningEnabled();
    }

    public boolean isEntraAllowed() {
        OmniAuthGlobalConfig c = cfg();
        return c != null && c.isProvisioningEnabled() && c.isProvisioningAllowEntra();
    }

    public boolean isNativeAllowed() {
        OmniAuthGlobalConfig c = cfg();
        return c != null && c.isProvisioningEnabled() && c.isProvisioningAllowNative();
    }

    /** Native intake currently open (allowed and breaker not tripped). */
    public boolean isNativeAvailable() {
        return isNativeAllowed() && !ProvisioningBreaker.isTripped();
    }

    public long getNativeCooldownMinutes() {
        return (ProvisioningBreaker.cooldownSecondsRemaining() + 59) / 60; // round up
    }

    public String getAllowedDomains() {
        OmniAuthGlobalConfig c = cfg();
        return c != null ? c.getProvisioningAllowedDomains() : "";
    }

    public int getDefaultDurationDays() {
        OmniAuthGlobalConfig c = cfg();
        return c != null ? c.getProvisioningDefaultDurationDays() : 0;
    }

    // ── Native request submission ─────────────────────────────────────────────

    /** POST from the native/service-account request form. */
    @POST
    public void doSubmitNative(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        String ctx = req.getContextPath();
        OmniAuthGlobalConfig c = cfg();

        if (c == null || !c.isProvisioningEnabled() || !c.isProvisioningAllowNative()) {
            rsp.sendRedirect(ctx + "/request-access/"); return;
        }

        // Flood guard first — count every attempt, valid or not.
        if (!ProvisioningBreaker.tryAcquire(c)) {
            rsp.sendRedirect(ctx + "/request-access/?error=unavailable"); return;
        }

        String username = trim(req.getParameter("username"));
        String fullName = trim(req.getParameter("fullName"));
        String email    = trim(req.getParameter("email"));
        String reason   = trim(req.getParameter("reason"));
        int    days     = parseDays(req.getParameter("durationDays"), c.getProvisioningDefaultDurationDays());

        if (username.isEmpty() || email.isEmpty() || reason.isEmpty()) {
            rsp.sendRedirect(ctx + "/request-access/?error=missing"); return;
        }

        // Hard domain gate (not enumeration-sensitive — it's about the requester's own address).
        if (!domainAllowed(email, c.getProvisioningAllowedDomains())) {
            rsp.sendRedirect(ctx + "/request-access/?error=domain"); return;
        }

        // Enumeration-safe: never reveal that the account or a request already exists —
        // silently no-op and show the same confirmation.
        boolean duplicate = User.getById(username, false) != null;
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        if (!duplicate && store != null && store.findPendingForUser(username) != null) {
            duplicate = true;
        }

        if (!duplicate && store != null) {
            ProvisioningRequest r = ProvisioningRequest.create(
                    ProvisioningRequest.TYPE_NATIVE, username, fullName, email, null, reason, days, clientIp(req));
            store.addRequest(r);
            LOGGER.info("Provisioning: native access request submitted for '" + username + "'");
            // Admin notification is wired in the notifications increment.
        }

        rsp.sendRedirect(ctx + "/request-access/?submitted=1");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static int parseDays(String v, int fallback) {
        if (v == null || v.isBlank()) return Math.max(0, fallback);
        try { return Math.max(0, Integer.parseInt(v.trim())); }
        catch (NumberFormatException e) { return Math.max(0, fallback); }
    }

    /** True if {@code email}'s domain matches one of the allowed domains, or if none are configured. */
    static boolean domainAllowed(String email, String allowedDomains) {
        if (allowedDomains == null || allowedDomains.isBlank()) return true;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return false;
        String domain = email.substring(at + 1).toLowerCase();
        for (String d : allowedDomains.toLowerCase().split("[,\\s]+")) {
            String allowed = d.startsWith("@") ? d.substring(1) : d;
            if (!allowed.isBlank() && domain.equals(allowed)) return true;
        }
        return false;
    }

    /** Best-effort client IP for audit / rate context (first X-Forwarded-For hop, else remote addr). */
    private static String clientIp(StaplerRequest2 req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }
}
