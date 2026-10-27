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
}
