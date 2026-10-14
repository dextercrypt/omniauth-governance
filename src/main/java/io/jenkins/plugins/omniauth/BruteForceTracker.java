package io.jenkins.plugins.omniauth;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * In-memory tracker for consecutive login failures per username, with optional
 * enforcement (temporary account lockout).
 *
 * <p>Counting is observational: {@link #recordFailure} increments on every failed
 * login (any channel — web form, HTTP BASIC, API token, CLI), {@link #recordSuccess}
 * resets on a successful one. When enforcement is enabled, reaching the configured
 * threshold also <em>locks</em> the account for a cooldown window: {@link #isLocked}
 * then returns {@code true} and the realm's authentication path rejects the login
 * before the password is even checked — so a correct password cannot get in while
 * locked. Protected users (admins) are never locked, which removes the deliberate
 * admin-lockout denial-of-service.
 */
public class BruteForceTracker {

    private static final Logger LOGGER = Logger.getLogger(BruteForceTracker.class.getName());
    private static final ConcurrentHashMap<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();

    /** Users who hit the threshold — persists through successful login until manually cleared. */
    private static final ConcurrentHashMap<String, String> ALERTED = new ConcurrentHashMap<>();

    /** Locked accounts → epoch-millis the lock expires. Absent = not locked. */
    private static final ConcurrentHashMap<String, Long> LOCKED = new ConcurrentHashMap<>();

    private BruteForceTracker() {}

    public static void recordFailure(String username) {
        if (username == null || username.isEmpty()) return;
        int count = FAILURES.computeIfAbsent(username, k -> new AtomicInteger(0)).incrementAndGet();
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        int threshold = (cfg != null) ? cfg.getBruteForceThreshold() : 5;
        LOGGER.fine("Login failure #" + count + " for: " + username);
        if (count == threshold) {
            LOGGER.warning("Brute force threshold (" + threshold + ") reached for user: " + username);
            ALERTED.put(username, Instant.now().toString());
            NotificationService.sendBruteForceAlert(cfg, username, count);
        }
        // Enforcement: at/over threshold, lock the account for the cooldown window.
        // Protected users (admins) are exempt so they can never be locked out. The
        // window slides on each new failure, so continued hammering stays locked.
        if (count >= threshold && lockoutEnabled(cfg) && !isProtected(cfg, username)) {
            long until = System.currentTimeMillis() + cooldownMillis(cfg);
            LOCKED.put(username, until);
            LOGGER.warning("Account locked by brute-force protection: " + username
                    + " (until " + Instant.ofEpochMilli(until) + ")");
        }
    }

    public static void recordSuccess(String username) {
        if (username != null) {
            FAILURES.remove(username);
            LOCKED.remove(username);
        }
    }

    /**
     * True when {@code username} is currently locked out and must be rejected before
     * password verification. Disabled lockout and protected users are never locked.
     * An expired lock is cleared (and its failure counter reset) on read, so the user
     * gets a clean slate once the cooldown passes.
     */
    public static boolean isLocked(String username) {
        if (username == null || username.isEmpty()) return false;
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        if (!lockoutEnabled(cfg) || isProtected(cfg, username)) return false;
        Long until = LOCKED.get(username);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            LOCKED.remove(username);
            FAILURES.remove(username);
            return false;
        }
        return true;
    }

    /** Epoch-millis the lock expires, or 0 if not locked. */
    public static long lockedUntil(String username) {
        if (username == null) return 0L;
        Long until = LOCKED.get(username);
        return until == null ? 0L : until;
    }

    /** Whole minutes remaining on the lock (rounded up), or 0 if not locked. */
    public static long lockRemainingMinutes(String username) {
        long until = lockedUntil(username);
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) return 0L;
        return (remaining + 59_999L) / 60_000L;
    }

    public static int getFailureCount(String username) {
        AtomicInteger c = FAILURES.get(username);
        return c == null ? 0 : c.get();
    }

    public static java.util.Map<String, Integer> getAllFailureCounts() {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, AtomicInteger> e : FAILURES.entrySet()) {
            int v = e.getValue().get();
            if (v > 0) result.put(e.getKey(), v);
        }
        return result;
    }

    public static java.util.Map<String, String> getAlertedUsers() {
        return new java.util.HashMap<>(ALERTED);
    }

    /** Usernames currently locked (expired entries skipped). */
    public static java.util.Set<String> getLockedUsers() {
        long now = System.currentTimeMillis();
        java.util.Set<String> result = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, Long> e : LOCKED.entrySet()) {
            if (e.getValue() != null && e.getValue() > now) result.add(e.getKey());
        }
        return result;
    }

    public static void clearAlert(String username) {
        ALERTED.remove(username);
    }

    /** Admin "unlock": clear the lock, the failure counter and the alert flag. */
    public static void clear(String username) {
        if (username == null) return;
        FAILURES.remove(username);
        ALERTED.remove(username);
        LOCKED.remove(username);
    }

    // ----- config helpers ----------------------------------------------------

    private static boolean lockoutEnabled(OmniAuthGlobalConfig cfg) {
        return cfg == null || cfg.isBruteForceLockoutEnabled();
    }

    private static boolean isProtected(OmniAuthGlobalConfig cfg, String username) {
        return cfg != null && cfg.isProtected(username);
    }

    private static long cooldownMillis(OmniAuthGlobalConfig cfg) {
        int min = (cfg != null) ? cfg.getBruteForceLockoutMinutes() : 15;
        if (min <= 0) min = 15;
        return min * 60_000L;
    }
}
