package io.jenkins.plugins.rotogate;

import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link RotogateConfigBackup} — the UI Backup &amp; Restore serializer.
 *
 * <p>Pins the safety-critical properties: the export never contains the realm or any secret;
 * import round-trips roles/assignments/groups/global settings with replace semantics; import
 * preserves existing secrets (never nulls them); and malformed input is rejected.
 */
@WithJenkins
public class RotogateConfigBackupTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    @Test
    public void roundTripConfig() {
        RotogateRoleConfig.get().setRoles(List.of(
                new RotogateRoleConfig.RoleDefinition("ci-dev", "desc", List.of("hudson.model.Item.Build"))));
        RotogateAssignmentConfig am = RotogateAssignmentConfig.get();
        am.setGroups(List.of(new RotogateGroupEntity("oid-1", "Platform", "2026-01-01T00:00:00Z", "admin")));
        am.setAssignments(List.of(new RotogateAssignment(
                "alice", "USER", "ci-dev", "", "GLOBAL", List.of(), "2026-01-01T00:00:00Z", "admin")));
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        cfg.setBruteForceThreshold(9);
        cfg.setProvisioningEnabled(true);
        cfg.setSmtpHost("smtp.example.com");

        String yaml = RotogateConfigBackup.export();

        // Wipe / change everything.
        RotogateRoleConfig.get().setRoles(List.of());
        am.setGroups(List.of());
        am.setAssignments(List.of());
        cfg.setBruteForceThreshold(5);
        cfg.setProvisioningEnabled(false);
        cfg.setSmtpHost("");

        RotogateConfigBackup.applyParsed(RotogateConfigBackup.parse(yaml));

        assertEquals(1, RotogateRoleConfig.get().getRoles().size());
        assertEquals("ci-dev", RotogateRoleConfig.get().getRoles().get(0).getName());
        assertEquals(1, am.getGroups().size());
        assertEquals("oid-1", am.getGroups().get(0).getGroupOid());
        assertEquals(1, am.getAssignments().size());
        assertEquals("alice", am.getAssignments().get(0).getUserId());
        assertEquals(9, cfg.getBruteForceThreshold());
        assertTrue(cfg.isProvisioningEnabled());
        assertEquals("smtp.example.com", cfg.getSmtpHost());
    }

    @Test
    public void exportExcludesRealmAndSecrets() throws Exception {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        setSecret(cfg, "slackWebhookUrl", "https://hooks.slack.com/SECRET-XYZ");
        setSecret(cfg, "smtpPassword", "p4ss-SECRET");
        cfg.save();

        String yaml = RotogateConfigBackup.export();

        assertFalse(yaml.contains("securityRealm"), "realm must not be exported");
        assertFalse(yaml.contains("entraConfig"), "entra must not be exported");
        assertFalse(yaml.contains("SECRET-XYZ"), "slack secret must not appear");
        assertFalse(yaml.contains("p4ss-SECRET"), "smtp secret must not appear");
        assertFalse(yaml.contains("slackWebhookUrl"), "secret keys must not appear");
        assertFalse(yaml.contains("smtpPassword"));
        // but the config sections are present
        assertTrue(yaml.contains("rotogateRoles"));
        assertTrue(yaml.contains("rotogateAssignments"));
        assertTrue(yaml.contains("rotogateGlobalConfig"));
    }

    @Test
    public void importPreservesExistingSecrets() throws Exception {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        setSecret(cfg, "slackWebhookUrl", "https://old");
        cfg.setSmtpHost("old-host");
        cfg.save();

        String yaml = RotogateConfigBackup.export(); // has smtpHost, NOT the secret

        // Change the secret after export; import must not revert/clear it.
        setSecret(cfg, "slackWebhookUrl", "https://current");
        cfg.setSmtpHost("changed-host");
        cfg.save();

        RotogateConfigBackup.applyParsed(RotogateConfigBackup.parse(yaml));

        assertEquals("old-host", cfg.getSmtpHost(), "non-secret field restored");
        assertEquals("https://current", cfg.getSlackWebhookUrlPlain(), "secret preserved, not touched by import");
    }

    @Test
    public void importReplacesRoles(/* not appends */) {
        RotogateRoleConfig rc = RotogateRoleConfig.get();
        rc.setRoles(List.of(new RotogateRoleConfig.RoleDefinition("only", "d", List.of("hudson.model.Hudson.Read"))));
        String yaml = RotogateConfigBackup.export();

        rc.setRoles(Arrays.asList(
                new RotogateRoleConfig.RoleDefinition("a", "", List.of()),
                new RotogateRoleConfig.RoleDefinition("b", "", List.of()),
                new RotogateRoleConfig.RoleDefinition("c", "", List.of())));
        assertEquals(3, rc.getRoles().size());

        RotogateConfigBackup.applyParsed(RotogateConfigBackup.parse(yaml));
        assertEquals(1, rc.getRoles().size(), "replace, not append");
        assertEquals("only", rc.getRoles().get(0).getName());
    }

    @Test
    public void parseRejectsMalformedAndEmpty() {
        assertRejected(null);
        assertRejected("");
        assertRejected("foo: bar\nbaz: qux");             // valid YAML, but no Rotogate sections
        assertRejected("unclassified:\n  rotogateRoles:\n    roles:\n    - name: ''"); // role without a name
    }

    private void assertRejected(String yaml) {
        try {
            RotogateConfigBackup.parse(yaml);
            fail("expected rejection for: " + yaml);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void exportHeaderIsAsciiOnly() {
        // The static header must stay ASCII: a non-ASCII char there can become a C1 control char
        // under a charset mismatch on upload and break YAML parsing ("special characters").
        String yaml = RotogateConfigBackup.export();
        String header = yaml.substring(0, yaml.indexOf("unclassified"));
        for (int i = 0; i < header.length(); i++) {
            assertTrue(header.charAt(i) < 0x80, "non-ASCII char in header at " + i);
        }
    }

    @Test
    public void parseAcceptsUnicodeValues() {
        // Correctly-decoded non-ASCII content must parse fine (the bug was decoding, not parsing).
        String yaml = "unclassified:\n"
                + "  rotogateGlobalConfig:\n"
                + "    loginHeading: \"Café — Build\"\n"
                + "    smtpFromName: \"Jenkins Ömni\"\n";
        RotogateConfigBackup.Parsed p = RotogateConfigBackup.parse(yaml);
        assertEquals("Café — Build", p.globals.get("loginHeading"));
    }

    @Test
    public void parseToleratesCharsetMangledUpload() {
        // Reproduces the browser-import bug: a UTF-8 file whose bytes the servlet container decoded
        // as ISO-8859-1, turning the em-dash into a C1 control char. Parse must still succeed.
        String original = "# Rotogate backup — note\n"
                + "unclassified:\n  rotogateGlobalConfig:\n    smtpHost: mangled-ok\n";
        String mangled = new String(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    java.nio.charset.StandardCharsets.ISO_8859_1);
        RotogateConfigBackup.Parsed p = RotogateConfigBackup.parse(mangled);
        assertEquals("mangled-ok", p.globals.get("smtpHost"));
    }

    @Test
    public void diffIsEmptyForIdenticalState() {
        RotogateRoleConfig.get().setRoles(List.of(
                new RotogateRoleConfig.RoleDefinition("r1", "d", List.of("hudson.model.Hudson.Read"))));
        RotogateConfigBackup.Diff d = RotogateConfigBackup.diff(RotogateConfigBackup.current());
        assertTrue(d.isEmpty(), "diff against the live state must be empty");
        assertEquals(0, d.totalChanges());
    }

    @Test
    public void diffDetectsAddRemoveChange() {
        RotogateRoleConfig rc = RotogateRoleConfig.get();
        // State A (captured into the backup): r1 (desc "one") and r2.
        rc.setRoles(Arrays.asList(
                new RotogateRoleConfig.RoleDefinition("r1", "one", List.of()),
                new RotogateRoleConfig.RoleDefinition("r2", "", List.of())));
        String yamlA = RotogateConfigBackup.export();

        // State B (live now): r1 (desc "two" — changed), r3 (added); r2 dropped.
        rc.setRoles(Arrays.asList(
                new RotogateRoleConfig.RoleDefinition("r1", "two", List.of()),
                new RotogateRoleConfig.RoleDefinition("r3", "", List.of())));

        // Importing A over B: r2 comes back (add), r3 goes away (remove), r1 changes.
        RotogateConfigBackup.Diff d = RotogateConfigBackup.diff(RotogateConfigBackup.parse(yamlA));
        assertEquals(List.of("r2"), d.rolesAdded);
        assertEquals(List.of("r3"), d.rolesRemoved);
        assertEquals(1, d.rolesChanged.size());
        assertEquals("r1", d.rolesChanged.get(0).name);
        assertFalse(d.isEmpty());
    }

    @Test
    public void diffReportsFieldLevelDetailForChangedRole() {
        RotogateRoleConfig rc = RotogateRoleConfig.get();
        // State A: role "ops" with description "one" and perm Build.
        rc.setRoles(List.of(new RotogateRoleConfig.RoleDefinition(
                "ops", "one", List.of("hudson.model.Item.Build"))));
        String yamlA = RotogateConfigBackup.export();
        // State B (live now): same role, description "two", perm Read instead of Build.
        rc.setRoles(List.of(new RotogateRoleConfig.RoleDefinition(
                "ops", "two", List.of("hudson.model.Item.Read"))));

        RotogateConfigBackup.Diff d = RotogateConfigBackup.diff(RotogateConfigBackup.parse(yamlA));
        assertEquals(1, d.rolesChanged.size());
        RotogateConfigBackup.ItemChange ch = d.rolesChanged.get(0);
        assertEquals("ops", ch.name);

        // Flatten lines to "label: [tone]text ..." so we can assert both content and colour.
        StringBuilder sb = new StringBuilder();
        for (RotogateConfigBackup.ChangeLine cl : ch.lines) {
            sb.append(cl.label).append(": ");
            for (RotogateConfigBackup.Seg s : cl.segs) sb.append('[').append(s.tone).append(']').append(s.text);
            sb.append(" | ");
        }
        String all = sb.toString();
        // description: current "two" (removed/red) → incoming "one" (added/green)
        assertTrue(all.contains("description") && all.contains("[remove]\"two\"") && all.contains("[add]\"one\""), all);
        // permissions: import re-adds Build (green) and drops Read (red)
        assertTrue(all.contains("permissions")
                && all.contains("[add]+hudson.model.Item.Build")
                && all.contains("[remove]-hudson.model.Item.Read"), all);
    }

    @Test
    public void diffDetectsSettingChange() {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        cfg.setSmtpHost("host-a");
        String yamlA = RotogateConfigBackup.export();
        cfg.setSmtpHost("host-b");

        RotogateConfigBackup.Diff d = RotogateConfigBackup.diff(RotogateConfigBackup.parse(yamlA));
        boolean found = false;
        for (RotogateConfigBackup.SettingChange c : d.settingsChanged) {
            if (c.key.equals("smtpHost")) {
                found = true;
                assertEquals("host-b", c.from);
                assertEquals("host-a", c.to);
            }
        }
        assertTrue(found, "smtpHost change must be reported");
    }

    @Test
    public void diffSummaryIsReadable() {
        RotogateRoleConfig.get().setRoles(List.of(new RotogateRoleConfig.RoleDefinition("keep", "", List.of())));
        String yamlA = RotogateConfigBackup.export();
        RotogateRoleConfig.get().setRoles(List.of());  // live now empty → import re-adds "keep"

        RotogateConfigBackup.Diff d = RotogateConfigBackup.diff(RotogateConfigBackup.parse(yamlA));
        assertTrue(d.summary().contains("roles"), d.summary());
        assertTrue(d.summary().contains("+1"), d.summary());
    }

    private static void setSecret(RotogateGlobalConfig cfg, String field, String value) {
        Secret s = Secret.fromString(value);
        switch (field) {
            case "slackWebhookUrl": cfg.setSlackWebhookUrl(s); break;
            case "teamsWebhookUrl": cfg.setTeamsWebhookUrl(s); break;
            case "smtpPassword":    cfg.setSmtpPassword(s);    break;
            default: throw new IllegalArgumentException("unknown secret field: " + field);
        }
    }
}
