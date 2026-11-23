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

    private static int parseInt(String v, int fallback) {
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
