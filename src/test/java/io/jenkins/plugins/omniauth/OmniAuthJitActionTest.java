package io.jenkins.plugins.omniauth;

import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link OmniAuthJitAction} — the user-facing JIT endpoints (/omniauth-jit/).
 * Focus:
 *  - doCancel IDOR regression: user A cannot cancel user B's request (userId.equals(requesterId))
 *  - doRequest: rejects anonymous, requires job+reason, caps reason, caps duration to assignment
 *    max, requires >=2 approvers, dedupes pending/active, and is annotated @POST
 *
 * StaplerRequest/Response are java.lang.reflect.Proxy fakes (house style). The current
 * authentication is set with ACL.as2(...) using a UsernamePasswordAuthenticationToken built
 * directly (the user need not exist in a realm — avoids User.impersonate2()'s requirement).
 */
public class OmniAuthJitActionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthJitAction action;
    private OmniAuthJitRequestStore store;

    @Before
    public void setUp() {
        action = j.jenkins.getExtensionList(OmniAuthJitAction.class).get(0);
        store = OmniAuthJitRequestStore.get();
        assertNotNull(action);
        assertNotNull(store);
        assertTrue("Fresh store per test", store.getRecentRequests(100).isEmpty());
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "", Collections.emptyList());
    }

    private StaplerRequest fakeReq(Map<String, String> params) {
        return (StaplerRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ StaplerRequest.class },
                (proxy, method, args) -> {
                    if ("getParameter".equals(method.getName())) return params.get((String) args[0]);
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class)     return 0;
                    return null;
                });
    }

    /** StaplerResponse proxy that captures everything written through getWriter(). */
    private static final class CapturingResponse {
        final StringWriter body = new StringWriter();
        final StaplerResponse rsp;
        CapturingResponse() {
            final PrintWriter writer = new PrintWriter(body);
            rsp = (StaplerResponse) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ StaplerResponse.class },
                    (proxy, method, args) -> {
                        if ("getWriter".equals(method.getName())) return writer;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class)     return 0;
                        return null;
                    });
        }
        String json() { return body.toString(); }
    }

    private String doRequest(Authentication auth, Map<String, String> params) throws Exception {
        CapturingResponse cap = new CapturingResponse();
        try (ACLContext ignored = ACL.as2(auth)) {
            action.doRequest(fakeReq(params), cap.rsp);
        }
        return cap.json();
    }

    private String doCancel(Authentication auth, Map<String, String> params) throws Exception {
        CapturingResponse cap = new CapturingResponse();
        try (ACLContext ignored = ACL.as2(auth)) {
            action.doCancel(fakeReq(params), cap.rsp);
        }
        return cap.json();
    }

    private Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Seeds a JIT USER assignment for {@code user} covering {@code scope}. */
    private OmniAuthAssignment seedJitAssignment(String user, String scope, int maxDuration,
                                                 String... approvers) {
        OmniAuthAssignment a = new OmniAuthAssignment(
                user, "USER", "", scope, "FOLDER", null, Instant.now().toString(), "admin");
        a.setAccessType("JIT");
        a.setApprovers(Arrays.asList(approvers));
        a.setMaxDurationHours(maxDuration);
        a.setApprovalTimeoutHours(4);
        OmniAuthAssignmentConfig.get().addAssignment(a);
        return a;
    }

    // ── @POST annotations ──────────────────────────────────────────────────────

    @Test
    public void doRequest_and_doCancel_areAnnotatedPost() throws Exception {
        Method req = OmniAuthJitAction.class.getMethod("doRequest", StaplerRequest.class, StaplerResponse.class);
        Method cancel = OmniAuthJitAction.class.getMethod("doCancel", StaplerRequest.class, StaplerResponse.class);
        assertNotNull("doRequest must be @POST (CSRF-protected)", req.getAnnotation(POST.class));
        assertNotNull("doCancel must be @POST (CSRF-protected)", cancel.getAnnotation(POST.class));
    }

    // ── doCancel: IDOR regression ──────────────────────────────────────────────

    @Test
    public void doCancel_userCannotCancelAnotherUsersRequest() throws Exception {
        OmniAuthJitRequest bobReq = OmniAuthJitRequest.create("bob", "folder/job", "r", 2, 4);
        bobReq.initApprovalEntries(Arrays.asList("x@x", "y@x"));
        store.addRequest(bobReq);

        // Alice attempts to cancel bob's request by guessing/learning its id
        String json = doCancel(user("alice"), params("requestId", bobReq.getRequestId()));

        assertTrue("Must be rejected as not-your-request, got: " + json, json.contains("Not your request"));
        assertTrue("Bob's request must stay pending (NOT cancelled)", bobReq.isPending());
        assertEquals(OmniAuthJitRequest.STATUS_PENDING, bobReq.getStatus());
    }

    @Test
    public void doCancel_ownerCanCancelOwnPendingRequest() throws Exception {
        OmniAuthJitRequest bobReq = OmniAuthJitRequest.create("bob", "folder/job", "r", 2, 4);
        bobReq.initApprovalEntries(Arrays.asList("x@x", "y@x"));
        store.addRequest(bobReq);

        String json = doCancel(user("bob"), params("requestId", bobReq.getRequestId()));

        assertTrue("Owner cancel returns ok, got: " + json, json.contains("\"ok\":true"));
        assertEquals(OmniAuthJitRequest.STATUS_CANCELLED, bobReq.getStatus());
    }

    @Test
    public void doCancel_cannotRevokeAnotherUsersActiveGrant() throws Exception {
        // The active branch calls revoke(), which does not itself enforce ownership —
        // the action's userId.equals(requesterId) guard is what stops the IDOR.
        OmniAuthJitRequest bobReq = OmniAuthJitRequest.create("bob", "folder/job", "r", 2, 4);
        bobReq.initApprovalEntries(Arrays.asList("x@x", "y@x"));
        bobReq.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        bobReq.setExpiresAt(Instant.now().plusSeconds(3600).toString());
        store.addRequest(bobReq);

        String json = doCancel(user("mallory"), params("requestId", bobReq.getRequestId()));

        assertTrue("Must be rejected, got: " + json, json.contains("Not your request"));
        assertTrue("Bob's grant must remain active", bobReq.isActive());
    }

    @Test
    public void doCancel_anonymous_isRejected() throws Exception {
        String json = doCancel(Jenkins.ANONYMOUS2, params("requestId", "whatever"));
        assertTrue(json.contains("Not authenticated"));
    }

    // ── doRequest: auth + validation ───────────────────────────────────────────

    @Test
    public void doRequest_anonymous_isRejected() throws Exception {
        String json = doRequest(Jenkins.ANONYMOUS2, params("job", "folder/job", "reason", "x"));
        assertTrue(json.contains("Not authenticated"));
        assertTrue("No request should be created for anonymous", store.getRecentRequests(100).isEmpty());
    }

    @Test
    public void doRequest_missingJob_isRejected() throws Exception {
        String json = doRequest(user("alice"), params("reason", "x"));
        assertTrue(json.contains("job is required"));
    }

    @Test
    public void doRequest_missingReason_isRejected() throws Exception {
        String json = doRequest(user("alice"), params("job", "folder/job"));
        assertTrue(json.contains("reason is required"));
    }

    @Test
    public void doRequest_noJitEligibility_isRejected() throws Exception {
        String json = doRequest(user("alice"), params("job", "folder/job", "reason", "need it"));
        assertTrue("No assignment → no eligibility, got: " + json,
                json.contains("No JIT eligibility"));
    }

    @Test
    public void doRequest_fewerThanTwoApprovers_isRejected() throws Exception {
        seedJitAssignment("alice", "folder/job", 4, "only@x"); // single approver
        String json = doRequest(user("alice"), params("job", "folder/job", "reason", "need it"));
        assertTrue("Requires >=2 approvers, got: " + json,
                json.contains("at least 2 approvers"));
    }

    @Test
    public void doRequest_happyPath_createsPendingRequestWithApprovers() throws Exception {
        seedJitAssignment("alice", "folder/job", 4, "x@x", "y@x");
        String json = doRequest(user("alice"),
                params("job", "folder/job", "reason", "deploy hotfix", "durationHours", "2"));

        assertTrue("Should succeed, got: " + json, json.contains("\"ok\":true"));
        OmniAuthJitRequest r = store.findPendingForUser("alice", "folder/job");
        assertNotNull("A pending request should now exist", r);
        assertEquals(2, r.getTotalApprovers());
        assertEquals(2, r.getRequestedDurationHours());
        assertEquals("deploy hotfix", r.getReason());
    }

    @Test
    public void doRequest_cappedToAssignmentMaxDuration() throws Exception {
        seedJitAssignment("alice", "folder/job", 4, "x@x", "y@x"); // max 4h
        doRequest(user("alice"),
                params("job", "folder/job", "reason", "r", "durationHours", "999"));
        OmniAuthJitRequest r = store.findPendingForUser("alice", "folder/job");
        assertNotNull(r);
        assertEquals("Duration must be capped at assignment max (4h)", 4, r.getRequestedDurationHours());
    }

    @Test
    public void doRequest_longReasonIsCappedTo1000Chars() throws Exception {
        seedJitAssignment("alice", "folder/job", 4, "x@x", "y@x");
        String longReason = "z".repeat(5000);
        doRequest(user("alice"),
                params("job", "folder/job", "reason", longReason, "durationHours", "1"));
        OmniAuthJitRequest r = store.findPendingForUser("alice", "folder/job");
        assertNotNull(r);
        assertEquals("Reason must be truncated to 1000 chars", 1000, r.getReason().length());
    }

    @Test
    public void doRequest_duplicatePending_isRejected() throws Exception {
        seedJitAssignment("alice", "folder/job", 4, "x@x", "y@x");
        doRequest(user("alice"), params("job", "folder/job", "reason", "first", "durationHours", "1"));
        String json = doRequest(user("alice"), params("job", "folder/job", "reason", "second", "durationHours", "1"));
        assertTrue("A second pending request for the same scope is blocked, got: " + json,
                json.contains("already pending"));
        assertEquals("Still only one request", 1, store.getRecentRequests(100).size());
    }

    @Test
    public void doRequest_activeGrantExists_isRejected() throws Exception {
        seedJitAssignment("alice", "folder/job", 4, "x@x", "y@x");
        OmniAuthJitRequest existing = OmniAuthJitRequest.create("alice", "folder/job", "r", 2, 4);
        existing.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        existing.setExpiresAt(Instant.now().plusSeconds(3600).toString());
        store.addRequest(existing);

        String json = doRequest(user("alice"), params("job", "folder/job", "reason", "again", "durationHours", "1"));
        assertTrue("An active grant blocks a new request, got: " + json,
                json.contains("already active"));
    }

    @Test
    public void doRequest_folderAssignmentCoversNestedJob() throws Exception {
        // Assignment scope is the parent folder; the requested job is nested under it.
        seedJitAssignment("alice", "folder", 4, "x@x", "y@x");
        String json = doRequest(user("alice"),
                params("job", "folder/sub/job", "reason", "nested", "durationHours", "1"));
        assertTrue("Folder-scoped JIT assignment covers nested jobs, got: " + json,
                json.contains("\"ok\":true"));
        // Request is created at the assignment scope, not the job path
        assertNotNull(store.findPendingForUser("alice", "folder"));
    }
}
