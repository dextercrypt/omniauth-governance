package io.jenkins.plugins.rotogate;

import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards that every Rotogate management page is gated by ADMINISTER. These pages carry sensitive
 * data (audit log, sessions, access config), and the management-link framework does NOT gate the
 * sub-URLs on its own — each page must enforce the permission itself (historically via a per-page
 * checkPermission in the do* method, now via {@code <l:layout permission="${app.ADMINISTER}">} once
 * the boilerplate render methods are dropped). This test fails if any page becomes reachable by a
 * non-admin, which is the exact regression the routing refactor could introduce.
 */
@WithJenkins
public class ManagementPagePermissionTest {

    private static final String BASE = "rotogate-management/";

    // The permission-gated GET pages.
    private static final String[] PAGES = {
        "userStatus", "staleUsers", "accessManagement", "accessReview", "jitRequests",
        "auditLog", "backup", "settings", "about", "notifications", "emergencyAccess",
        "notificationLog", "protectedUsers",
    };

    @Test
    public void adminReachesPages_nonAdminIsDenied(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.ADMINISTER).everywhere().to("alice-admin");
        auth.grant(Jenkins.READ).everywhere().to("bob-user"); // logged in, but no ADMINISTER
        j.jenkins.setAuthorizationStrategy(auth);

        JenkinsRule.WebClient admin = j.createWebClient().login("alice-admin");
        admin.getOptions().setJavaScriptEnabled(false);
        for (String page : PAGES) {
            assertEquals(200, admin.goTo(BASE + page).getWebResponse().getStatusCode(),
                    "admin should reach " + page);
        }

        JenkinsRule.WebClient user = j.createWebClient().login("bob-user");
        user.getOptions().setJavaScriptEnabled(false);
        user.getOptions().setThrowExceptionOnFailingStatusCode(false);
        for (String page : PAGES) {
            assertEquals(403, user.goTo(BASE + page).getWebResponse().getStatusCode(),
                    "non-admin must be denied " + page);
        }
    }
}
