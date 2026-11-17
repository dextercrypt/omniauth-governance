package io.jenkins.plugins.omniauth;

import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OmniAuthAuthorizationStrategy}: per-item ACL wiring, the lazy ACL cache and
 * its invalidation, and the descriptor/converter plumbing.
 */
@WithJenkins
public class OmniAuthAuthorizationStrategyTest {

    private JenkinsRule j;

    private OmniAuthAuthorizationStrategy strategy;
    private OmniAuthAssignmentConfig config;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
        strategy = new OmniAuthAuthorizationStrategy();
        j.jenkins.setAuthorizationStrategy(strategy);
        // aclCache is static and survives across tests in the JVM — clear it for isolation.
        OmniAuthAuthorizationStrategy.invalidateCache();
        config = OmniAuthAssignmentConfig.get();
        config.setAssignments(new ArrayList<>());
    }

    /**
     * Invokes the OmniAuth override {@code getACL(AbstractItem)}. The static type must be
     * AbstractItem: ProjectMatrixAuthorizationStrategy also declares a more specific
     * {@code getACL(Job)} overload, and in production Jenkins reaches the strategy through
     * {@code AbstractItem.getACL()} (where {@code this} is typed AbstractItem), so this mirrors
     * the real dispatch path.
     */
    private ACL aclFor(AbstractItem item) {
        return strategy.getACL(item);
    }

    private void addAssignment() {
        config.setAssignments(new ArrayList<>(Arrays.asList(
                new OmniAuthAssignment("alice", "USER", "Developer", "teamA", "FOLDER",
                        new ArrayList<>(), Instant.now().toString(), "admin"))));
    }

    // ── getACL: dispatch between super matrix ACL and the per-item OmniAuth ACL ──

    @Test
    public void getACL_withNoAssignments_returnsNonOmniAuthAcl() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job-empty");
        ACL acl = aclFor(p);
        assertNotNull(acl);
        assertFalse(acl instanceof OmniAuthItemACL, "with no assignments, the super matrix ACL should be used");
    }

    @Test
    public void getACL_withAssignments_returnsOmniAuthItemACL() throws Exception {
        addAssignment();
        FreeStyleProject p = j.createFreeStyleProject("job-present");
        ACL acl = aclFor(p);
        assertTrue(acl instanceof OmniAuthItemACL, "with assignments present, a per-item OmniAuthItemACL should be returned");
    }

    @Test
    public void getACL_cachesOneAclPerItemFullName() throws Exception {
        addAssignment();
        FreeStyleProject p = j.createFreeStyleProject("job-cached");
        ACL first  = aclFor(p);
        ACL second = aclFor(p);
        assertSame(first, second, "the per-item ACL should be cached and reused");
    }

    @Test
    public void invalidateCache_forcesAFreshAclInstance() throws Exception {
        addAssignment();
        FreeStyleProject p = j.createFreeStyleProject("job-evicted");
        ACL first = aclFor(p);
        OmniAuthAuthorizationStrategy.invalidateCache();
        ACL second = aclFor(p);
        assertNotSame(first, second, "after invalidation a new ACL instance should be built");
        assertTrue(second instanceof OmniAuthItemACL);
    }

    // ── root ACL sanity (the backbone of OmniAuthItemACL's admin/fall-through) ──

    @Test
    public void rootACL_withNoGrants_deniesOrdinaryAuthenticatedUser() {
        ACL root = strategy.getRootACL();
        assertNotNull(root);
        assertFalse(root.hasPermission2(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                "nobody", "", java.util.Collections.emptyList()),
                        jenkins.model.Jenkins.ADMINISTER), "an unprivileged user must not have ADMINISTER via the empty root matrix");
    }

    // ── descriptor / converter plumbing ─────────────────────────────────────────

    @Test
    public void descriptor_hasExpectedDisplayName() {
        OmniAuthAuthorizationStrategy.DescriptorImpl d =
                j.jenkins.getDescriptorByType(OmniAuthAuthorizationStrategy.DescriptorImpl.class);
        assertNotNull(d);
        assertEquals("OmniAuth Authorization Strategy", d.getDisplayName());
    }

    @Test
    public void converter_convertsOnlyTheOmniAuthStrategyClass() {
        OmniAuthAuthorizationStrategy.ConverterImpl converter =
                new OmniAuthAuthorizationStrategy.ConverterImpl();
        assertTrue(converter.canConvert(OmniAuthAuthorizationStrategy.class));
        assertFalse(converter.canConvert(GlobalMatrixAuthorizationStrategy.class));
        assertTrue(converter.create() instanceof OmniAuthAuthorizationStrategy, "ConverterImpl.create() yields an OmniAuth strategy");
    }

    @Test
    public void migrationSource_isNull_whenOmniAuthIsAlreadyActive() {
        // OmniAuth is the installed strategy (see @BeforeEach) → there is nothing to migrate from.
        OmniAuthAuthorizationStrategy.DescriptorImpl d =
                j.jenkins.getDescriptorByType(OmniAuthAuthorizationStrategy.DescriptorImpl.class);
        assertNull(d.getMigrationSource());
        assertFalse(d.shouldShowMigrationNotice());
    }

    @Test
    public void migrationSource_returnsExistingMatrixStrategy_whenSwitchingFromIt() {
        // Install a plain GlobalMatrix (not OmniAuth) → the descriptor should surface it as the
        // migration source for the switch-over UI.
        AuthorizationStrategy plain = new GlobalMatrixAuthorizationStrategy();
        j.jenkins.setAuthorizationStrategy(plain);

        OmniAuthAuthorizationStrategy.DescriptorImpl d =
                j.jenkins.getDescriptorByType(OmniAuthAuthorizationStrategy.DescriptorImpl.class);
        assertSame(plain, d.getMigrationSource());
        // An empty matrix has no granted entries, so no migration notice is shown.
        assertFalse(d.shouldShowMigrationNotice());
        // getPrePopulatedInstance returns a (non-null) OmniAuth strategy when a source exists.
        assertNotNull(d.getPrePopulatedInstance());
    }

    // -------------------------------------------------------------------------
    // Dangerous (admin-equivalent) permissions must never be grantable.
    // -------------------------------------------------------------------------

    @Test
    public void isDangerousPermission_flagsAdminEquivalentPermissions() {
        assertTrue(OmniAuthAuthorizationStrategy.isDangerousPermission("hudson.model.Hudson.RunScripts"));
        assertTrue(OmniAuthAuthorizationStrategy.isDangerousPermission("hudson.model.Hudson.ConfigureUpdateCenter"));
        assertTrue(OmniAuthAuthorizationStrategy.isDangerousPermission("hudson.model.Hudson.UploadPlugins"));
        // Ordinary permissions and junk are not dangerous.
        assertFalse(OmniAuthAuthorizationStrategy.isDangerousPermission("hudson.model.Hudson.Administer"));
        assertFalse(OmniAuthAuthorizationStrategy.isDangerousPermission("hudson.model.Hudson.Read"));
        assertFalse(OmniAuthAuthorizationStrategy.isDangerousPermission("not.a.real.Permission"));
        assertFalse(OmniAuthAuthorizationStrategy.isDangerousPermission(null));
    }

    @Test
    public void roleDefinition_stripsDangerousPermissions() {
        OmniAuthRoleConfig.RoleDefinition role = new OmniAuthRoleConfig.RoleDefinition(
                "danger", "tries to grab admin-equivalent perms",
                Arrays.asList(
                        "hudson.model.Hudson.Read",
                        "hudson.model.Hudson.RunScripts",
                        "hudson.model.Hudson.ConfigureUpdateCenter",
                        "hudson.model.Hudson.UploadPlugins",
                        "hudson.model.Item.Build"));
        // The three dangerous ids are dropped; the ordinary ones survive.
        assertEquals(Arrays.asList("hudson.model.Hudson.Read", "hudson.model.Item.Build"),
                role.getPermissionIds());
    }

    @Test
    public void permissionGroups_doNotOfferDangerousPermissions() {
        OmniAuthManagementLink link = j.jenkins.getExtensionList(OmniAuthManagementLink.class).get(0);
        boolean anyDangerous = link.getAvailablePermissions().stream()
                .flatMap(g -> g.getPermissions().stream())
                .anyMatch(p -> OmniAuthAuthorizationStrategy.isDangerousPermission(p.getId()));
        assertFalse(anyDangerous, "the permission picker must not offer admin-equivalent permissions");
    }

    @Test
    public void permissionGroups_areDerivedFromJenkinsNotAnOtherBucket() {
        OmniAuthManagementLink link = j.jenkins.getExtensionList(OmniAuthManagementLink.class).get(0);
        var groups = link.getAvailablePermissions();
        // No catch-all "other plugin permissions" dumping ground — everything sits in its real group.
        assertTrue(groups.stream().noneMatch(g -> g.getGroupName().toLowerCase().contains("other plugin")),
                "permissions should be grouped by their real Jenkins group, not an 'other' bucket");
        // The Item/Job permission group is present and holds the Item.* permissions.
        boolean hasItemGroup = groups.stream().anyMatch(g ->
                g.getPermissions().stream().anyMatch(p -> p.getId().startsWith("hudson.model.Item.")));
        assertTrue(hasItemGroup, "the Item/Job permission group should be offered");
    }

    @Test
    public void permissionInfo_carriesDescriptionAndImpliedBy() {
        OmniAuthManagementLink link = j.jenkins.getExtensionList(OmniAuthManagementLink.class).get(0);
        var all = link.getAvailablePermissions().stream()
                .flatMap(g -> g.getPermissions().stream())
                .toList();
        OmniAuthManagementLink.PermissionInfo read = all.stream()
                .filter(p -> p.getId().equals("hudson.model.Item.Read")).findFirst().orElseThrow();
        assertFalse(read.getDescription().isEmpty(), "Item.Read should expose its description for the tooltip");
        // Discover is granted automatically by a stronger permission (Read), so it is implied-by something.
        OmniAuthManagementLink.PermissionInfo discover = all.stream()
                .filter(p -> p.getId().equals("hudson.model.Item.Discover")).findFirst().orElseThrow();
        assertFalse(discover.getImpliedById().isEmpty(), "Item.Discover should report the permission that implies it");
    }
}
