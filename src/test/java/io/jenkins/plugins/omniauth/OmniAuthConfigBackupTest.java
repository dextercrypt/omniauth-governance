package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link OmniAuthConfigBackup} — the UI Backup &amp; Restore serializer.
 *
 * <p>Pins the safety-critical properties: the export never contains the realm or any secret;
 * import round-trips roles/assignments/groups/global settings with replace semantics; import
 * preserves existing secrets (never nulls them); and malformed input is rejected.
 */
public class OmniAuthConfigBackupTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void roundTripConfig() {
        OmniAuthRoleConfig.get().setRoles(List.of(
                new OmniAuthRoleConfig.RoleDefinition("ci-dev", "desc", List.of("hudson.model.Item.Build"))));
        OmniAuthAssignmentConfig am = OmniAuthAssignmentConfig.get();
        am.setGroups(List.of(new OmniAuthGroupEntity("oid-1", "Platform", "2026-01-01T00:00:00Z", "admin")));
        am.setAssignments(List.of(new OmniAuthAssignment(
                "alice", "USER", "ci-dev", "", "GLOBAL", List.of(), "2026-01-01T00:00:00Z", "admin")));
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        cfg.setBruteForceThreshold(9);
        cfg.setProvisioningEnabled(true);
        cfg.setSmtpHost("smtp.example.com");

        String yaml = OmniAuthConfigBackup.export();

        // Wipe / change everything.
        OmniAuthRoleConfig.get().setRoles(List.of());
        am.setGroups(List.of());
        am.setAssignments(List.of());
        cfg.setBruteForceThreshold(5);
        cfg.setProvisioningEnabled(false);
        cfg.setSmtpHost("");

        OmniAuthConfigBackup.applyParsed(OmniAuthConfigBackup.parse(yaml));

        assertEquals(1, OmniAuthRoleConfig.get().getRoles().size());
        assertEquals("ci-dev", OmniAuthRoleConfig.get().getRoles().get(0).getName());
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
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        setSecret(cfg, "slackWebhookUrl", "https://hooks.slack.com/SECRET-XYZ");
        setSecret(cfg, "smtpPassword", "p4ss-SECRET");
        cfg.save();

        String yaml = OmniAuthConfigBackup.export();

