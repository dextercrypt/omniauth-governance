package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Security + round-trip for the notification secrets (SMTP password, Slack/Teams webhooks).
 *
 * <p>These are {@code hudson.util.Secret} fields with Secret-typed getters/setters, so JCasC
 * treats them exactly like {@code clientSecret}: on export it emits Jenkins ciphertext
 * ({@code {AQAA...}}), never plaintext; on import it accepts a value (and, in production, resolves
 * {@code ${ENV}} the same way). This test proves (1) no plaintext ever reaches the exported YAML,
 * (2) the secrets ARE exported (encrypted) so a backup is complete, and (3) they import back.
 */
public class JCasCSecretExportTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

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
        assertFalse("Slack webhook leaked in plaintext", yaml.contains(SLACK));
        assertFalse("Teams webhook leaked in plaintext", yaml.contains(TEAMS));
        assertFalse("SMTP password leaked in plaintext", yaml.contains(SMTP_PW));

        // (2) They ARE exported now (so backup is complete) — as encrypted attributes.
        assertTrue("slackWebhookUrl should be exported", yaml.contains("slackWebhookUrl"));
        assertTrue("teamsWebhookUrl should be exported", yaml.contains("teamsWebhookUrl"));
        assertTrue("smtpPassword should be exported", yaml.contains("smtpPassword"));
        // Jenkins Secret ciphertext is wrapped in braces, e.g. {AQAA....==}.
        assertTrue("exported secret should be Jenkins ciphertext", yaml.matches("(?s).*slackWebhookUrl:\\s*\"\\{.*"));
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
