package io.jenkins.plugins.omniauth;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the newly-wired {@link OmniAuthAuditLog} event types — the coverage pass that
 * extended the audit trail beyond the original curated set to the matrix-permission path,
 * config changes, security-response actions, provisioning lifecycle, and JIT expiry/timeout.
 *
 * <p>Pins that each new {@code log*} method writes a parseable entry with the expected
 * {@code action} discriminator and its key fields, so a future refactor can't silently drop
 * one of them back into the gap list.
 */
public class OmniAuthAuditLogTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    /** Finds the most recent recorded event with the given action, or null. */
    private Map<String, String> latest(String action) {
        for (Map<String, String> e : OmniAuthAuditLog.get().readRecent(500)) {
            if (action.equals(e.get("action"))) return e;
        }
        return null;
    }

    @Test
    public void permissionGrantAndRevokeAreRecorded() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logPermissionGranted("admin", "alice", "global", "global role → DEVELOPER");
        log.logPermissionRevoked("admin", "bob", "my-job", "scoped permissions");

        Map<String, String> g = latest("permission_granted");
        assertNotNull("permission_granted must be recorded", g);
        assertEquals("admin", g.get("by"));
        assertEquals("alice", g.get("user"));
        assertEquals("global", g.get("scope"));
        assertTrue(g.get("detail").contains("DEVELOPER"));

        Map<String, String> r = latest("permission_revoked");
        assertNotNull("permission_revoked must be recorded", r);
        assertEquals("bob", r.get("user"));
        assertEquals("my-job", r.get("scope"));
    }

    @Test
    public void securityResponseActionsAreRecorded() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logSessionRevoked("admin", "mallory");
        log.logBruteForceCleared("admin", "mallory");
        log.logAuditPurged("admin", 3);

        assertEquals("mallory", latest("session_revoked").get("user"));
        assertEquals("mallory", latest("bruteforce_cleared").get("user"));
        assertEquals("3", latest("audit_purged").get("count"));
    }

    @Test
    public void configChangesAreRecorded() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logConfigChanged("admin", "Settings", "bruteForceThreshold: 5 → 9");
        log.logConfigExported("admin");
        log.logConfigImported("admin", "roles=2, assignments=4");
        log.logConfigReverted("admin", "restored point X");

        assertEquals("Settings", latest("config_changed").get("area"));
        assertNotNull(latest("config_exported"));
        assertTrue(latest("config_imported").get("detail").contains("roles=2"));
        assertNotNull(latest("config_reverted"));
    }

    @Test
    public void provisioningLifecycleAndPasswordAreRecorded() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logProvisioningRequested("newuser", "native");
        log.logProvisioningRejected("admin", "newuser");
        log.logActivationResent("admin", "newuser");
        log.logPasswordChanged("newuser");

        assertEquals("native", latest("provisioning_requested").get("authType"));
        assertEquals("newuser", latest("provisioning_rejected").get("user"));
        assertEquals("newuser", latest("activation_resent").get("user"));
        assertEquals("newuser", latest("password_changed").get("user"));
    }

    @Test
    public void userDeletionMarkersAreRecorded() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logUserMarkedForDeletion("admin", "ghost");
        log.logUserUnmarkedForDeletion("admin", "ghost");

        assertEquals("ghost", latest("user_marked_deletion").get("user"));
        assertEquals("ghost", latest("user_unmarked_deletion").get("user"));
    }

    @Test
    public void jitExpiryAndTimeoutAreRecorded() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logJitExpired("carol", "job/deploy");       // active grant elapsed
        log.logJitTimedOut("dave", "job/deploy");        // pending never approved

        assertEquals("carol", latest("jit_expired").get("user"));
        assertEquals("dave", latest("jit_timed_out").get("user"));
    }

    @Test
    public void newestFirstOrderingHolds() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logConfigChanged("admin", "First", "a");
        log.logConfigChanged("admin", "Second", "b");
        List<Map<String, String>> recent = OmniAuthAuditLog.get().readRecent(10);
        assertEquals("Second", recent.get(0).get("area"));
    }

    @Test
    public void pruneOlderThanTrimsStaleLinesKeepsRecent() throws Exception {
        // Write a mix of old (~400d) and fresh lines into the CURRENT month file directly, then
        // prune line-level. (Files are written by hand so the write-path month cleanup doesn't run.)
        java.io.File logsDir = new java.io.File(jenkins.model.Jenkins.get().getRootDir(), "logs");
        logsDir.mkdirs();
        String ym = java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        java.io.File cur = new java.io.File(logsDir, "omniauth-audit-" + ym + ".json");
        String oldTs = java.time.Instant.now().minus(400, java.time.temporal.ChronoUnit.DAYS).toString();
        String newTs = java.time.Instant.now().toString();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(cur, false))) {
            for (int i = 0; i < 50; i++) {
                pw.println("{\"ts\":\"" + oldTs + "\",\"action\":\"login_success\",\"user\":\"ancient\"}");
            }
            pw.println("{\"ts\":\"" + newTs + "\",\"action\":\"login_success\",\"user\":\"fresh\"}");
        }
        OmniAuthAuditLog log = OmniAuthAuditLog.get();

        int removed = log.pruneOlderThan(365);
        assertEquals("the 50 stale lines must be pruned", 50, removed);
        assertTrue("the file stays (it still has a recent line)", cur.exists());
        assertEquals("fresh", log.readRecent(10).get(0).get("user"));
        assertEquals("retention 0 = keep forever (no-op)", 0, log.pruneOlderThan(0));
    }

    @Test
    public void storageInfoReportsEventsAndSize() {
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        log.logConfigChanged("admin", "S1", "a");
        log.logConfigChanged("admin", "S2", "b");
        OmniAuthAuditLog.StorageInfo info = log.getStorageInfo();
        assertTrue("events counted", info.getEvents() >= 2);
        assertTrue("bytes counted", info.getBytes() > 0);
        assertNotNull("human size", info.getHumanSize());
    }

    /**
     * readRecent must return the NEWEST events even when older month files exist — the prior
     * implementation prepended older files and then took the first N, returning the OLDEST events
     * (so the UI showed only months-old entries). This seeds an old-month file alongside today's.
     */
    @Test
    public void readRecentReturnsNewestAcrossMonthFiles() throws Exception {
        java.io.File logsDir = new java.io.File(jenkins.model.Jenkins.get().getRootDir(), "logs");
        logsDir.mkdirs();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM");
        // A prior-month file (within retention) with many entries, written directly so the write-path
        // cleanup never runs. These must NOT crowd out the current month's newer events.
        java.io.File prev = new java.io.File(logsDir,
                "omniauth-audit-" + java.time.YearMonth.now().minusMonths(1).format(fmt) + ".json");
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(prev, false))) {
            for (int i = 0; i < 300; i++) {
                pw.println("{\"ts\":\"2000-01-01T00:00:0" + (i % 10) + "Z\",\"action\":\"login_success\",\"user\":\"ancient\"}");
            }
        }
        // A current-month file with one newer event.
        java.io.File cur = new java.io.File(logsDir,
                "omniauth-audit-" + java.time.YearMonth.now().format(fmt) + ".json");
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(cur, false))) {
            pw.println("{\"ts\":\"" + java.time.Instant.now() + "\",\"action\":\"config_changed\",\"area\":\"NewestArea\",\"user\":\"admin\"}");
        }

        List<Map<String, String>> recent = OmniAuthAuditLog.get().readRecent(5);
        assertEquals("newest event must come first", "config_changed", recent.get(0).get("action"));
        assertEquals("NewestArea", recent.get(0).get("area"));
        assertFalse("oldest entries must not be returned ahead of new ones",
                "ancient".equals(recent.get(0).get("user")));
    }
}
