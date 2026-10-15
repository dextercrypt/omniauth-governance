package io.jenkins.plugins.omniauth;

import jenkins.model.Jenkins;

import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>Keys are canonicalized through the active realm's {@link hudson.security.IdStrategy}
 * (the native realm is case-insensitive by default), so {@code admin}, {@code Admin} and
 * {@code "ADMIN "} share one counter and lockout cannot be bypassed with case/whitespace.
 * The maps are bounded (size cap + idle-TTL pruning) so a flood of distinct usernames
 * from unauthenticated callers cannot exhaust memory.
 */
public class BruteForceTracker {

    private static final Logger LOGGER = Logger.getLogger(BruteForceTracker.class.getName());

    /** Hard cap on tracked usernames per map — prevents unbounded growth / OOM. */
    private static final int MAX_TRACKED = 10_000;
    /** Idle entries older than this (since last failure) are eligible for pruning. */
    private static final long FAILURE_RETENTION_MS = 60L * 60L * 1000L; // 1 hour

    /** Per-username failure counter plus the time of the most recent failure (for pruning). */
    private static final class Counter {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long lastFailureMs = System.currentTimeMillis();
    }

    private static final ConcurrentHashMap<String, Counter> FAILURES = new ConcurrentHashMap<>();

    /** Users who hit the threshold — persists through successful login until manually cleared. */
    private static final ConcurrentHashMap<String, String> ALERTED = new ConcurrentHashMap<>();

    /** Locked accounts → epoch-millis the lock expires. Absent = not locked. */
    private static final ConcurrentHashMap<String, Long> LOCKED = new ConcurrentHashMap<>();

    private BruteForceTracker() {}

    /**
     * Canonical key for a username, matching how the realm authenticates. Returns {@code null}
     * for null/blank input (callers then no-op).
     */
    private static String key(String username) {
        if (username == null) return null;
        String u = username.trim();
        if (u.isEmpty()) return null;
        try {
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null && j.getSecurityRealm() != null) {
                return j.getSecurityRealm().getUserIdStrategy().keyFor(u);
            }
        } catch (Exception ignored) {
            // fall through to the lower-case default
        }
        return u.toLowerCase(Locale.ROOT);
    }

    public static void recordFailure(String username) {
        String key = key(username);
        if (key == null) return;
        maybePrune();
        Counter counter = FAILURES.computeIfAbsent(key, k -> new Counter());
        int count = counter.count.incrementAndGet();
        counter.lastFailureMs = System.currentTimeMillis();
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        int threshold = (cfg != null) ? cfg.getBruteForceThreshold() : 5;
        LOGGER.fine("Login failure #" + count + " for: " + key);
        if (count == threshold) {
            LOGGER.warning("Brute force threshold (" + threshold + ") reached for user: " + key);
            ALERTED.put(key, Instant.now().toString());
            NotificationService.sendBruteForceAlert(cfg, key, count);
        }
        // Enforcement: at/over threshold, lock the account for the cooldown window.
        // Protected users (admins) are exempt so they can never be locked out. The
        // window slides on each new failure, so continued hammering stays locked.
        if (count >= threshold && lockoutEnabled(cfg) && !isProtected(cfg, key)) {
            long now = System.currentTimeMillis();
            // Atomically set/extend the lock and detect the not-locked -> locked transition,
            // so the admin alert fires exactly once per lock episode even under concurrency.
            boolean[] firstLock = {false};
            LOCKED.compute(key, (k, prev) -> {
                if (prev == null || prev <= now) firstLock[0] = true;
                return now + cooldownMillis(cfg);
            });
            if (firstLock[0]) {
                LOGGER.warning("Account locked by brute-force protection: " + key
                        + " (for " + (cfg != null ? cfg.getBruteForceLockoutMinutes() : 15) + " min)");
                NotificationService.sendAccountLocked(cfg, key,
                        cfg != null ? cfg.getBruteForceLockoutMinutes() : 15);
            }
        }
    }

    public static void recordSuccess(String username) {
        String key = key(username);
        if (key != null) {
            FAILURES.remove(key);
            LOCKED.remove(key);
        }
    }

    /**
     * True when {@code username} is currently locked out and must be rejected before
     * password verification. Disabled lockout and protected users are never locked.
     * An expired lock is cleared (and its failure counter reset) on read, so the user
     * gets a clean slate once the cooldown passes.
     */
    public static boolean isLocked(String username) {
        String key = key(username);
        if (key == null) return false;
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        if (!lockoutEnabled(cfg) || isProtected(cfg, key)) return false;
        Long until = LOCKED.get(key);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            LOCKED.remove(key);
            FAILURES.remove(key);
            return false;
        }
        return true;
    }

    /** Epoch-millis the lock expires, or 0 if not locked. */
    public static long lockedUntil(String username) {
        String key = key(username);
        if (key == null) return 0L;
        Long until = LOCKED.get(key);
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
        String key = key(username);
        if (key == null) return 0;
        Counter c = FAILURES.get(key);
        return c == null ? 0 : c.count.get();
    }

    public static java.util.Map<String, Integer> getAllFailureCounts() {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Counter> e : FAILURES.entrySet()) {
            int v = e.getValue().count.get();
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
        String key = key(username);
        if (key != null) ALERTED.remove(key);
    }

    /** Admin "unlock": clear the lock, the failure counter and the alert flag. */
    public static void clear(String username) {
        String key = key(username);
        if (key == null) return;
        FAILURES.remove(key);
        ALERTED.remove(key);
        LOCKED.remove(key);
    }

    // ----- pruning -----------------------------------------------------------

    /**
     * Opportunistic cleanup, run on failure writes. Removes expired locks and idle
     * failure/alert entries, and — if a map is still over the cap after that — drops the
     * oldest-looking entries so a flood of distinct usernames cannot grow the maps without
     * bound. Only runs its full pass when a map is near the cap, so the common path is cheap.
     */
    private static void maybePrune() {
        if (FAILURES.size() < MAX_TRACKED && LOCKED.size() < MAX_TRACKED && ALERTED.size() < MAX_TRACKED) {
            return;
        }
        long now = System.currentTimeMillis();
        // Expired locks.
        LOCKED.values().removeIf(until -> until == null || until <= now);
        // Idle failure counters (not currently locked).
        for (Iterator<Map.Entry<String, Counter>> it = FAILURES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Counter> e = it.next();
            Long lockUntil = LOCKED.get(e.getKey());
            boolean locked = lockUntil != null && lockUntil > now;
            if (!locked && now - e.getValue().lastFailureMs > FAILURE_RETENTION_MS) {
                it.remove();
            }
        }
        // Hard cap fallback: if still over, drop entries for keys no longer in FAILURES/LOCKED.
        if (ALERTED.size() >= MAX_TRACKED) {
            ALERTED.keySet().removeIf(k -> !FAILURES.containsKey(k) && !LOCKED.containsKey(k));
        }
        // Last resort — if a flood kept everything "fresh", clear the oldest half of FAILURES.
        if (FAILURES.size() >= MAX_TRACKED) {
            FAILURES.entrySet().stream()
                    .sorted((a, b) -> Long.compare(a.getValue().lastFailureMs, b.getValue().lastFailureMs))
                    .limit(FAILURES.size() - MAX_TRACKED / 2)
                    .map(Map.Entry::getKey)
                    .forEach(k -> {
                        if (!LOCKED.containsKey(k)) FAILURES.remove(k);
                    });
        }
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
