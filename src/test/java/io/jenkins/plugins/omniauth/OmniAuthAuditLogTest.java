package io.jenkins.plugins.omniauth;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
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
}
