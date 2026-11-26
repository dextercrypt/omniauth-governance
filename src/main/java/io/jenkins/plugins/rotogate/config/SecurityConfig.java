package io.jenkins.plugins.rotogate.config;

import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Login-security configuration: brute-force detection / lockout, the SOS lockout-recovery switch and
 * the protected-users list (accounts the automated cleanup and lockout must never touch).
 *
 * <p>Owned by {@link io.jenkins.plugins.rotogate.RotogateGlobalConfig}, which keeps delegating
 * accessors onto it. The brute-force controls live on the Notifications page and the SOS switch on
 * the Settings page, so the two {@code update*From} methods are deliberately narrow: each applies
 * only the fields its own page submitted.
 */
public class SecurityConfig {

    // ── Brute force detection + lockout ───────────────────────────────────────
    private int bruteForceThreshold = 5;
    /** Enforce a temporary account lockout once the threshold is reached (not just alert). */
    private boolean bruteForceLockoutEnabled = true;
    /** How long a locked account stays locked, in minutes. */
    private int bruteForceLockoutMinutes = 15;

    // ── "Keep me signed in" (remember-me) duration ────────────────────────────
    /** Hard ceiling on the remember-me cookie - Jenkins' own default validity (14 days). */
    public static final int REMEMBER_ME_MAX_SECONDS = 14 * 24 * 60 * 60; // 1,209,600
    /** The number the admin typed (paired with {@link #rememberMeUnit}); default 8 hours. */
    private int    rememberMeValue = 8;
    /** Unit for {@link #rememberMeValue}: MINUTES | HOURS | DAYS. */
    private String rememberMeUnit  = "HOURS";

    // ── SOS Mode (lockout recovery) ───────────────────────────────────────────
    private boolean sosEnabled = true;

    // ── Protected users ───────────────────────────────────────────────────────
    private List<String> protectedUsers = new ArrayList<>();

    public SecurityConfig() { }

    // Brute force
    public int getBruteForceThreshold() { return bruteForceThreshold; }
    public void setBruteForceThreshold(int v) { this.bruteForceThreshold = v; }
    public boolean isBruteForceLockoutEnabled() { return bruteForceLockoutEnabled; }
    public void setBruteForceLockoutEnabled(boolean v) { this.bruteForceLockoutEnabled = v; }
    public int getBruteForceLockoutMinutes() { return bruteForceLockoutMinutes > 0 ? bruteForceLockoutMinutes : 15; }
    public void setBruteForceLockoutMinutes(int v) { this.bruteForceLockoutMinutes = v > 0 ? v : 15; }

    // Remember-me ("Keep me signed in") duration
    public int getRememberMeValue() { return rememberMeValue > 0 ? rememberMeValue : 1; }
    public void setRememberMeValue(int v) { this.rememberMeValue = v > 0 ? v : 1; }
    public String getRememberMeUnit() {
        String u = rememberMeUnit != null ? rememberMeUnit.trim().toUpperCase() : "";
        return (u.equals("MINUTES") || u.equals("DAYS")) ? u : "HOURS";
    }
    public void setRememberMeUnit(String v) {
        String u = v != null ? v.trim().toUpperCase() : "";
        this.rememberMeUnit = (u.equals("MINUTES") || u.equals("DAYS")) ? u : "HOURS";
    }
    /** Configured "Keep me signed in" lifetime in seconds, clamped to [1 min, 14 days]. */
    public int getRememberMeSeconds() {
        long secs = (long) getRememberMeValue() * unitSeconds(getRememberMeUnit());
        if (secs < 60) secs = 60;
        if (secs > REMEMBER_ME_MAX_SECONDS) secs = REMEMBER_ME_MAX_SECONDS;
        return (int) secs;
    }
    private static int unitSeconds(String unit) {
        switch (unit) {
            case "MINUTES": return 60;
            case "DAYS":    return 86400;
            default:        return 3600; // HOURS
        }
    }

    // SOS
    public boolean isSosEnabled() { return sosEnabled; }
    public void setSosEnabled(boolean v) { this.sosEnabled = v; }

    // Protected users
    public List<String> getProtectedUsers() { return Collections.unmodifiableList(protectedUsers); }
    public boolean isProtected(String uid)  { return uid != null && protectedUsers.contains(uid); }
    public void setProtectedUsers(List<String> users) {
        this.protectedUsers = (users == null) ? new ArrayList<>() : new ArrayList<>(users);
    }

    /** Apply the brute-force controls submitted by the Notifications page. */
    public void updateBruteForceFrom(StaplerRequest2 req) {
        bruteForceLockoutEnabled = req.getParameter("bruteForceLockoutEnabled") != null;
        if (req.getParameter("bruteForceThreshold") != null)
            bruteForceThreshold = parseInt(req.getParameter("bruteForceThreshold"), bruteForceThreshold);
        if (req.getParameter("bruteForceLockoutMinutes") != null)
            bruteForceLockoutMinutes = parseInt(req.getParameter("bruteForceLockoutMinutes"), bruteForceLockoutMinutes);
    }

    /** Apply the SOS switch submitted by the Settings page. */
    public void updateSosFrom(StaplerRequest2 req) {
        sosEnabled = req.getParameter("sosEnabled") != null;
    }

    /**
     * Apply the "Keep me signed in" duration submitted by the Settings page. Parses the unit first,
     * then clamps the number to the per-unit maximum so value×unit can never exceed the 14-day ceiling.
     */
    public void updateRememberMeFrom(StaplerRequest2 req) {
        if (req.getParameter("rememberMeUnit") != null) setRememberMeUnit(req.getParameter("rememberMeUnit"));
        if (req.getParameter("rememberMeValue") != null) {
            // Blank or invalid → fall back to the 8-unit default rather than the previous value.
            int v = parseInt(req.getParameter("rememberMeValue"), 8);
            int maxForUnit = REMEMBER_ME_MAX_SECONDS / unitSeconds(getRememberMeUnit());
            if (v < 1) v = 8;
            if (v > maxForUnit) v = maxForUnit;
            setRememberMeValue(v);
        }
    }

    private static int parseInt(String v, int fallback) {
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
