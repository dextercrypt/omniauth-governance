package io.jenkins.plugins.omniauth;

import hudson.model.TaskListener;
import hudson.model.User;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the SOS Mode state machine ({@link OmniAuthSos}) and its backstop worker:
 * file-driven arming, the 30-minute window, feature toggle, failed-attempt lockout, manual
 * deactivation, code-based identity resolution, and ADMINISTER repair.
 */
@WithJenkins
public class OmniAuthSosTest {

    private JenkinsRule j;

    private File trigger;

    @BeforeEach
    public void reset(JenkinsRule rule) throws Exception {
        j = rule;
        // The realm gate requires OmniAuth to be the active Security Realm for SOS to arm.
        j.jenkins.setSecurityRealm(new OmniAuthSecurityRealm(false, null));
        // Clear any leftover trigger + static state between tests.
        OmniAuthSos.deactivate("test-setup");
        trigger = new File(j.jenkins.getRootDir(), OmniAuthSos.TRIGGER_FILE);
    }

    private void arm() throws Exception {
        assertTrue(trigger.createNewFile() || trigger.exists());
        trigger.setLastModified(System.currentTimeMillis());
    }

    @Test
    public void notArmed_withoutTriggerFile() {
        assertFalse(OmniAuthSos.isArmed());
        assertEquals(0, OmniAuthSos.minutesRemaining());
    }

    @Test
    public void armed_whenFreshTriggerPresent() throws Exception {
        arm();
        assertTrue(OmniAuthSos.isArmed());
        long mins = OmniAuthSos.minutesRemaining();
        assertTrue(mins >= 29 && mins <= 30, "window should be close to 30 minutes, was " + mins);
    }

    @Test
    public void disabledInConfig_suppressesArming() throws Exception {
        OmniAuthGlobalConfig.get().setSosEnabled(false);
        arm();
        assertFalse(OmniAuthSos.isArmed(), "feature disabled → not armed even with trigger");
        OmniAuthGlobalConfig.get().setSosEnabled(true);
        assertTrue(OmniAuthSos.isArmed());
    }

    @Test
    public void staleTrigger_isExpiredByWorker() throws Exception {
        arm();
        trigger.setLastModified(System.currentTimeMillis() - 31L * 60 * 1000);
        assertFalse(OmniAuthSos.isArmed(), "a trigger older than the window is not armed");
        assertTrue(OmniAuthSos.triggerIsStale());

        new OmniAuthSosWorker().execute(TaskListener.NULL);
        assertFalse(trigger.exists(), "worker should delete the stale trigger");
        assertFalse(OmniAuthSos.isArmed());
    }

    @Test
    public void resolveAndConsume_identifiesAdminFromCode() throws Exception {
        User admin = User.getById("sos-admin", true);
        OmniAuthRecoveryCodesProperty prop = new OmniAuthRecoveryCodesProperty();
        List<String> codes = prop.regenerate();
        admin.addProperty(prop);
        admin.save();

        User resolved = OmniAuthSos.resolveAndConsume(codes.get(2));
        assertNotNull(resolved, "a matching code must resolve to its owner");
        assertEquals("sos-admin", resolved.getId());

        // Consumed: the same code no longer resolves.
        assertNull(OmniAuthSos.resolveAndConsume(codes.get(2)));
        // A different, still-unused code of the same admin still works.
        assertNotNull(OmniAuthSos.resolveAndConsume(codes.get(5)));
    }

    @Test
    public void resolveAndConsume_returnsNullForUnknownCode() throws Exception {
        User admin = User.getById("sos-admin2", true);
        OmniAuthRecoveryCodesProperty prop = new OmniAuthRecoveryCodesProperty();
        prop.regenerate();
        admin.addProperty(prop);
        admin.save();
        assertNull(OmniAuthSos.resolveAndConsume("NOPE-NOPE-NOPE"));
    }

    @Test
    public void fiveFailedAttempts_locksAndDisarms() throws Exception {
        arm();
        OmniAuthSos.noteArmedIfNeeded();
        assertTrue(OmniAuthSos.isArmed());

        boolean locked = false;
        for (int i = 0; i < OmniAuthSos.MAX_FAILED_ATTEMPTS; i++) {
            locked = OmniAuthSos.recordFailedAttempt();
        }
        assertTrue(locked, "the fifth failure should trip the lockout");
        assertFalse(trigger.exists(), "lockout deletes the trigger → disarmed");
        assertFalse(OmniAuthSos.isArmed());
    }

    @Test
    public void deactivate_deletesTrigger() throws Exception {
        arm();
        assertTrue(OmniAuthSos.isArmed());
        OmniAuthSos.deactivate("admin1");
        assertFalse(trigger.exists());
        assertFalse(OmniAuthSos.isArmed());
    }

    @Test
    public void notArmed_whenRealmIsNotOmniAuth() throws Exception {
        // Realm gate: a Jenkins that does not use OmniAuth to log in must never arm SOS,
        // even with a fresh trigger file — this closes idle / abandoned-install backdoors.
        j.jenkins.setSecurityRealm(hudson.security.SecurityRealm.NO_AUTHENTICATION);
        arm();
        assertFalse(OmniAuthSos.isArmed(), "SOS must stay dark when OmniAuth is not the active realm");

        // Restoring OmniAuth as the realm re-enables it with the same trigger still in place.
        j.jenkins.setSecurityRealm(new OmniAuthSecurityRealm(false, null));
        assertTrue(OmniAuthSos.isArmed());
    }

    @Test
    public void grantAdminister_repairsAdminAccess() throws Exception {
        j.jenkins.setAuthorizationStrategy(new OmniAuthAuthorizationStrategy());
        User.getById("sos-admin3", true).save();

        assertTrue(OmniAuthSos.grantAdminister("sos-admin3"));

        OmniAuthAuthorizationStrategy strat = (OmniAuthAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
        boolean hasAdmin = strat.getGrantedPermissionEntries()
                .getOrDefault(Jenkins.ADMINISTER, java.util.Collections.emptySet())
                .stream().anyMatch(pe -> "sos-admin3".equals(pe.getSid()));
        assertTrue(hasAdmin, "sos-admin3 should now hold global ADMINISTER");
    }

    @Test
    public void grantAdminister_worksWithStockMatrixStrategy() throws Exception {
        // #1: SOS restores admin into any matrix-based strategy, not only OmniAuth's.
        hudson.security.GlobalMatrixAuthorizationStrategy stock =
                new hudson.security.GlobalMatrixAuthorizationStrategy();
        j.jenkins.setAuthorizationStrategy(stock);
        User.getById("sos-admin4", true).save();

        assertTrue(OmniAuthSos.grantAdminister("sos-admin4"));

        boolean hasAdmin = ((hudson.security.GlobalMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy())
                .getGrantedPermissionEntries()
                .getOrDefault(Jenkins.ADMINISTER, java.util.Collections.emptySet())
                .stream().anyMatch(pe -> "sos-admin4".equals(pe.getSid()));
        assertTrue(hasAdmin, "sos-admin4 should hold ADMINISTER in the stock matrix");
    }

    @Test
    public void grantAdminister_returnsFalseForNonMatrixStrategy() throws Exception {
        // A non-matrix strategy cannot be granted into → false (caller surfaces the #2 message).
        j.jenkins.setAuthorizationStrategy(new hudson.security.FullControlOnceLoggedInAuthorizationStrategy());
        assertFalse(OmniAuthSos.grantAdminister("whoever"));
    }
}
