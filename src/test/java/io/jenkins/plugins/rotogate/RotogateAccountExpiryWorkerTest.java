package io.jenkins.plugins.rotogate;

import hudson.model.TaskListener;
import hudson.model.User;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests RotogateAccountExpiryWorker enforcement logic:
 *   - a protected user is skipped even once expired,
 *   - a non-protected expired account is disabled (DISABLE action) rather than deleted,
 *   - a pre-expiry reminder is NOT marked sent when there is no address to send to
 *     (so an address added later still gets reminded).
 *
 * The DELETE action path (user.delete + audit) is not exercised here. The worker runs under
 * the default (Unsecured) authorization strategy, so purgeGlobalMatrix is a safe no-op.
 */
@WithJenkins
public class RotogateAccountExpiryWorkerTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    private final RotogateAccountExpiryWorker worker = new RotogateAccountExpiryWorker();

    private static void setConfigField(String name, Object value) throws Exception {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        Field f = RotogateGlobalConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(cfg, value);
    }

    private static User expiringUser(String id, String expiresAt) throws Exception {
        User u = User.getById(id, true);
        u.addProperty(new RotogateAccountExpiryProperty(expiresAt));
        u.save();
        return u;
    }

    @Test
    public void expiredProtectedUser_isSkipped() throws Exception {
        expiringUser("exp-protected", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        RotogateGlobalConfig.get().setProtectedUsers(java.util.List.of("exp-protected"));

        worker.execute(TaskListener.NULL);

        User u = User.getById("exp-protected", false);
        assertNotNull(u, "protected user must never be deleted/disabled on expiry");
        RotogateAccountExpiryProperty p = u.getProperty(RotogateAccountExpiryProperty.class);
        assertFalse(p.isDisabled(), "protected expired user must not be marked disabled");
    }

    @Test
    public void expiredUser_disableAction_isDisabledNotDeleted() throws Exception {
        expiringUser("exp-disable", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        // default action is DISABLE

        worker.execute(TaskListener.NULL);

        User u = User.getById("exp-disable", false);
        assertNotNull(u, "DISABLE action must not delete the account");
        RotogateAccountExpiryProperty p = u.getProperty(RotogateAccountExpiryProperty.class);
        assertTrue(p.isDisabled(), "an expired account must be disabled under the DISABLE action");
    }

    @Test
    public void notYetExpiredUser_withinReminderWindowButNoEmail_isNotMarkedReminded() throws Exception {
        setConfigField("provisioningExpiryReminderDays", 7);
        expiringUser("exp-remind", Instant.now().plus(3, ChronoUnit.DAYS).toString());

        worker.execute(TaskListener.NULL);

        User u = User.getById("exp-remind", false);
        assertNotNull(u);
        RotogateAccountExpiryProperty p = u.getProperty(RotogateAccountExpiryProperty.class);
        assertFalse(p.isReminderSent(), "with no email address the reminder must stay unmarked so a later address still gets it");
        assertFalse(p.isDisabled(), "a not-yet-expired account must not be disabled");
    }

    @Test
    public void userWithoutExpiryProperty_isUntouched() throws Exception {
        User u = User.getById("exp-none", true);
        u.save();

        worker.execute(TaskListener.NULL);

        assertNotNull(User.getById("exp-none", false), "an account with no expiry property must be left alone");
    }
}
