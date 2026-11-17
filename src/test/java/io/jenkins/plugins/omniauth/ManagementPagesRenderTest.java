package io.jenkins.plugins.omniauth;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Server-side render smoke test for the OmniAuth management pages. Each parameter-less page is
 * GET-ed and must return HTTP 200 — this catches Jelly errors (an unknown tag, a missing view,
 * a bad EL expression) that would otherwise only surface when a human opens the page. It is the
 * headless counterpart to clicking through every page.
 *
 * JavaScript is disabled: we are verifying the Jelly/Stapler render, not client behaviour, and
 * HtmlUnit's JS engine chokes on the modern core bundles. The OmniAuth realm is installed so the
 * pages render their real (realm-active) branch; the default Unsecured strategy leaves the web
 * client with ADMINISTER so the checkPermission guards pass.
 */
@WithJenkins
public class ManagementPagesRenderTest {

    private static final String BASE = "omniauth-management/";

    // Parameter-less pages that forward to a Jelly view (GET renders).
    private static final String[] PAGES = {
        "",                 // management-link index
        "userStatus",
        "staleUsers",
        "userOverview",     // access.jelly
        "accessManagement",
        "accessReview",
        "jitRequests",
        "auditLog",
        "backup",
        "settings",
        "about",
        "notifications",
        "sessions",
        "notificationLog",
        "provisioning",
        "security",
        "protectedUsers",
        "emergencyAccess",
    };

    @Test
    public void allManagementPagesRender(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(new OmniAuthSecurityRealm(false, null));

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setThrowExceptionOnScriptError(false);

        List<String> failures = new ArrayList<>();
        for (String page : PAGES) {
            String url = BASE + page;
            try {
                int status = wc.goTo(url).getWebResponse().getStatusCode();
                if (status != 200) {
                    failures.add(url + " -> HTTP " + status);
                }
            } catch (Exception e) {
                failures.add(url + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            fail("Management pages failed to render:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    public void notificationEventGridTaglibRenders(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(new OmniAuthSecurityRealm(false, null));
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        String html = wc.goTo(BASE + "notifications").getWebResponse().getContentAsString();
        // The three channels share one oau:eventGrid taglib (lib/oau/eventGrid.jelly). Prove it
        // expands: every channel's checkboxes and a native tooltip must be in the server HTML.
        assertTrue(html.contains("name=\"smtpEvents\""), "email event checkboxes should render");
        assertTrue(html.contains("name=\"slackEvents\""), "slack event checkboxes should render");
        assertTrue(html.contains("name=\"teamsEvents\""), "teams event checkboxes should render");
        assertTrue(html.contains("tooltip="), "event labels should carry native tooltips");
    }
}
