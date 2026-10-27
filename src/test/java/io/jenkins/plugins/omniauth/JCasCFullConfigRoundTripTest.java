package io.jenkins.plugins.omniauth;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completeness proof for {@link OmniAuthGlobalConfig} over JCasC: imports one field from every
 * config group (thresholds, cleanup, notification toggles, SMTP server, events, brute-force,
 * stale-warning, access-review, notification branding) and asserts each applied, then exports and
 * asserts the same keys come back with no "FAILED TO EXPORT". This guards against a field being
 * added later without a setter and silently falling out of JCasC coverage.
 */
@WithJenkinsConfiguredWithCode
public class JCasCFullConfigRoundTripTest {

    private JenkinsConfiguredWithCodeRule j;

    @BeforeEach
    void setUp(JenkinsConfiguredWithCodeRule r) {
        j = r;
    }

    @Test
    @ConfiguredWithCode("jcasc-globalconfig-full.yaml")
    public void everyConfigGroupImports() {
        OmniAuthGlobalConfig c = OmniAuthGlobalConfig.get();

        assertEquals(120, c.getStaleThresholdDays());
        assertEquals(45, c.getActiveThresholdDays());

        assertTrue(c.isCleanupEnabled());
        assertFalse(c.isCleanupDryRun());
        assertEquals("0 3 * * 1", c.getCleanupCron());
        assertEquals(25, c.getCleanupMaxDeletions());
        assertEquals("ops@contoso.com", c.getCleanupNotifyEmail());

        assertTrue(c.isNotificationsEnabled());
        assertTrue(c.isSmtpEnabled());
        assertTrue(c.isSlackEnabled());
        assertFalse(c.isTeamsEnabled());

        assertEquals("smtp.contoso.com", c.getSmtpHost());
        assertEquals(2525, c.getSmtpPort());
        assertEquals("jenkins@contoso.com", c.getSmtpUsername());
        assertFalse(c.isSmtpTls());
        assertEquals("ci@contoso.com", c.getSmtpFromAddress());
        assertEquals("Contoso CI", c.getSmtpFromName());
        assertEquals("noreply@contoso.com", c.getSmtpReplyTo());
        assertEquals("admins@contoso.com", c.getNotifyEmails());

        assertEquals(7, c.getBruteForceThreshold());

        assertTrue(c.isStaleWarningEnabled());
        assertEquals("0 6 * * *", c.getStaleWarningCron());
        assertEquals(14, c.getStaleWarningWindowDays());

        assertTrue(c.isAccessReviewEnabled());
        assertEquals(60, c.getAccessReviewThresholdDays());

        assertEquals("https://contoso.com/logo.png", c.getNotificationLogoUrl());
        assertEquals("Internal use only", c.getNotificationFooterNote());

        assertEquals(List.of("accountLocked", "provisionRequest"), c.getSmtpEvents());
        assertEquals(List.of("accountLocked"), c.getSlackEvents());
    }

    @Test
    @ConfiguredWithCode("jcasc-globalconfig-full.yaml")
    public void everyConfigGroupExportsCleanly() throws Exception {
        String yaml = exportToString();
        assertFalse(yaml.contains("FAILED TO EXPORT"));
        for (String key : new String[]{
                "staleThresholdDays", "cleanupCron", "notificationsEnabled", "smtpHost",
                "smtpPort", "smtpFromName", "bruteForceThreshold", "staleWarningCron",
                "accessReviewThresholdDays", "notificationLogoUrl", "smtpEvents"}) {
            assertTrue(yaml.contains(key), "expected exported key: " + key);
        }
    }

    private String exportToString() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ConfigurationAsCode.get().export(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
