package io.jenkins.plugins.omniauth;

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
 * Tests OmniAuthAccountExpiryWorker enforcement logic:
 *   - a protected user is skipped even once expired,
 *   - a non-protected expired account is disabled (DISABLE action) rather than deleted,
 *   - a pre-expiry reminder is NOT marked sent when there is no address to send to
 *     (so an address added later still gets reminded).
 *
 * The DELETE action path (user.delete + audit) is not exercised here. The worker runs under
 * the default (Unsecured) authorization strategy, so purgeGlobalMatrix is a safe no-op.
 */
@WithJenkins
public class OmniAuthAccountExpiryWorkerTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    private final OmniAuthAccountExpiryWorker worker = new OmniAuthAccountExpiryWorker();

    private static void setConfigField(String name, Object value) throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        Field f = OmniAuthGlobalConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(cfg, value);
    }

    private static User expiringUser(String id, String expiresAt) throws Exception {
        User u = User.getById(id, true);
        u.addProperty(new OmniAuthAccountExpiryProperty(expiresAt));
        u.save();
        return u;
    }

    @Test
    public void expiredProtectedUser_isSkipped() throws Exception {
        expiringUser("exp-protected", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        OmniAuthGlobalConfig.get().setProtectedUsers(java.util.List.of("exp-protected"));

        worker.execute(TaskListener.NULL);

        User u = User.getById("exp-protected", false);
        assertNotNull(u, "protected user must never be deleted/disabled on expiry");
        OmniAuthAccountExpiryProperty p = u.getProperty(OmniAuthAccountExpiryProperty.class);
        assertFalse(p.isDisabled(), "protected expired user must not be marked disabled");
    }

    @Test
    public void expiredUser_disableAction_isDisabledNotDeleted() throws Exception {
        expiringUser("exp-disable", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        // default action is DISABLE

        worker.execute(TaskListener.NULL);

        User u = User.getById("exp-disable", false);
        assertNotNull(u, "DISABLE action must not delete the account");
        OmniAuthAccountExpiryProperty p = u.getProperty(OmniAuthAccountExpiryProperty.class);
        assertTrue(p.isDisabled(), "an expired account must be disabled under the DISABLE action");
    }

    @Test
    public void notYetExpiredUser_withinReminderWindowButNoEmail_isNotMarkedReminded() throws Exception {
        setConfigField("provisioningExpiryReminderDays", 7);
        expiringUser("exp-remind", Instant.now().plus(3, ChronoUnit.DAYS).toString());

        worker.execute(TaskListener.NULL);

        User u = User.getById("exp-remind", false);
        assertNotNull(u);
        OmniAuthAccountExpiryProperty p = u.getProperty(OmniAuthAccountExpiryProperty.class);
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