        assertFalse("realm must not be exported", yaml.contains("securityRealm"));
        assertFalse("entra must not be exported", yaml.contains("entraConfig"));
        assertFalse("slack secret must not appear", yaml.contains("SECRET-XYZ"));
        assertFalse("smtp secret must not appear", yaml.contains("p4ss-SECRET"));
        assertFalse("secret keys must not appear", yaml.contains("slackWebhookUrl"));
        assertFalse(yaml.contains("smtpPassword"));
        // but the config sections are present
        assertTrue(yaml.contains("omniAuthRoles"));
        assertTrue(yaml.contains("omniAuthAssignments"));
        assertTrue(yaml.contains("omniAuthGlobalConfig"));
    }

    @Test
    public void importPreservesExistingSecrets() throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        setSecret(cfg, "slackWebhookUrl", "https://old");
        cfg.setSmtpHost("old-host");
        cfg.save();

        String yaml = OmniAuthConfigBackup.export(); // has smtpHost, NOT the secret

        // Change the secret after export; import must not revert/clear it.
        setSecret(cfg, "slackWebhookUrl", "https://current");
        cfg.setSmtpHost("changed-host");
        cfg.save();

        OmniAuthConfigBackup.applyParsed(OmniAuthConfigBackup.parse(yaml));

        assertEquals("non-secret field restored", "old-host", cfg.getSmtpHost());
        assertEquals("secret preserved, not touched by import", "https://current", cfg.getSlackWebhookUrlPlain());
    }

    @Test
    public void importReplacesRoles(/* not appends */) {
        OmniAuthRoleConfig rc = OmniAuthRoleConfig.get();
        rc.setRoles(List.of(new OmniAuthRoleConfig.RoleDefinition("only", "d", List.of("hudson.model.Hudson.Read"))));
        String yaml = OmniAuthConfigBackup.export();

        rc.setRoles(Arrays.asList(
                new OmniAuthRoleConfig.RoleDefinition("a", "", List.of()),
                new OmniAuthRoleConfig.RoleDefinition("b", "", List.of()),
                new OmniAuthRoleConfig.RoleDefinition("c", "", List.of())));
        assertEquals(3, rc.getRoles().size());

        OmniAuthConfigBackup.applyParsed(OmniAuthConfigBackup.parse(yaml));
        assertEquals("replace, not append", 1, rc.getRoles().size());
        assertEquals("only", rc.getRoles().get(0).getName());
    }

    @Test
    public void parseRejectsMalformedAndEmpty() {
        assertRejected(null);
        assertRejected("");
        assertRejected("foo: bar\nbaz: qux");             // valid YAML, but no OmniAuth sections
        assertRejected("unclassified:\n  omniAuthRoles:\n    roles:\n    - name: ''"); // role without a name
    }

    private void assertRejected(String yaml) {
        try {
            OmniAuthConfigBackup.parse(yaml);
            fail("expected rejection for: " + yaml);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void exportHeaderIsAsciiOnly() {
        // The static header must stay ASCII: a non-ASCII char there can become a C1 control char
        // under a charset mismatch on upload and break YAML parsing ("special characters").
        String yaml = OmniAuthConfigBackup.export();
        String header = yaml.substring(0, yaml.indexOf("unclassified"));
        for (int i = 0; i < header.length(); i++) {
            assertTrue("non-ASCII char in header at " + i, header.charAt(i) < 0x80);
        }
    }

    @Test
    public void parseAcceptsUnicodeValues() {
        // Correctly-decoded non-ASCII content must parse fine (the bug was decoding, not parsing).
        String yaml = "unclassified:\n"
                + "  omniAuthGlobalConfig:\n"
                + "    loginHeading: \"Café — Build\"\n"
                + "    smtpFromName: \"Jenkins Ömni\"\n";
        OmniAuthConfigBackup.Parsed p = OmniAuthConfigBackup.parse(yaml);
        assertEquals("Café — Build", p.globals.get("loginHeading"));
    }

    @Test
    public void parseToleratesCharsetMangledUpload() {
        // Reproduces the browser-import bug: a UTF-8 file whose bytes the servlet container decoded
        // as ISO-8859-1, turning the em-dash into a C1 control char. Parse must still succeed.
        String original = "# OmniAuth backup — note\n"
                + "unclassified:\n  omniAuthGlobalConfig:\n    smtpHost: mangled-ok\n";
        String mangled = new String(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    java.nio.charset.StandardCharsets.ISO_8859_1);
        OmniAuthConfigBackup.Parsed p = OmniAuthConfigBackup.parse(mangled);
        assertEquals("mangled-ok", p.globals.get("smtpHost"));
    }

    @Test
    public void diffIsEmptyForIdenticalState() {
        OmniAuthRoleConfig.get().setRoles(List.of(
                new OmniAuthRoleConfig.RoleDefinition("r1", "d", List.of("hudson.model.Hudson.Read"))));
        OmniAuthConfigBackup.Diff d = OmniAuthConfigBackup.diff(OmniAuthConfigBackup.current());
        assertTrue("diff against the live state must be empty", d.isEmpty());
        assertEquals(0, d.totalChanges());
    }

    @Test
    public void diffDetectsAddRemoveChange() {
        OmniAuthRoleConfig rc = OmniAuthRoleConfig.get();
        // State A (captured into the backup): r1 (desc "one") and r2.
        rc.setRoles(Arrays.asList(
                new OmniAuthRoleConfig.RoleDefinition("r1", "one", List.of()),
                new OmniAuthRoleConfig.RoleDefinition("r2", "", List.of())));
        String yamlA = OmniAuthConfigBackup.export();

        // State B (live now): r1 (desc "two" — changed), r3 (added); r2 dropped.
        rc.setRoles(Arrays.asList(
                new OmniAuthRoleConfig.RoleDefinition("r1", "two", List.of()),
                new OmniAuthRoleConfig.RoleDefinition("r3", "", List.of())));

        // Importing A over B: r2 comes back (add), r3 goes away (remove), r1 changes.
        OmniAuthConfigBackup.Diff d = OmniAuthConfigBackup.diff(OmniAuthConfigBackup.parse(yamlA));
        assertEquals(List.of("r2"), d.rolesAdded);
        assertEquals(List.of("r3"), d.rolesRemoved);
        assertEquals(1, d.rolesChanged.size());
        assertEquals("r1", d.rolesChanged.get(0).name);
        assertFalse(d.isEmpty());
    }

    @Test
    public void diffReportsFieldLevelDetailForChangedRole() {
        OmniAuthRoleConfig rc = OmniAuthRoleConfig.get();
        // State A: role "ops" with description "one" and perm Build.
        rc.setRoles(List.of(new OmniAuthRoleConfig.RoleDefinition(
                "ops", "one", List.of("hudson.model.Item.Build"))));
        String yamlA = OmniAuthConfigBackup.export();
        // State B (live now): same role, description "two", perm Read instead of Build.
        rc.setRoles(List.of(new OmniAuthRoleConfig.RoleDefinition(
                "ops", "two", List.of("hudson.model.Item.Read"))));

        OmniAuthConfigBackup.Diff d = OmniAuthConfigBackup.diff(OmniAuthConfigBackup.parse(yamlA));
        assertEquals(1, d.rolesChanged.size());
        OmniAuthConfigBackup.ItemChange ch = d.rolesChanged.get(0);
        assertEquals("ops", ch.name);

        // Flatten lines to "label: [tone]text ..." so we can assert both content and colour.
        StringBuilder sb = new StringBuilder();
        for (OmniAuthConfigBackup.ChangeLine cl : ch.lines) {
            sb.append(cl.label).append(": ");
            for (OmniAuthConfigBackup.Seg s : cl.segs) sb.append('[').append(s.tone).append(']').append(s.text);
            sb.append(" | ");
        }
        String all = sb.toString();
        // description: current "two" (removed/red) → incoming "one" (added/green)
        assertTrue(all, all.contains("description") && all.contains("[remove]\"two\"") && all.contains("[add]\"one\""));
        // permissions: import re-adds Build (green) and drops Read (red)
        assertTrue(all, all.contains("permissions")
                && all.contains("[add]+hudson.model.Item.Build")
                && all.contains("[remove]-hudson.model.Item.Read"));
    }

    @Test
    public void diffDetectsSettingChange() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        cfg.setSmtpHost("host-a");
        String yamlA = OmniAuthConfigBackup.export();
        cfg.setSmtpHost("host-b");

        OmniAuthConfigBackup.Diff d = OmniAuthConfigBackup.diff(OmniAuthConfigBackup.parse(yamlA));
        boolean found = false;
        for (OmniAuthConfigBackup.SettingChange c : d.settingsChanged) {
            if (c.key.equals("smtpHost")) {
                found = true;
                assertEquals("host-b", c.from);
                assertEquals("host-a", c.to);
            }
        }
        assertTrue("smtpHost change must be reported", found);
    }

    @Test
    public void diffSummaryIsReadable() {
        OmniAuthRoleConfig.get().setRoles(List.of(new OmniAuthRoleConfig.RoleDefinition("keep", "", List.of())));
        String yamlA = OmniAuthConfigBackup.export();
        OmniAuthRoleConfig.get().setRoles(List.of());  // live now empty → import re-adds "keep"

        OmniAuthConfigBackup.Diff d = OmniAuthConfigBackup.diff(OmniAuthConfigBackup.parse(yamlA));
        assertTrue(d.summary(), d.summary().contains("roles"));
        assertTrue(d.summary(), d.summary().contains("+1"));
    }

    private static void setSecret(OmniAuthGlobalConfig cfg, String field, String value) throws Exception {
        Field f = OmniAuthGlobalConfig.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(cfg, Secret.fromString(value));
    }
}
