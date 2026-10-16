package io.jenkins.plugins.omniauth;

import hudson.model.User;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.Assert.*;

/**
 * Tests for StaleUserCleanupWork — the cron-field matcher (pure logic) and the
 * cleanup-sweep safety guards (null lastLogin never deleted, unparseable timestamp
 * skipped without aborting the sweep, and the maxDeletions cap honoured).
 *
 * The cron tests call the package-private static matchers directly. The sweep tests
 * use JenkinsRule to create real users (StaleUserCleanupWork#runCleanup iterates
 * User.getAll()), then drive runCleanup() and assert which accounts survived.
 *
 * OmniAuthGlobalConfig exposes no setters for the cleanup knobs (they are only set via
 * configure(json)), so the sweep tests set the private fields reflectively — exactly the
 * values an admin would configure on the Notifications page.
 */
public class StaleUserCleanupWorkTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    // ─── Cron field matcher (pure logic) ──────────────────────────────────────

    @Test
    public void fieldMatches_wildcard_matchesAnyValue() {
        assertTrue(StaleUserCleanupWork.fieldMatches("*", 0));
        assertTrue(StaleUserCleanupWork.fieldMatches("*", 59));
    }

    @Test
    public void fieldMatches_exactValue_matchesOnlyThatNumber() {
        assertTrue(StaleUserCleanupWork.fieldMatches("15", 15));
        assertFalse(StaleUserCleanupWork.fieldMatches("15", 16));
    }

    @Test
    public void fieldMatches_commaList_matchesAnyListedValue() {
        assertTrue(StaleUserCleanupWork.fieldMatches("1,15,30", 1));
        assertTrue(StaleUserCleanupWork.fieldMatches("1,15,30", 15));
        assertTrue(StaleUserCleanupWork.fieldMatches("1,15,30", 30));
        assertFalse(StaleUserCleanupWork.fieldMatches("1,15,30", 16));
    }

    @Test
    public void fieldMatches_commaListWithWhitespace_isTrimmed() {
        assertTrue(StaleUserCleanupWork.fieldMatches("1, 15 , 30", 15));
    }

    @Test
    public void fieldMatches_range_matchesInclusiveBounds() {
        assertTrue(StaleUserCleanupWork.fieldMatches("1-5", 1));
        assertTrue(StaleUserCleanupWork.fieldMatches("1-5", 3));
        assertTrue(StaleUserCleanupWork.fieldMatches("1-5", 5));
        assertFalse(StaleUserCleanupWork.fieldMatches("1-5", 0));
        assertFalse(StaleUserCleanupWork.fieldMatches("1-5", 6));
    }

    @Test
    public void fieldMatches_step_matchesMultiples() {
        assertTrue(StaleUserCleanupWork.fieldMatches("*/5", 0));
        assertTrue(StaleUserCleanupWork.fieldMatches("*/5", 10));
        assertFalse(StaleUserCleanupWork.fieldMatches("*/5", 7));
    }

    @Test
    public void fieldMatches_stepZero_doesNotDivideByZero() {
        // n must be > 0; "*/0" must never throw and never match.
        assertFalse(StaleUserCleanupWork.fieldMatches("*/0", 0));
        assertFalse(StaleUserCleanupWork.fieldMatches("*/0", 5));
    }

    @Test
    public void fieldMatches_commaListContainingRange_matchesEither() {
        assertTrue(StaleUserCleanupWork.fieldMatches("1-5,20", 3));
        assertTrue(StaleUserCleanupWork.fieldMatches("1-5,20", 20));
        assertFalse(StaleUserCleanupWork.fieldMatches("1-5,20", 10));
    }

    @Test
    public void fieldMatches_garbage_returnsFalse() {
        assertFalse(StaleUserCleanupWork.fieldMatches("abc", 3));
        assertFalse(StaleUserCleanupWork.fieldMatches("", 3));
    }

    @Test
    public void cronMatches_allWildcards_matchesNow() {
        // Five wildcards match regardless of the current clock.
        assertTrue(StaleUserCleanupWork.cronMatches("* * * * *"));
    }

    @Test
    public void cronMatches_collapsesRepeatedWhitespace() {
        assertTrue(StaleUserCleanupWork.cronMatches("*   *  *    *  *"));
    }

    @Test
    public void cronMatches_nullOrBlank_returnsFalse() {
        assertFalse(StaleUserCleanupWork.cronMatches(null));
        assertFalse(StaleUserCleanupWork.cronMatches(""));
        assertFalse(StaleUserCleanupWork.cronMatches("    "));
    }

    @Test
    public void cronMatches_wrongFieldCount_returnsFalse() {
        assertFalse(StaleUserCleanupWork.cronMatches("* * * *"));      // 4 fields
        assertFalse(StaleUserCleanupWork.cronMatches("* * * * * *"));  // 6 fields
    }

    // ─── Cleanup-sweep safety (JenkinsRule) ───────────────────────────────────

    /** Sets the three cleanup knobs reflectively (no public setters exist). */
    private void configureCleanup(int thresholdDays, int maxDeletions, boolean dryRun) throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        setField(cfg, "staleThresholdDays", thresholdDays);
        setField(cfg, "cleanupMaxDeletions", maxDeletions);
        setField(cfg, "cleanupDryRun", dryRun);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = OmniAuthGlobalConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static User userWithLastLogin(String id, String lastLoginAt) throws Exception {
        User u = User.getById(id, true);
        u.addProperty(new LastLoginProperty(lastLoginAt));
        u.save();
        return u;
    }

    private static String daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).toString();
    }

    @Before
    public void sanityNoStaleRealm() {
        // No security realm needed for runCleanup; left intentionally default.
    }

    @Test
    public void runCleanup_nullLastLogin_neverDeletesTheAccount() throws Exception {
        // A freshly provisioned account that has never logged in has no LastLoginProperty.
        User newbie = User.getById("sweep-newbie", true);
        newbie.save();
        assertNull("precondition: no recorded login",
                OmniAuthUserProperty.resolveLastLogin(null, newbie.getProperty(LastLoginProperty.class)));

        configureCleanup(1, 50, false); // aggressive 1-day threshold, live mode
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        assertNotNull("never-logged-in account must survive the sweep",
                User.getById("sweep-newbie", false));
    }

    @Test
    public void runCleanup_staleAccount_isDeletedInLiveMode() throws Exception {
        userWithLastLogin("sweep-oldie", daysAgo(100));

        configureCleanup(30, 50, false);
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        assertNull("a 100-day-idle account past the 30-day threshold must be deleted",
                User.getById("sweep-oldie", false));
    }

    @Test
    public void runCleanup_recentAccount_isKept() throws Exception {
        userWithLastLogin("sweep-fresh", daysAgo(2));

        configureCleanup(30, 50, false);
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        assertNotNull("a 2-day-idle account well under the threshold must be kept",
                User.getById("sweep-fresh", false));
    }

    @Test
    public void runCleanup_unparseableTimestamp_skipsUserButDoesNotAbortSweep() throws Exception {
        userWithLastLogin("sweep-garbage", "not-a-real-timestamp");
        userWithLastLogin("sweep-stale-sibling", daysAgo(100));

        configureCleanup(30, 50, false);
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        assertNotNull("the corrupt-timestamp user is skipped, not deleted",
                User.getById("sweep-garbage", false));
        assertNull("one corrupt timestamp must not abort the whole sweep — the stale sibling is still deleted",
                User.getById("sweep-stale-sibling", false));
    }

    @Test
    public void runCleanup_honoursMaxDeletionsCap() throws Exception {
        userWithLastLogin("sweep-cap-1", daysAgo(100));
        userWithLastLogin("sweep-cap-2", daysAgo(100));
        userWithLastLogin("sweep-cap-3", daysAgo(100));
        userWithLastLogin("sweep-cap-4", daysAgo(100));

        configureCleanup(30, 2, false); // cap = 2
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        int surviving = 0;
        for (int i = 1; i <= 4; i++) {
            if (User.getById("sweep-cap-" + i, false) != null) surviving++;
        }
        assertEquals("with a cap of 2 of 4 stale users, exactly 2 should remain", 2, surviving);
    }

    @Test
    public void runCleanup_dryRun_deletesNothing() throws Exception {
        userWithLastLogin("sweep-dry", daysAgo(100));

        configureCleanup(30, 50, true); // dry run
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        assertNotNull("dry-run mode must not delete anything",
                User.getById("sweep-dry", false));
    }

    @Test
    public void runCleanup_protectedUser_isSkippedEvenWhenStale() throws Exception {
        userWithLastLogin("sweep-protected", daysAgo(100));
        OmniAuthGlobalConfig.get().setProtectedUsers(java.util.List.of("sweep-protected"));

        configureCleanup(30, 50, false);
        StaleUserCleanupWork.runCleanup(OmniAuthGlobalConfig.get());

        assertNotNull("a protected user must never be auto-deleted",
                User.getById("sweep-protected", false));
    }
}
