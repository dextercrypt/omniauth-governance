package io.jenkins.plugins.omniauth;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Tests for {@link OmniAuthJitRequestStore} — the CopyOnWriteArrayList-backed JIT request store.
 * Uses JenkinsRule because the store extends GlobalConfiguration (load()/save() + cache
 * invalidation touch the running Jenkins). Each test gets a fresh JENKINS_HOME, so the store
 * starts empty; {@link #freshStore()} asserts that invariant.
 *
 * Priority coverage:
 *  - fail-closed active expiry (findActiveForUser re-checks isExpiredActive at call time)
 *  - the timed-out-approval regression (PENDING-but-past-deadline is NOT approvable via EITHER
 *    processApproverAction OR approveAsApprover, and flips to TIMED_OUT with no ACTIVE/expiresAt)
 *  - separation of duty (full approval needs ALL listed approvers; one actor cannot activate)
 *  - requester-ownership on cancel
 */
public class OmniAuthJitRequestStoreTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthJitRequestStore store;

    @Before
    public void freshStore() {
        store = OmniAuthJitRequestStore.get();
        assertNotNull(store);
        assertTrue("Each test must start from an empty store", store.getRecentRequests(100).isEmpty());
    }

    private static String pastIso()   { return Instant.now().minus(2, ChronoUnit.HOURS).toString(); }
    private static String futureIso() { return Instant.now().plus(2, ChronoUnit.HOURS).toString(); }

    /** A two-approver PENDING request with a generous future approval deadline. */
    private OmniAuthJitRequest pendingRequest(String user, String scope, int durationHours,
                                              String... approvers) {
        OmniAuthJitRequest r = OmniAuthJitRequest.create(user, scope, "reason", durationHours, 4);
        r.initApprovalEntries(Arrays.asList(approvers));
        return r;
    }

    // ── addRequest / findById / findByToken ───────────────────────────────────

    @Test
    public void addRequest_thenFindById_returnsSameInstance() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 2, "a@x", "b@x");
        store.addRequest(r);
        assertSame(r, store.findById(r.getRequestId()));
        assertNull(store.findById("no-such-id"));
        assertNull(store.findById(null));
    }

    @Test
    public void findByToken_locatesRequestOwningThatEntryToken() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 2, "a@x", "b@x");
        store.addRequest(r);
        String token = r.getApprovalEntries().get(0).getToken();
        assertSame(r, store.findByToken(token));
        assertNull(store.findByToken("bogus"));
        assertNull(store.findByToken(null));
    }

    // ── findActiveForUser (fail-closed on expiry) ─────────────────────────────

    @Test
    public void findActiveForUser_activeNotExpired_isReturned() {
        OmniAuthJitRequest r = pendingRequest("alice", "scopeA", 2, "a@x", "b@x");
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(futureIso());
        store.addRequest(r);
        assertSame(r, store.findActiveForUser("alice", "scopeA"));
    }

    @Test
    public void findActiveForUser_activeButExpired_isNotReturned_failClosed() {
        OmniAuthJitRequest r = pendingRequest("alice", "scopeA", 2, "a@x", "b@x");
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(pastIso()); // status still ACTIVE but window has elapsed
        store.addRequest(r);
        assertNull("An ACTIVE-but-expired grant must fail closed at check time",
                store.findActiveForUser("alice", "scopeA"));
    }

    @Test
    public void findActiveForUser_wrongUserOrScope_isNotReturned() {
        OmniAuthJitRequest r = pendingRequest("alice", "scopeA", 2, "a@x", "b@x");
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(futureIso());
        store.addRequest(r);
        assertNull(store.findActiveForUser("bob", "scopeA"));
        assertNull(store.findActiveForUser("alice", "scopeB"));
    }

    @Test
    public void findPendingForUser_matchesPendingOnly() {
        OmniAuthJitRequest r = pendingRequest("alice", "scopeA", 2, "a@x", "b@x");
        store.addRequest(r);
        assertSame(r, store.findPendingForUser("alice", "scopeA"));
        assertNull(store.findPendingForUser("alice", "scopeB"));

        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        assertNull("A non-pending request is not returned by findPendingForUser",
                store.findPendingForUser("alice", "scopeA"));
    }

    @Test
    public void getPendingCountAndList_reflectPendingRequests() {
        store.addRequest(pendingRequest("alice", "s1", 2, "a@x", "b@x"));
        OmniAuthJitRequest active = pendingRequest("bob", "s2", 2, "a@x", "b@x");
        active.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        store.addRequest(active);
        assertEquals(1, store.getPendingCount());
        assertEquals(1, store.getPendingRequests().size());
    }

    // ── processApproverAction: token-based approver decisions ─────────────────

    @Test
    public void processApproverAction_unknownToken_returnsTokenNotFound() {
        assertEquals(OmniAuthJitRequestStore.ActionResult.TOKEN_NOT_FOUND,
                store.processApproverAction("no-such-token", "APPROVED", ""));
    }

    @Test
    public void processApproverAction_partialApproval_staysPending() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        String firstToken = r.getApprovalEntries().get(0).getToken();

        OmniAuthJitRequestStore.ActionResult res =
                store.processApproverAction(firstToken, "APPROVED", "lgtm");

        assertEquals(OmniAuthJitRequestStore.ActionResult.PARTIAL_APPROVED, res);
        assertTrue("Still pending until ALL approvers sign off", r.isPending());
        assertNull("No expiry until fully approved", r.getExpiresAt());
    }

    @Test
    public void processApproverAction_lastRequiredApprover_goesActiveWithExpiry() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        store.processApproverAction(r.getApprovalEntries().get(0).getToken(), "APPROVED", "");
        OmniAuthJitRequestStore.ActionResult res =
                store.processApproverAction(r.getApprovalEntries().get(1).getToken(), "APPROVED", "");

        assertEquals(OmniAuthJitRequestStore.ActionResult.ALL_APPROVED, res);
        assertTrue(r.isActive());
        assertNotNull("expiresAt must be set on activation", r.getExpiresAt());
        assertNotNull("approvedAt must be set on activation", r.getApprovedAt());
        // ~3h window → positive seconds remaining
        assertTrue(r.secondsRemaining() > 0);
    }

    @Test
    public void processApproverAction_denied_setsDeniedImmediately() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        OmniAuthJitRequestStore.ActionResult res =
                store.processApproverAction(r.getApprovalEntries().get(0).getToken(), "DENIED", "no");

        assertEquals(OmniAuthJitRequestStore.ActionResult.DENIED, res);
        assertEquals(OmniAuthJitRequest.STATUS_DENIED, r.getStatus());
        assertNull("A denied request never activates", r.getExpiresAt());
    }

    @Test
    public void processApproverAction_alreadyDecidedRequest_returnsAlreadyProcessed() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        String token = r.getApprovalEntries().get(0).getToken();
        store.processApproverAction(token, "DENIED", "no"); // request now DENIED

        // Re-using any token on a decided request is rejected
        assertEquals(OmniAuthJitRequestStore.ActionResult.ALREADY_PROCESSED,
                store.processApproverAction(token, "APPROVED", ""));
        assertEquals(OmniAuthJitRequestStore.ActionResult.ALREADY_PROCESSED,
                store.processApproverAction(r.getApprovalEntries().get(1).getToken(), "APPROVED", ""));
    }

    @Test
    public void processApproverAction_sameApproverTwice_secondIsAlreadyProcessed() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        String token = r.getApprovalEntries().get(0).getToken();
        assertEquals(OmniAuthJitRequestStore.ActionResult.PARTIAL_APPROVED,
                store.processApproverAction(token, "APPROVED", ""));
        // Same entry's token again — entry no longer pending
        assertEquals(OmniAuthJitRequestStore.ActionResult.ALREADY_PROCESSED,
                store.processApproverAction(token, "APPROVED", ""));
    }

    // ── HEADLINE REGRESSION: timed-out PENDING must NOT be approvable ──────────

    @Test
    public void processApproverAction_timedOutPending_refusedAndFlippedToTimedOut() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        r.setApprovalDeadline(pastIso()); // PENDING but approval window already elapsed
        store.addRequest(r);
        assertTrue("precondition: request is pending", r.isPending());
        assertTrue("precondition: request is timed out", r.isTimedOut());

        OmniAuthJitRequestStore.ActionResult res =
                store.processApproverAction(r.getApprovalEntries().get(0).getToken(), "APPROVED", "");

        assertEquals("A timed-out request must not be approvable",
                OmniAuthJitRequestStore.ActionResult.ALREADY_PROCESSED, res);
        assertEquals(OmniAuthJitRequest.STATUS_TIMED_OUT, r.getStatus());
        assertNull("No expiry may be set on a timed-out request", r.getExpiresAt());
        assertFalse(r.isActive());
        // The approval entry must remain undecided — the decision was refused, not recorded
        assertTrue(r.getApprovalEntries().get(0).isPending());
    }

    @Test
    public void approveAsApprover_timedOutPending_refusedAndFlippedToTimedOut() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        r.setApprovalDeadline(pastIso());
        store.addRequest(r);

        boolean activated = store.approveAsApprover(r.getRequestId(), "a@x");

        assertFalse("approveAsApprover must refuse a timed-out request", activated);
        assertEquals(OmniAuthJitRequest.STATUS_TIMED_OUT, r.getStatus());
        assertNull("No expiry may be set on a timed-out request", r.getExpiresAt());
        assertFalse(r.isActive());
        assertTrue(r.getApprovalEntries().get(0).isPending());
    }

    // ── approveAsApprover / denyAsApprover: listed-approver + separation of duty ─

    @Test
    public void approveAsApprover_nonApprover_isRejected() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertFalse("A user not on the approver list cannot act",
                store.approveAsApprover(r.getRequestId(), "eve@x"));
        assertTrue(r.isPending());
    }

    @Test
    public void approveAsApprover_singleApproverCannotUnilaterallyActivate() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        boolean activated = store.approveAsApprover(r.getRequestId(), "a@x");
        assertFalse("One of two approvers cannot alone activate (separation of duty)", activated);
        assertTrue(r.isPending());
        assertNull(r.getExpiresAt());
        assertEquals(1, r.getApprovedCount());
    }

    @Test
    public void approveAsApprover_allApprovers_activate() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertFalse(store.approveAsApprover(r.getRequestId(), "a@x"));
        boolean activated = store.approveAsApprover(r.getRequestId(), "b@x");
        assertTrue("Full approval requires ALL listed approvers", activated);
        assertTrue(r.isActive());
        assertNotNull(r.getExpiresAt());
    }

    @Test
    public void approveAsApprover_sameActorTwice_cannotSatisfyBothSlots() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertFalse(store.approveAsApprover(r.getRequestId(), "a@x"));
        // a@x has no remaining pending entry → second call finds no pending entry for them
        assertFalse("A single actor cannot fill a second approver slot",
                store.approveAsApprover(r.getRequestId(), "a@x"));
        assertTrue(r.isPending());
        assertEquals(1, r.getApprovedCount());
    }

    @Test
    public void denyAsApprover_listedApprover_deniesRequest() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertTrue(store.denyAsApprover(r.getRequestId(), "b@x", "nope"));
        assertEquals(OmniAuthJitRequest.STATUS_DENIED, r.getStatus());
        assertEquals("nope", r.getApproverComment());
    }

    @Test
    public void denyAsApprover_nonApprover_isRejected() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertFalse(store.denyAsApprover(r.getRequestId(), "eve@x", "nope"));
        assertTrue(r.isPending());
    }

    @Test
    public void approveAsApprover_unknownRequest_isFalse() {
        assertFalse(store.approveAsApprover("no-such-id", "a@x"));
    }

    // ── cancel: requester ownership ───────────────────────────────────────────

    @Test
    public void cancel_byOwner_cancelsPendingRequest() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertTrue(store.cancel(r.getRequestId(), "alice"));
        assertEquals(OmniAuthJitRequest.STATUS_CANCELLED, r.getStatus());
    }

    @Test
    public void cancel_byDifferentUser_isRefused() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertFalse("A different requesterId must not be able to cancel", store.cancel(r.getRequestId(), "mallory"));
        assertTrue("Request must remain pending after refused cancel", r.isPending());
    }

    @Test
    public void cancel_nonPendingRequest_isRefused() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        store.addRequest(r);
        assertFalse("Only pending requests can be cancelled", store.cancel(r.getRequestId(), "alice"));
    }

    // ── revoke: active requests only ──────────────────────────────────────────

    @Test
    public void revoke_activeRequest_revokesIt() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(futureIso());
        store.addRequest(r);
        assertTrue(store.revoke(r.getRequestId(), "admin"));
        assertEquals(OmniAuthJitRequest.STATUS_REVOKED, r.getStatus());
        assertEquals("admin", r.getApproverId());
    }

    @Test
    public void revoke_pendingRequest_isRefused() {
        OmniAuthJitRequest r = pendingRequest("alice", "s", 3, "a@x", "b@x");
        store.addRequest(r);
        assertFalse("Only active requests can be revoked", store.revoke(r.getRequestId(), "admin"));
        assertTrue(r.isPending());
    }

    // ── processExpiredAndTimedOut (worker driver) ─────────────────────────────

    @Test
    public void processExpiredAndTimedOut_flipsTimedOutAndExpired() {
        OmniAuthJitRequest timedOut = pendingRequest("alice", "s1", 3, "a@x", "b@x");
        timedOut.setApprovalDeadline(pastIso());
        store.addRequest(timedOut);

        OmniAuthJitRequest expired = pendingRequest("bob", "s2", 3, "a@x", "b@x");
        expired.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        expired.setExpiresAt(pastIso());
        store.addRequest(expired);

        OmniAuthJitRequest healthy = pendingRequest("carol", "s3", 3, "a@x", "b@x");
        store.addRequest(healthy);

        var transitioned = store.processExpiredAndTimedOut();

        assertEquals(2, transitioned.size());
        assertEquals(OmniAuthJitRequest.STATUS_TIMED_OUT, timedOut.getStatus());
        assertEquals(OmniAuthJitRequest.STATUS_EXPIRED, expired.getStatus());
        assertTrue("Healthy pending request is untouched", healthy.isPending());
    }

    // ── purgeOldRecords (retention) ───────────────────────────────────────────

    @Test
    public void purgeOldRecords_removesOldTerminalKeepsLiveAndRecent() {
        // Old terminal (cancelled, requested long ago, no expiry/approval times) → purged
        OmniAuthJitRequest oldTerminal = pendingRequest("alice", "s1", 3, "a@x", "b@x");
        oldTerminal.setStatus(OmniAuthJitRequest.STATUS_CANCELLED);
        oldTerminal.setRequestedAt(Instant.now().minus(100, ChronoUnit.DAYS).toString());
        store.addRequest(oldTerminal);

        // Recent terminal → kept
        OmniAuthJitRequest recentTerminal = pendingRequest("bob", "s2", 3, "a@x", "b@x");
        recentTerminal.setStatus(OmniAuthJitRequest.STATUS_DENIED);
        store.addRequest(recentTerminal);

        // Live pending (old requestedAt but NOT terminal) → kept
        OmniAuthJitRequest livePending = pendingRequest("carol", "s3", 3, "a@x", "b@x");
        livePending.setRequestedAt(Instant.now().minus(100, ChronoUnit.DAYS).toString());
        store.addRequest(livePending);

        int purged = store.purgeOldRecords(90);

        assertEquals(1, purged);
        assertNull("Old terminal record purged", store.findById(oldTerminal.getRequestId()));
        assertNotNull("Recent terminal kept", store.findById(recentTerminal.getRequestId()));
        assertNotNull("Live pending kept regardless of age", store.findById(livePending.getRequestId()));
    }

    @Test
    public void purgeOldRecords_zeroRetention_noOp() {
        OmniAuthJitRequest r = pendingRequest("alice", "s1", 3, "a@x", "b@x");
        r.setStatus(OmniAuthJitRequest.STATUS_CANCELLED);
        r.setRequestedAt(Instant.now().minus(100, ChronoUnit.DAYS).toString());
        store.addRequest(r);
        assertEquals(0, store.purgeOldRecords(0));
        assertNotNull(store.findById(r.getRequestId()));
    }

    // ── findLastRequestForUser ────────────────────────────────────────────────

    @Test
    public void findLastRequestForUser_returnsMostRecentByRequestedAt() {
        OmniAuthJitRequest older = pendingRequest("alice", "s", 3, "a@x", "b@x");
        older.setRequestedAt(Instant.now().minus(1, ChronoUnit.DAYS).toString());
        older.setStatus(OmniAuthJitRequest.STATUS_DENIED);
        store.addRequest(older);

        OmniAuthJitRequest newer = pendingRequest("alice", "s", 3, "a@x", "b@x");
        newer.setRequestedAt(Instant.now().toString());
        store.addRequest(newer);

        assertSame(newer, store.findLastRequestForUser("alice", "s"));
        assertNull(store.findLastRequestForUser("alice", "other"));
    }
}
