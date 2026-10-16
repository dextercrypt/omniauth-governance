package io.jenkins.plugins.omniauth;

import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.Permission;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Behaviour tests for {@link OmniAuthItemACL} — the core per-item permission resolver and the
 * most security-critical class in the plugin.
 *
 * <p>Strategy: an {@link OmniAuthAuthorizationStrategy} (a ProjectMatrix subclass) with NO matrix
 * grants is installed so that the ACL's admin-bypass check and its root-ACL fall-through both
 * return {@code false} for an ordinary authenticated user. That forces the real assignment-based
 * resolution logic to run, rather than being short-circuited by an admin or a global grant.</p>
 *
 * <p>Default roles (Administrator / Developer / Read Only) from {@link OmniAuthRoleConfig} are
 * used. "Developer" is the useful one here: it grants Item.Build/Cancel/Configure/Delete/WipeOut/Read
 * (but NOT Item.Discover), which is exactly what the ancestor/direct-parent boundary tests need.</p>
 */
public class OmniAuthItemACLTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String DEVELOPER     = "Developer";
    private static final String ADMINISTRATOR = "Administrator";

    private OmniAuthAssignmentConfig config;

    @Before
    public void setUp() {
        // No matrix grants => the ACL's admin bypass and root-ACL fall-through both deny the
        // ordinary test user, so the assignment logic is actually exercised.
        j.jenkins.setAuthorizationStrategy(new OmniAuthAuthorizationStrategy());
        OmniAuthAuthorizationStrategy.invalidateCache();
        config = OmniAuthAssignmentConfig.get();
        config.setAssignments(new ArrayList<>());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Authentication user(String name, GrantedAuthority... authorities) {
        return new UsernamePasswordAuthenticationToken(name, "",
                authorities.length == 0 ? Collections.emptyList() : Arrays.asList(authorities));
    }

    private OmniAuthAssignment assignment(String userId, String authType, String roleId,
                                          String scope, String scopeType) {
        return new OmniAuthAssignment(userId, authType, roleId, scope, scopeType,
                new ArrayList<>(), Instant.now().toString(), "admin");
    }

    private void install(OmniAuthAssignment... as) {
        config.setAssignments(new ArrayList<>(Arrays.asList(as)));
        OmniAuthAuthorizationStrategy.invalidateCache();
    }

    // ── job-scoped grant: the job itself gets the full role ────────────────────

    @Test
    public void jobScopedGrant_appliesFullRoleToTheJobItself() {
        install(assignment("alice", "USER", DEVELOPER, "folderA/folderB/jobX", "JOB"));
        OmniAuthItemACL acl = new OmniAuthItemACL("folderA/folderB/jobX");
        Authentication alice = user("alice");

        assertTrue("job gets Build",     acl.hasPermission2(alice, Item.BUILD));
        assertTrue("job gets Configure", acl.hasPermission2(alice, Item.CONFIGURE));
        assertTrue("job gets Delete",    acl.hasPermission2(alice, Item.DELETE));
        assertTrue("job gets Read",      acl.hasPermission2(alice, Item.READ));
    }

    // ── REGRESSION (fixed HIGH priv-esc): ancestor folders must NOT be elevated ──

    @Test
    public void jobScopedGrant_doesNotElevateAncestorFolder() {
        // Grant on the JOB only. "folderA" is the grandparent — two levels above the job.
        install(assignment("alice", "USER", DEVELOPER, "folderA/folderB/jobX", "JOB"));
        OmniAuthItemACL ancestor = new OmniAuthItemACL("folderA");
        Authentication alice = user("alice");

        assertFalse("ancestor must NOT get Configure", ancestor.hasPermission2(alice, Item.CONFIGURE));
        assertFalse("ancestor must NOT get Delete",    ancestor.hasPermission2(alice, Item.DELETE));
        assertFalse("ancestor must NOT get Build",     ancestor.hasPermission2(alice, Item.BUILD));
        assertFalse("ancestor must NOT get WipeOut",   ancestor.hasPermission2(alice, Item.WIPEOUT));
    }

    @Test
    public void jobScopedGrant_ancestorFolderGetsItemReadForNavigationOnly() {
        install(assignment("alice", "USER", DEVELOPER, "folderA/folderB/jobX", "JOB"));
        OmniAuthItemACL ancestor = new OmniAuthItemACL("folderA");

        // Nav-only: the ancestor is readable (so the user can navigate down to the job)
        // but nothing mutating is allowed.
        assertTrue("ancestor gets Item.Read for navigation",
                ancestor.hasPermission2(user("alice"), Item.READ));
    }

    // ── direct parent of a job grant gets ONLY the build subset ─────────────────

    @Test
    public void jobScopedGrant_directParentGetsBuildSubsetOnly() {
        install(assignment("alice", "USER", DEVELOPER, "folderA/folderB/jobX", "JOB"));
        OmniAuthItemACL directParent = new OmniAuthItemACL("folderA/folderB");
        Authentication alice = user("alice");

        // Build-enabling subset is delegated to the direct parent (Jenkins routes a pipeline's
        // Build check through its parent folder).
        assertTrue("direct parent gets Read",   directParent.hasPermission2(alice, Item.READ));
        assertTrue("direct parent gets Build",  directParent.hasPermission2(alice, Item.BUILD));
        assertTrue("direct parent gets Cancel", directParent.hasPermission2(alice, Item.CANCEL));

        // But NEVER the mutating permissions — that would let a one-job grant reconfigure/delete
        // the whole parent folder.
        assertFalse("direct parent must NOT get Configure", directParent.hasPermission2(alice, Item.CONFIGURE));
        assertFalse("direct parent must NOT get Delete",    directParent.hasPermission2(alice, Item.DELETE));
        assertFalse("direct parent must NOT get WipeOut",   directParent.hasPermission2(alice, Item.WIPEOUT));
    }

    // ── folder-scoped grant ─────────────────────────────────────────────────────

    @Test
    public void folderScopedGrant_appliesFullRoleToTheFolderItself() {
        install(assignment("alice", "USER", DEVELOPER, "teamA", "FOLDER"));
        OmniAuthItemACL acl = new OmniAuthItemACL("teamA");
        assertTrue(acl.hasPermission2(user("alice"), Item.CONFIGURE));
    }

    @Test
    public void folderScopedGrant_appliesToDescendants() {
        install(assignment("alice", "USER", DEVELOPER, "teamA", "FOLDER"));
        OmniAuthItemACL descendant = new OmniAuthItemACL("teamA/job1");
        assertTrue("descendant inherits the folder role",
                descendant.hasPermission2(user("alice"), Item.CONFIGURE));
    }

    // ── REGRESSION: trailing-slash matching stops sibling-prefix leakage ────────

    @Test
    public void folderScopedGrant_doesNotLeakToSiblingWithSharedPrefix() {
        // Grant on "teamA" must NOT bleed onto "teamA-secret" (shared prefix, different folder).
        install(assignment("alice", "USER", DEVELOPER, "teamA", "FOLDER"));
        OmniAuthItemACL sibling = new OmniAuthItemACL("teamA-secret");
        Authentication alice = user("alice");

        assertFalse("sibling prefix must NOT get Configure", sibling.hasPermission2(alice, Item.CONFIGURE));
        assertFalse("sibling prefix must NOT even get Read",  sibling.hasPermission2(alice, Item.READ));
    }

    @Test
    public void folderScopedGrant_ancestorGetsNavReadOnly() {
        // Grant on nested folder "parent/teamA" — the "parent" ancestor gets nav Read only.
        install(assignment("alice", "USER", DEVELOPER, "parent/teamA", "FOLDER"));
        OmniAuthItemACL parent = new OmniAuthItemACL("parent");
        Authentication alice = user("alice");

        assertTrue("ancestor folder gets nav Read", parent.hasPermission2(alice, Item.READ));
        assertFalse("ancestor folder does NOT get Configure", parent.hasPermission2(alice, Item.CONFIGURE));
    }

    // ── global grant resolution ─────────────────────────────────────────────────

    @Test
    public void globalGrant_appliesToAnyItemEverywhere() {
        install(assignment("alice", "USER", DEVELOPER, "", "GLOBAL"));
        // Any arbitrary path is covered by a global grant.
        assertTrue(new OmniAuthItemACL("some/deep/nested/path").hasPermission2(user("alice"), Item.CONFIGURE));
        assertTrue(new OmniAuthItemACL("other").hasPermission2(user("alice"), Item.BUILD));
    }

    @Test
    public void globalAdministratorGrant_grantsAdminister() {
        install(assignment("root", "USER", ADMINISTRATOR, "", "GLOBAL"));
        assertTrue(new OmniAuthItemACL("anything").hasPermission2(user("root"), Jenkins.ADMINISTER));
    }

    // ── fail-closed expiry ──────────────────────────────────────────────────────

    @Test
    public void expiredAssignment_grantsNothing() {
        OmniAuthAssignment a = assignment("alice", "USER", DEVELOPER, "folderA/jobX", "JOB");
        a.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS).toString());
        install(a);
        OmniAuthItemACL acl = new OmniAuthItemACL("folderA/jobX");
        Authentication alice = user("alice");

        assertFalse("expired grant gives no Read",      acl.hasPermission2(alice, Item.READ));
        assertFalse("expired grant gives no Configure", acl.hasPermission2(alice, Item.CONFIGURE));
    }

    @Test
    public void corruptExpiryTimestamp_failsClosedAndGrantsNothing() {
        OmniAuthAssignment a = assignment("alice", "USER", DEVELOPER, "folderA/jobX", "JOB");
        a.setExpiresAt("not-a-real-timestamp");
        install(a);
        // isExpired() treats an unparseable expiry as expired → the grant is withheld.
        assertFalse(new OmniAuthItemACL("folderA/jobX").hasPermission2(user("alice"), Item.READ));
    }

    // ── fall-through / anonymous / system ───────────────────────────────────────

    @Test
    public void userWithNoAssignment_fallsThroughAndIsDenied() {
        // No OmniAuth assignment and no matrix grant → fail closed.
        OmniAuthItemACL acl = new OmniAuthItemACL("someJob");
        Authentication bob = user("bob");
        assertFalse(acl.hasPermission2(bob, Item.READ));
        assertFalse(acl.hasPermission2(bob, Item.CONFIGURE));
    }

    @Test
    public void nullAuthentication_isDenied() {
        assertFalse(new OmniAuthItemACL("someJob").hasPermission2((Authentication) null, Item.READ));
    }

    @Test
    public void systemAuthentication_isAlwaysAllowed() {
        // Jenkins' internal SYSTEM principal bypasses everything.
        assertTrue(new OmniAuthItemACL("someJob").hasPermission2(ACL.SYSTEM2, Item.CONFIGURE));
    }

    // ── group assignments ───────────────────────────────────────────────────────

    @Test
    public void groupScopedGrant_appliesToUserInThatGroup() {
        install(assignment("group-oid-1", "GROUP", DEVELOPER, "teamA/jobX", "JOB"));
        Authentication inGroup = user("alice", new EntraGroupDetails("group-oid-1", "Dev Team"));
        assertTrue(new OmniAuthItemACL("teamA/jobX").hasPermission2(inGroup, Item.CONFIGURE));
    }

    @Test
    public void groupScopedGrant_doesNotApplyToUserNotInThatGroup() {
        install(assignment("group-oid-1", "GROUP", DEVELOPER, "teamA/jobX", "JOB"));
        // Same assignment, but this user carries a *different* group authority.
        Authentication other = user("bob", new EntraGroupDetails("group-oid-OTHER", "Other Team"));
        assertFalse(new OmniAuthItemACL("teamA/jobX").hasPermission2(other, Item.CONFIGURE));
    }

    @Test
    public void groupGrant_matchesByObjectIdNotDisplayName() {
        // Assignment is keyed by the group OID. A member whose display name differs still matches
        // via the OID; an assignment keyed by the display name string does NOT.
        install(assignment("group-oid-1", "GROUP", DEVELOPER, "teamA/jobX", "JOB"),
                assignment("Dev Team",     "GROUP", DEVELOPER, "teamA/jobX", "JOB"));
        Authentication member = user("alice", new EntraGroupDetails("group-oid-1", "Totally Different Name"));
        // Matches the OID-keyed assignment.
        assertTrue(new OmniAuthItemACL("teamA/jobX").hasPermission2(member, Item.CONFIGURE));
    }

    @Test
    public void displayNameKeyedAssignment_doesNotMatchByDisplayName() {
        // Only an assignment whose userId is the OID should match; one keyed by display name must not.
        install(assignment("Dev Team", "GROUP", DEVELOPER, "teamA/jobX", "JOB"));
        Authentication member = user("alice", new EntraGroupDetails("group-oid-1", "Dev Team"));
        assertFalse(new OmniAuthItemACL("teamA/jobX").hasPermission2(member, Item.CONFIGURE));
    }

    // ── JIT assignments ─────────────────────────────────────────────────────────

    @Test
    public void jitAssignment_withoutActiveRequest_grantsNavReadOnly() {
        OmniAuthAssignment a = assignment("alice", "USER", DEVELOPER, "teamA/jobX", "JOB");
        a.setAccessType("JIT");
        install(a);
        OmniAuthItemACL acl = new OmniAuthItemACL("teamA/jobX");
        Authentication alice = user("alice");

        // No active JIT request → the user may *see* the job but not build/configure it.
        assertTrue("JIT inactive still allows nav Read", acl.hasPermission2(alice, Item.READ));
        assertFalse("JIT inactive must NOT grant Build",     acl.hasPermission2(alice, Item.BUILD));
        assertFalse("JIT inactive must NOT grant Configure", acl.hasPermission2(alice, Item.CONFIGURE));
    }

    @Test
    public void jitAssignment_withActiveRequest_grantsFullRole() {
        OmniAuthAssignment a = assignment("alice", "USER", DEVELOPER, "teamA/jobX", "JOB");
        a.setAccessType("JIT");
        install(a);

        // Create an ACTIVE, non-expired JIT request for this exact user+scope.
        OmniAuthJitRequest r = OmniAuthJitRequest.create("alice", "teamA/jobX", "need access", 2, 4);
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        OmniAuthJitRequestStore.get().addRequest(r);

        OmniAuthItemACL acl = new OmniAuthItemACL("teamA/jobX");
        Authentication alice = user("alice");
        assertTrue("active JIT request unlocks Build",     acl.hasPermission2(alice, Item.BUILD));
        assertTrue("active JIT request unlocks Configure", acl.hasPermission2(alice, Item.CONFIGURE));
    }

    // ── custom-permission roles ──────────────────────────────────────────────────

    @Test
    public void customPermissionAssignment_grantsExactlyThosePermissions() {
        OmniAuthAssignment a = assignment("alice", "USER", "CUSTOM", "teamA/jobX", "JOB");
        a.setCustomPermissions(Arrays.asList("hudson.model.Item.Read", "hudson.model.Item.Build"));
        install(a);
        OmniAuthItemACL acl = new OmniAuthItemACL("teamA/jobX");
        Authentication alice = user("alice");

        assertTrue("custom grant includes Build",      acl.hasPermission2(alice, Item.BUILD));
        assertFalse("custom grant excludes Configure", acl.hasPermission2(alice, Item.CONFIGURE));
    }

    // ── invariant: the ACL never consults break-glass / TOTP state ───────────────

    @Test
    public void acl_sourceNeverReferencesBreakGlassOrTotp() throws Exception {
        // Break-glass and TOTP are real concepts elsewhere in the plugin (OmniAuthUserProperty et al).
        // Authorization decisions must depend ONLY on assignments + roles — never on a user's TOTP
        // enrolment or break-glass flag. This guards against a future change wiring either into the
        // permission path. (Structural invariant: the class must not reference those identifiers.)
        File src = new File("src/main/java/io/jenkins/plugins/omniauth/OmniAuthItemACL.java");
        assertTrue("source file should be resolvable from the module root: " + src.getAbsolutePath(),
                src.isFile());
        String body = Files.readString(src.toPath());
        assertFalse("ACL must not reference break-glass", body.toLowerCase().contains("breakglass")
                || body.toLowerCase().contains("break-glass"));
        assertFalse("ACL must not reference TOTP", body.toLowerCase().contains("totp"));
    }
}
