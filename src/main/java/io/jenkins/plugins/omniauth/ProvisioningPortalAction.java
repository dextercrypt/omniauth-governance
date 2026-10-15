package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
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
 *   <li>a native (username/password) request form (this increment).</li>
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

    // Session keys for the verified Entra identity awaiting a reason (written by
    // OmniAuthSecurityRealm#handleEntraProvisionRequest, read here on the reason page).
    static final String SESSION_ENTRA_OID    = "omniauth.provision.entra.oid";
    static final String SESSION_ENTRA_USERID = "omniauth.provision.entra.userId";
    static final String SESSION_ENTRA_NAME   = "omniauth.provision.entra.name";
    static final String SESSION_ENTRA_EMAIL  = "omniauth.provision.entra.email";

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

    // ── Entra reason-capture page state (read from the session stash) ──────────

    private static String entraStash(String key) {
        StaplerRequest2 r = Stapler.getCurrentRequest2();
        if (r == null) return null;
        jakarta.servlet.http.HttpSession s = r.getSession(false);
        if (s == null) return null;
        Object v = s.getAttribute(key);
        return v != null ? v.toString() : null;
    }

    /** True when a Microsoft-verified identity is waiting for a reason (reason.jelly gate). */
    public boolean isEntraReasonReady() {
        String oid = entraStash(SESSION_ENTRA_OID);
        String uid = entraStash(SESSION_ENTRA_USERID);
        return oid != null && !oid.isEmpty() && uid != null && !uid.isEmpty();
    }

    public String getEntraReasonName()   { String v = entraStash(SESSION_ENTRA_NAME);   return v != null ? v : ""; }
    public String getEntraReasonEmail()  { String v = entraStash(SESSION_ENTRA_EMAIL);  return v != null ? v : ""; }
    public String getEntraReasonUserId() { String v = entraStash(SESSION_ENTRA_USERID); return v != null ? v : ""; }

    // ── Native request submission ─────────────────────────────────────────────

    /** POST from the native (username/password) request form. */
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
        String access   = trim(req.getParameter("requestedAccess"));
        int    days     = parseDays(req.getParameter("durationDays"), c.getProvisioningDefaultDurationDays());

        if (username.isEmpty() || email.isEmpty() || reason.isEmpty() || access.isEmpty()) {
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
            r.setRequestedAccess(access);
            store.addRequestIfNoPending(r);
            LOGGER.info("Provisioning: native access request submitted for '" + username + "'");
            NotificationService.sendProvisioningRequested(c, r);
        }

        rsp.sendRedirect(ctx + "/request-access/?submitted=1");
    }

    // ── Entra reason submission ───────────────────────────────────────────────

    /**
     * POST from the Entra reason-capture page. The requester's identity was already proven by
     * Microsoft and stashed on the session; here we add their reason and create the PENDING
     * request. No account is created — an admin still approves.
     */
    @POST
    public void doSubmitEntraReason(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        String ctx = req.getContextPath();
        OmniAuthGlobalConfig c = cfg();
        if (c == null || !c.isProvisioningEnabled() || !c.isProvisioningAllowEntra()) {
            rsp.sendRedirect(ctx + "/request-access/"); return;
        }

        jakarta.servlet.http.HttpSession s = req.getSession(false);
        String oid    = s == null ? null : str(s.getAttribute(SESSION_ENTRA_OID));
        String userId = s == null ? null : str(s.getAttribute(SESSION_ENTRA_USERID));
        if (oid == null || oid.isEmpty() || userId == null || userId.isEmpty()) {
            // Stash gone (session expired, or page reached without the Microsoft round-trip).
            rsp.sendRedirect(ctx + "/request-access/?error=session"); return;
        }
        String name  = str(s.getAttribute(SESSION_ENTRA_NAME));
        String email = str(s.getAttribute(SESSION_ENTRA_EMAIL));

        String reason = trim(req.getParameter("reason"));
        String access = trim(req.getParameter("requestedAccess"));
        int    days   = parseDays(req.getParameter("durationDays"), c.getProvisioningDefaultDurationDays());
        if (reason.isEmpty() || access.isEmpty()) {
            rsp.sendRedirect(ctx + "/request-access/reason?error=missing"); return;
        }

        // Consume the stash — one reason page per verified round-trip.
        s.removeAttribute(SESSION_ENTRA_OID);
        s.removeAttribute(SESSION_ENTRA_USERID);
        s.removeAttribute(SESSION_ENTRA_NAME);
        s.removeAttribute(SESSION_ENTRA_EMAIL);

        // Never clobber an existing account; dedupe a pending request (same guards as the realm).
        if (User.getById(userId, false) != null) {
            rsp.sendRedirect(ctx + "/request-access/?already=1"); return;
        }
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        if (store != null && store.findPendingForUser(userId) == null) {
            ProvisioningRequest r = ProvisioningRequest.create(
                    ProvisioningRequest.TYPE_ENTRA, userId, name, email, oid, reason, days, clientIp(req));
            r.setRequestedAccess(access);
            store.addRequestIfNoPending(r);
            LOGGER.info("Provisioning: Entra access request submitted for '" + userId + "'");
            NotificationService.sendProvisioningRequested(c, r);
        }
        rsp.sendRedirect(ctx + "/request-access/?submitted=1");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String str(Object o) { return o == null ? null : o.toString(); }

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
