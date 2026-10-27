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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies OmniAuth's roles and assignments survive a JCasC round trip.
 *
 * <p>Before this work, a {@code casc export} emitted {@code FAILED TO EXPORT ... No configurator
 * found} for both {@code omniAuthRoles.roles} ({@link OmniAuthRoleConfig.RoleDefinition}) and
 * {@code omniAuthAssignments.assignments} ({@link OmniAuthAssignment}), because neither nested
 * element type was data-bound. Adding {@code @DataBoundConstructor}/{@code @DataBoundSetter} to
 * those two types makes them configurable, so JCasC can both <em>apply</em> them from YAML
 * (import) and <em>emit</em> them (export). These tests pin both directions.
 */
@WithJenkinsConfiguredWithCode
public class JCasCRoundTripTest {

    private JenkinsConfiguredWithCodeRule j;

    @BeforeEach
    void setUp(JenkinsConfiguredWithCodeRule r) {
        j = r;
    }

    // ── Import: YAML -> live config ──────────────────────────────────────────

    @Test
    @ConfiguredWithCode("jcasc-roles-assignments.yaml")
    public void rolesAreConfiguredFromYaml() {
        OmniAuthRoleConfig roles = OmniAuthRoleConfig.get();
        assertNotNull(roles);
        // The YAML's single role replaces the constructor-seeded defaults.
        assertEquals(1, roles.getRoles().size());
        OmniAuthRoleConfig.RoleDefinition r = roles.getRoles().get(0);
        assertEquals("ci-developer", r.getName());
        assertEquals("Build and read jobs", r.getDescription());
        assertEquals(List.of("hudson.model.Item.Build", "hudson.model.Item.Read"), r.getPermissionIds());
    }

    @Test
    @ConfiguredWithCode("jcasc-roles-assignments.yaml")
    public void assignmentsAreConfiguredFromYaml() {
        OmniAuthAssignmentConfig cfg = OmniAuthAssignmentConfig.get();
        assertNotNull(cfg);
        assertEquals(2, cfg.getAssignments().size());

        OmniAuthAssignment standing = cfg.getAssignments().get(0);
        assertEquals("alice@contoso.com", standing.getUserId());
        assertEquals("USER", standing.getAuthType());
        assertEquals("ci-developer", standing.getRoleId());
        assertEquals("", standing.getScope());
        assertEquals("GLOBAL", standing.getScopeType());
        assertFalse(standing.isJit());

        OmniAuthAssignment jit = cfg.getAssignments().get(1);
        assertEquals("release-team", jit.getUserId());
        assertEquals("GROUP", jit.getAuthType());
        assertEquals("job/payments", jit.getScope());
        assertTrue(jit.isJit());
        assertEquals(List.of("alice@contoso.com"), jit.getApprovers());
        assertEquals(8, jit.getMaxDurationHours());
        assertEquals(2, jit.getApprovalTimeoutHours());
    }

    @Test
    @ConfiguredWithCode("jcasc-roles-assignments.yaml")
    public void accessManagementGroupsAreConfiguredFromYaml() {
        OmniAuthAssignmentConfig cfg = OmniAuthAssignmentConfig.get();
        assertEquals(1, cfg.getGroups().size());
        OmniAuthGroupEntity g = cfg.getGroups().get(0);
        assertEquals("00000000-aaaa-bbbb-cccc-111111111111", g.getGroupOid());
        assertEquals("Platform Engineers", g.getLabel());
        assertEquals("Platform Engineers", g.getDisplayName());
        assertEquals("admin", g.getAddedBy());
    }

    // ── Export: live config -> YAML (no "FAILED TO EXPORT") ──────────────────

    @Test
    @ConfiguredWithCode("jcasc-roles-assignments.yaml")
    public void rolesAndAssignmentsExportCleanly() throws Exception {
        String yaml = exportToString();

        // The regression this guards: nested element types used to be inexportable.
        assertFalse(yaml.contains("FAILED TO EXPORT"), "export must not contain the no-configurator failure marker");

        // Round-trip evidence: the configured values re-appear in the exported document.
        assertTrue(yaml.contains("ci-developer"), "role name should be exported");
        assertTrue(yaml.contains("alice@contoso.com"), "standing assignment should be exported");
        assertTrue(yaml.contains("release-team"), "JIT assignment should be exported");
        assertTrue(yaml.contains("Platform Engineers"), "Access-Management group should be exported");
    }

    private String exportToString() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ConfigurationAsCode.get().export(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
