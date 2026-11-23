package io.jenkins.plugins.rotogate.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the form-binding {@code updateFrom} methods on the per-area config holders — the path the
 * save handlers now take instead of hand-assembling JSON. Verifies the semantics that path has to
 * preserve: checkbox presence (present = on, absent = off), partial text fields (an absent parameter
 * leaves the stored value untouched), the pasted {@code host:port} split, keeping an existing
 * password when a blank one is submitted, the from-name default and the cleanup dry-run rule.
 *
 * <p>Needs {@link JenkinsRule} only so {@code Secret.fromString} has a confidential store; the holders
 * themselves are plain objects and the request is a Mockito stub.
 */
@WithJenkins
class ConfigUpdateFromTest {

    @SuppressWarnings("unused")
    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        this.j = rule;
    }

    /** A request whose getParameter/getParameterValues answer from the given maps (null otherwise). */
    private static StaplerRequest2 req(Map<String, String> single, Map<String, String[]> multi) {
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getParameter(anyString())).thenAnswer(inv -> single.get(inv.<String>getArgument(0)));
        when(req.getParameterValues(anyString())).thenAnswer(inv -> multi.get(inv.<String>getArgument(0)));
        return req;
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ── NotificationConfig ────────────────────────────────────────────────────

    @Test
    void notification_checkboxPresence_andPartialText() {
        NotificationConfig c = new NotificationConfig();
        c.setSmtpUsername("existing-user"); // not in the form below → must survive (partial)

        Map<String, String> p = params(
                "notificationsEnabled", "on",
                "smtpEnabled", "on",
                "smtpHost", "mail.example.com",
                "smtpPort", "587",
                "smtpFromAddress", "from@example.com");
        c.updateFrom(req(p, Map.of()));

        assertTrue(c.isNotificationsEnabled(), "checkbox present → on");
        assertTrue(c.isSmtpEnabled());
        assertFalse(c.isSlackEnabled(), "checkbox absent → off");
        assertFalse(c.isSmtpTls(), "smtpTls absent → off (checkbox presence, not partial)");
        assertEquals("mail.example.com", c.getSmtpHost());
        assertEquals(587, c.getSmtpPort());
        assertEquals("from@example.com", c.getSmtpFromAddress());
        assertEquals("existing-user", c.getSmtpUsername(), "field absent from form must be left untouched");
    }

    @Test
    void notification_splitsPastedHostPort() {
        NotificationConfig c = new NotificationConfig();
        c.updateFrom(req(params("smtpHost", "mail.example.com:2525"), Map.of()));
        assertEquals("mail.example.com", c.getSmtpHost());
        assertEquals(2525, c.getSmtpPort());
    }

    @Test
    void notification_blankPasswordKeepsExisting_emptyFromNameDefaults() {
        NotificationConfig c = new NotificationConfig();
        c.setSmtpPassword(hudson.util.Secret.fromString("keep-me"));

        c.updateFrom(req(params("smtpPassword", "", "smtpFromName", ""), Map.of()));

        assertEquals("keep-me", c.getSmtpPassword().getPlainText(), "blank password submission keeps the stored secret");
        assertEquals("Jenkins Rotogate", c.getSmtpFromName(), "empty from-name falls back to the default");
    }

    @Test
    void notification_newPasswordOverwrites() {
        NotificationConfig c = new NotificationConfig();
        c.setSmtpPassword(hudson.util.Secret.fromString("old"));
        c.updateFrom(req(params("smtpPassword", "new-pw"), Map.of()));
        assertEquals("new-pw", c.getSmtpPassword().getPlainText());
    }

    @Test
    void notification_eventListsReplacedFromMultiValues() {
        NotificationConfig c = new NotificationConfig();
        Map<String, String[]> multi = new HashMap<>();
        multi.put("smtpEvents", new String[]{"cleanup", "bruteForce"});
        multi.put("slackEvents", new String[]{"configChanged"});
        // teamsEvents omitted → replaced with empty (the page always submits its event groups)
        c.updateFrom(req(Map.of(), multi));

        assertEquals(java.util.List.of("cleanup", "bruteForce"), c.getSmtpEvents());
        assertEquals(java.util.List.of("configChanged"), c.getSlackEvents());
        assertTrue(c.getTeamsEvents().isEmpty());
    }

    // ── SecurityConfig (fields split across two pages) ────────────────────────

    @Test
    void security_bruteForceAndSos_areIndependentPartialUpdates() {
        SecurityConfig c = new SecurityConfig();
        c.setSosEnabled(true); // owned by the Settings page, must survive a Notifications-page save

        // Notifications page submits only the brute-force controls.
        c.updateBruteForceFrom(req(params("bruteForceThreshold", "9"), Map.of()));
        assertEquals(9, c.getBruteForceThreshold());
        assertFalse(c.isBruteForceLockoutEnabled(), "lockout checkbox absent → off");
        assertTrue(c.isSosEnabled(), "SOS is not on this page → untouched");

        // Settings page submits only the SOS switch.
        c.setBruteForceThreshold(3);
        c.updateSosFrom(req(params(), Map.of()));
        assertFalse(c.isSosEnabled(), "SOS checkbox absent → off");
        assertEquals(3, c.getBruteForceThreshold(), "brute-force is not on this page → untouched");
    }

    // ── LifecycleConfig ───────────────────────────────────────────────────────

    @Test
    void lifecycle_settings_cleanupDryRunForcedWhenDisabled_andPartial() {
        LifecycleConfig c = new LifecycleConfig();
        c.setStaleWarningEnabled(true); // owned by a different page → must survive

        // cleanup enabled but dryRun unchecked → dryRun false
        c.updateSettingsFrom(req(params(
                "staleThresholdDays", "120",
                "cleanupEnabled", "on"), Map.of()));
        assertEquals(120, c.getStaleThresholdDays());
        assertTrue(c.isCleanupEnabled());
        assertFalse(c.isCleanupDryRun());
        assertTrue(c.isStaleWarningEnabled(), "stale-warning not on this page → untouched");

        // cleanup disabled → dryRun forced true regardless of its checkbox
        c.updateSettingsFrom(req(params(), Map.of()));
        assertFalse(c.isCleanupEnabled());
        assertTrue(c.isCleanupDryRun(), "disabled cleanup forces dry-run on");
    }

    @Test
    void lifecycle_staleWarning_checkboxPresence() {
        LifecycleConfig c = new LifecycleConfig();
        c.updateStaleWarningFrom(req(params("staleWarningEnabled", "on", "staleWarningWindowDays", "21"), Map.of()));
        assertTrue(c.isStaleWarningEnabled());
        assertEquals(21, c.getStaleWarningWindowDays());

        c.updateStaleWarningFrom(req(params(), Map.of()));
        assertFalse(c.isStaleWarningEnabled());
    }

    // ── LoginBrandingConfig ───────────────────────────────────────────────────

    @Test
    void branding_partialUpdate() {
        LoginBrandingConfig c = new LoginBrandingConfig();
        c.setLoginHeading("Keep heading");
        c.updateFrom(req(params("loginButler", "nerd", "loginBackground", "starburst"), Map.of()));
        assertEquals("nerd", c.getLoginButler());
        assertEquals("starburst", c.getLoginBackground());
        assertEquals("Keep heading", c.getLoginHeading(), "heading not submitted → untouched");
    }

    // ── ProvisioningConfig ────────────────────────────────────────────────────

    @Test
    void provisioning_bindsAllFields_andFloors() {
        ProvisioningConfig c = new ProvisioningConfig();
        c.updateFrom(req(params(
                "provisioningEnabled", "on",
                "provisioningAllowNative", "on",
                "provisioningBreakerMax", "0",   // non-positive → floored to default 100
                "activationLinkTtlHours", "48"), Map.of()));

        assertTrue(c.isProvisioningEnabled());
        assertTrue(c.isProvisioningAllowNative());
        assertFalse(c.isProvisioningAllowEntra(), "checkbox absent → off");
        assertEquals(100, c.getProvisioningBreakerMax(), "non-positive breaker max floors to 100");
        assertEquals(48, c.getActivationLinkTtlHours());
    }
}
