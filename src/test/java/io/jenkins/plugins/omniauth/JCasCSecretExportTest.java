package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security + round-trip for the notification secrets (SMTP password, Slack/Teams webhooks).
 *
 * <p>These are {@code hudson.util.Secret} fields with Secret-typed getters/setters, so JCasC
 * treats them exactly like {@code clientSecret}: on export it emits Jenkins ciphertext
 * ({@code {AQAA...}}), never plaintext; on import it accepts a value (and, in production, resolves
 * {@code ${ENV}} the same way). This test proves (1) no plaintext ever reaches the exported YAML,
 * (2) the secrets ARE exported (encrypted) so a backup is complete, and (3) they import back.
 */
@WithJenkinsConfiguredWithCode
public class JCasCSecretExportTest {

    private JenkinsConfiguredWithCodeRule j;

    @BeforeEach
    void setUp(JenkinsConfiguredWithCodeRule r) {
        j = r;
    }

    private static final String SLACK = "https://hooks.slack.com/services/T000/B000/XXXXsecretXXXX";
    private static final String TEAMS = "https://outlook.office.com/webhook/TEAMSsecretTOKEN";
    private static final String SMTP_PW = "sup3r-s3cret-smtp-passw0rd";

    @Test
    public void notificationSecretsExportEncryptedNeverPlaintext() throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        setSecret(cfg, "slackWebhookUrl", SLACK);
        setSecret(cfg, "teamsWebhookUrl", TEAMS);
        setSecret(cfg, "smtpPassword", SMTP_PW);
        cfg.save();

        String yaml = exportToString();

        // (1) No plaintext anywhere.
        assertFalse(yaml.contains(SLACK), "Slack webhook leaked in plaintext");
        assertFalse(yaml.contains(TEAMS), "Teams webhook leaked in plaintext");
        assertFalse(yaml.contains(SMTP_PW), "SMTP password leaked in plaintext");

        // (2) They ARE exported now (so backup is complete) — as encrypted attributes.
        assertTrue(yaml.contains("slackWebhookUrl"), "slackWebhookUrl should be exported");
        assertTrue(yaml.contains("teamsWebhookUrl"), "teamsWebhookUrl should be exported");
        assertTrue(yaml.contains("smtpPassword"), "smtpPassword should be exported");
        // Jenkins Secret ciphertext is wrapped in braces, e.g. {AQAA....==}.
        assertTrue(yaml.matches("(?s).*slackWebhookUrl:\\s*\"\\{.*"), "exported secret should be Jenkins ciphertext");
    }

    @Test
    public void notificationSecretsRoundTripFromYaml() throws Exception {
        ConfigurationAsCode.get().configure(
                getClass().getResource("jcasc-notification-secrets.yaml").toString());
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        assertEquals(SLACK, cfg.getSlackWebhookUrlPlain());
        assertEquals(TEAMS, cfg.getTeamsWebhookUrlPlain());
        assertEquals(SMTP_PW, cfg.getSmtpPassword().getPlainText());
    }

    private static void setSecret(OmniAuthGlobalConfig cfg, String field, String value) throws Exception {
        Field f = OmniAuthGlobalConfig.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(cfg, Secret.fromString(value));
    }

    private String exportToString() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ConfigurationAsCode.get().export(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
