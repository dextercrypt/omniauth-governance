package io.jenkins.plugins.omniauth;

import hudson.security.AuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link BruteForceTracker}. Needs a JenkinsRule because the tracker reads
 * {@link OmniAuthGlobalConfig} (threshold / lockout flag / protected users) through
 * {@code GlobalConfiguration.all()} and canonicalizes keys through the active realm's
 * {@link hudson.security.IdStrategy}.
 *
 * <p>The tracker keeps its state in static maps that outlive any one JenkinsRule, so the
 * maps are cleared before and after every test via reflection.
 *
 * <p>Default config (fresh per test): threshold = 5, lockout enabled, 15-minute cooldown,
 * no protected users. A {@link HudsonPrivateSecurityRealm} (case-insensitive IdStrategy) is
 * installed so canonicalization is deterministic.
 */
public class BruteForceTrackerTest {

    private static final int DEFAULT_THRESHOLD = 5;
    private static final int MAX_TRACKED = 10_000;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthGlobalConfig cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(new HudsonPrivateSecurityRealm(true, false, null));
        j.jenkins.setAuthorizationStrategy(new AuthorizationStrategy.Unsecured());
        resetTracker();
        cfg = OmniAuthGlobalConfig.get();
    }

    @After
    public void tearDown() throws Exception {
        resetTracker();
    }

    // ───────────────────────── reflection helpers ─────────────────────────────

    private static void resetTracker() throws Exception {
        for (String name : new String[]{"FAILURES", "ALERTED", "LOCKED"}) {
            map(name).clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String name) throws Exception {
        Field f = BruteForceTracker.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, Object>) f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> lockedMap() throws Exception {
        Field f = BruteForceTracker.class.getDeclaredField("LOCKED");
        f.setAccessible(true);
        return (Map<String, Long>) f.get(null);
    }

    private static int failuresSize() throws Exception {
        return map("FAILURES").size();
    }

    private static boolean invokeLockoutEnabled(OmniAuthGlobalConfig c) throws Exception {
        Method m = BruteForceTracker.class.getDeclaredMethod("lockoutEnabled", OmniAuthGlobalConfig.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, c);
    }

    private static void fail(String user, int times) {
        for (int i = 0; i < times; i++) BruteForceTracker.recordFailure(user);
    }

    // ───────────────────────── counting ───────────────────────────────────────

    @Test
    public void recordFailure_incrementsCount() {
        fail("bob", 3);
        assertEquals(3, BruteForceTracker.getFailureCount("bob"));
    }

    @Test
    public void nullAndBlankUsernames_areIgnored() {
        BruteForceTracker.recordFailure(null);
        BruteForceTracker.recordFailure("");
        BruteForceTracker.recordFailure("   ");
        assertEquals(0, BruteForceTracker.getFailureCount(""));
        assertEquals(0, BruteForceTracker.getFailureCount("   "));
    }

    // ───────────────────────── lockout trips AT threshold, not before ──────────

    @Test
    public void notLockedBelowThreshold() {
        fail("bob", DEFAULT_THRESHOLD - 1); // 4 failures
        assertEquals(DEFAULT_THRESHOLD - 1, BruteForceTracker.getFailureCount("bob"));
        assertFalse("must NOT lock before the threshold (regression: no double-count)",
                BruteForceTracker.isLocked("bob"));
    }

    @Test
    public void locksExactlyAtThreshold() {
        fail("bob", DEFAULT_THRESHOLD); // 5th failure trips it
        assertEquals(DEFAULT_THRESHOLD, BruteForceTracker.getFailureCount("bob"));
        assertTrue(BruteForceTracker.isLocked("bob"));
    }

    // ───────────────────────── canonicalization (case / whitespace) ────────────

    @Test
    public void caseAndWhitespaceVariants_shareOneCounter() {
        // Five failures spread across case/whitespace variants of the SAME account.
        BruteForceTracker.recordFailure("admin");
        BruteForceTracker.recordFailure("Admin");
        BruteForceTracker.recordFailure("ADMIN ");
        BruteForceTracker.recordFailure("  admin");
        BruteForceTracker.recordFailure("ADMIN");
        assertEquals("all variants must canonicalize to one counter",
                DEFAULT_THRESHOLD, BruteForceTracker.getFailureCount("admin"));
        assertTrue("lockout must not be bypassable via case/whitespace",
                BruteForceTracker.isLocked("aDmIn"));
    }

    @Test
    public void whitespace_isTrimmed() {
        BruteForceTracker.recordFailure("  spacey  ");
        assertEquals(1, BruteForceTracker.getFailureCount("spacey"));
    }

    // ───────────────────────── protected-user immunity ─────────────────────────

    @Test
    public void protectedUser_isNeverLocked() {
        cfg.setProtectedUsers(Arrays.asList("admin"));
        fail("admin", DEFAULT_THRESHOLD * 2); // hammer well past threshold
        assertFalse("protected users must never be locked", BruteForceTracker.isLocked("admin"));
        assertTrue("protected user has no lock entry",
                BruteForceTracker.getLockedUsers().isEmpty());
    }

    @Test
    public void protectedUser_immunityIsCaseInsensitive() {
        // Protected name stored mixed-case; login attempts arrive lower-case.
        cfg.setProtectedUsers(Arrays.asList("Admin"));
        fail("admin", DEFAULT_THRESHOLD * 2);
        assertFalse("canonicalized protected comparison must still grant immunity",
                BruteForceTracker.isLocked("admin"));
    }

    // ───────────────────────── reset / unlock ──────────────────────────────────

    @Test
    public void recordSuccess_resetsCountAndLock() {
        fail("bob", DEFAULT_THRESHOLD);
        assertTrue(BruteForceTracker.isLocked("bob"));
        BruteForceTracker.recordSuccess("Bob"); // case-insensitive reset
        assertEquals(0, BruteForceTracker.getFailureCount("bob"));
        assertFalse(BruteForceTracker.isLocked("bob"));
    }

    @Test
    public void clear_unlocksAndResetsEverything() {
        fail("bob", DEFAULT_THRESHOLD);
        assertTrue(BruteForceTracker.isLocked("bob"));
        BruteForceTracker.clear("BOB ");
        assertFalse(BruteForceTracker.isLocked("bob"));
        assertEquals(0, BruteForceTracker.getFailureCount("bob"));
        assertFalse(BruteForceTracker.getAlertedUsers().containsKey("bob"));
    }

    // ───────────────────────── accessor maps reflect state ─────────────────────

    @Test
    public void accessorMaps_reflectLockedState() {
        fail("mallory", DEFAULT_THRESHOLD);
        assertTrue(BruteForceTracker.getLockedUsers().contains("mallory"));
        assertTrue(BruteForceTracker.getAlertedUsers().containsKey("mallory"));
        assertEquals(Integer.valueOf(DEFAULT_THRESHOLD),
                BruteForceTracker.getAllFailureCounts().get("mallory"));
    }

    // ───────────────────────── lock expiry (cooldown) ──────────────────────────

    @Test
    public void expiredLock_isClearedOnRead() throws Exception {
        // Inject an already-expired lock directly, bypassing the 1-minute minimum cooldown.
        lockedMap().put("stale", System.currentTimeMillis() - 1000L);
        assertFalse("an expired lock must read as not-locked", BruteForceTracker.isLocked("stale"));
        assertFalse("and must be pruned from the map on read",
                lockedMap().containsKey("stale"));
    }

    @Test
    public void lockedUntilAndRemaining_whileLocked() {
        fail("bob", DEFAULT_THRESHOLD);
        long until = BruteForceTracker.lockedUntil("bob");
        assertTrue("lockedUntil is in the future", until > System.currentTimeMillis());
        long remaining = BruteForceTracker.lockRemainingMinutes("bob");
        assertTrue("remaining minutes within the configured 15-min window",
                remaining >= 1 && remaining <= 15);
    }

    @Test
    public void lockedUntil_zeroWhenNotLocked() {
        assertEquals(0L, BruteForceTracker.lockedUntil("nobody"));
        assertEquals(0L, BruteForceTracker.lockRemainingMinutes("nobody"));
    }

    // ───────────────────────── lockout-enabled gate ────────────────────────────

    @Test
    public void lockoutDisabled_neverLocks() {
        cfg.setBruteForceLockoutEnabled(false);
        fail("bob", DEFAULT_THRESHOLD * 2);
        assertFalse("with lockout disabled, no account is locked", BruteForceTracker.isLocked("bob"));
        assertTrue(BruteForceTracker.getLockedUsers().isEmpty());
    }

    @Test
    public void lockoutEnabled_failsSafeWhenConfigNull() throws Exception {
        // The private gate must treat a missing config as ENABLED (fail safe / closed).
        assertTrue("null config must fail safe to lockout ENABLED", invokeLockoutEnabled(null));
        cfg.setBruteForceLockoutEnabled(true);
        assertTrue(invokeLockoutEnabled(cfg));
        cfg.setBruteForceLockoutEnabled(false);
        assertFalse(invokeLockoutEnabled(cfg));
    }

    // ───────────────────────── single-alert / single-lock-notification ─────────

    @Test
    public void alertsAndLockNotification_fireExactlyOncePerEpisode() {
        try (MockedStatic<NotificationService> ns = mockStatic(NotificationService.class)) {
            // Threshold is 5; hammer well past it.
            fail("mallory", DEFAULT_THRESHOLD + 3); // 8 failures
            assertTrue(BruteForceTracker.isLocked("mallory"));
            ns.verify(() -> NotificationService.sendBruteForceAlert(any(), anyString(), anyInt()),
                    times(1));
            ns.verify(() -> NotificationService.sendAccountLocked(any(), anyString(), anyInt()),
                    times(1));
        }
    }

    // ───────────────────────── bounded map / DoS cap ───────────────────────────

    @Test
    public void distinctUsernameFlood_doesNotGrowUnbounded() throws Exception {
        // A flood of distinct usernames from unauthenticated callers must not exhaust memory:
        // the FAILURES map is capped at MAX_TRACKED (regression for the fixed DoS).
        int flood = MAX_TRACKED + 250; // push just past the cap to trigger pruning
        for (int i = 0; i < flood; i++) {
            BruteForceTracker.recordFailure("flood-user-" + i);
        }
        int size = failuresSize();
        assertTrue("map must stay bounded at/under the cap, was " + size, size <= MAX_TRACKED);
        assertTrue("pruning must have dropped entries (flood was " + flood + ", size " + size + ")",
                size < flood);
    }
}
