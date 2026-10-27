package io.jenkins.plugins.omniauth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-POJO unit tests for {@link OmniAuthJitRequest} and its {@link OmniAuthJitRequest.ApprovalEntry}.
 * No Jenkins required — exercises the state-check logic (isTimedOut / isExpiredActive incl.
 * corrupt-timestamp fail-closed), approval tracking (allMatch), identity lookup, status labels
 * and token generation strength.
 */
public class OmniAuthJitRequestTest {

    private static String pastIso()   { return Instant.now().minus(1, ChronoUnit.HOURS).toString(); }
    private static String futureIso()  { return Instant.now().plus(1, ChronoUnit.HOURS).toString(); }

    private OmniAuthJitRequest pending(String deadline) {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.setStatus(OmniAuthJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(deadline);
        return r;
    }

    private OmniAuthJitRequest active(String expiresAt) {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(expiresAt);
        return r;
    }

    // ── isTimedOut ────────────────────────────────────────────────────────────

    @Test
    public void isTimedOut_pendingWithPastDeadline_isTrue() {
        assertTrue(pending(pastIso()).isTimedOut());
    }

    @Test
    public void isTimedOut_pendingWithFutureDeadline_isFalse() {
        assertFalse(pending(futureIso()).isTimedOut());
    }

    @Test
    public void isTimedOut_pendingWithNullDeadline_isFalse() {
        assertFalse(pending(null).isTimedOut());
    }

    @Test
    public void isTimedOut_pendingWithBlankDeadline_isFalse() {
        assertFalse(pending("   ").isTimedOut());
    }

    @Test
    public void isTimedOut_corruptDeadline_failsClosedTrue() {
        assertTrue(pending("not-a-timestamp").isTimedOut(), "A corrupt deadline must be treated as timed out (fail closed)");
    }

    @Test
    public void isTimedOut_nonPendingStatus_isFalseEvenWithPastDeadline() {
        OmniAuthJitRequest r = pending(pastIso());
        r.setStatus(OmniAuthJitRequest.STATUS_ACTIVE);
        assertFalse(r.isTimedOut(), "Only PENDING requests can time out");
    }

    // ── isExpiredActive ───────────────────────────────────────────────────────

    @Test
    public void isExpiredActive_activeWithPastExpiry_isTrue() {
        assertTrue(active(pastIso()).isExpiredActive());
    }

    @Test
    public void isExpiredActive_activeWithFutureExpiry_isFalse() {
        assertFalse(active(futureIso()).isExpiredActive());
    }

    @Test
    public void isExpiredActive_activeWithNullExpiry_isFalse() {
        assertFalse(active(null).isExpiredActive());
    }

    @Test
    public void isExpiredActive_corruptExpiry_failsClosedTrue() {
        assertTrue(active("garbage").isExpiredActive(), "A corrupt expiry must be treated as expired (fail closed)");
    }

    @Test
    public void isExpiredActive_nonActiveStatus_isFalse() {
        OmniAuthJitRequest r = active(pastIso());
        r.setStatus(OmniAuthJitRequest.STATUS_PENDING);
        assertFalse(r.isExpiredActive(), "Only ACTIVE requests can be expired-active");
    }

    // ── secondsRemaining ──────────────────────────────────────────────────────

    @Test
    public void secondsRemaining_activeFutureExpiry_isPositive() {
        assertTrue(active(futureIso()).secondsRemaining() > 0);
    }

    @Test
    public void secondsRemaining_activePastExpiry_isZero() {
        assertEquals(0, active(pastIso()).secondsRemaining());
    }

    @Test
    public void secondsRemaining_nonActive_isZero() {
        OmniAuthJitRequest r = active(futureIso());
        r.setStatus(OmniAuthJitRequest.STATUS_PENDING);
        assertEquals(0, r.secondsRemaining());
    }

    // ── isFullyApproved (allMatch) ────────────────────────────────────────────

    @Test
    public void isFullyApproved_noEntries_isFalse() {
        assertFalse(new OmniAuthJitRequest().isFullyApproved());
    }

    @Test
    public void isFullyApproved_allApproved_isTrue() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x"));
        r.getApprovalEntries().forEach(e -> e.setDecision("APPROVED"));
        assertTrue(r.isFullyApproved());
    }

    @Test
    public void isFullyApproved_onePending_isFalse() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x"));
        r.getApprovalEntries().get(0).setDecision("APPROVED");
        // second stays pending
        assertFalse(r.isFullyApproved(), "A single pending approver blocks full approval");
    }

    @Test
    public void isFullyApproved_oneDenied_isFalse() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x"));
        r.getApprovalEntries().get(0).setDecision("APPROVED");
        r.getApprovalEntries().get(1).setDecision("DENIED");
        assertFalse(r.isFullyApproved());
    }

    // ── approved/total counts ─────────────────────────────────────────────────

    @Test
    public void approvedAndTotalCounts_trackDecisions() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x", "c@x"));
        r.getApprovalEntries().get(0).setDecision("APPROVED");
        r.getApprovalEntries().get(1).setDecision("APPROVED");
        assertEquals(2, r.getApprovedCount());
        assertEquals(3, r.getTotalApprovers());
    }

    // ── findPendingEntryByIdentity / findEntryByToken ─────────────────────────

    @Test
    public void findPendingEntryByIdentity_pendingMatch_returnsEntry() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        OmniAuthJitRequest.ApprovalEntry e = r.findPendingEntryByIdentity("bob@x");
        assertNotNull(e);
        assertEquals("bob@x", e.getApproverIdentity());
    }

    @Test
    public void findPendingEntryByIdentity_alreadyDecided_returnsNull() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        r.findPendingEntryByIdentity("alice@x").setDecision("APPROVED");
        assertNull(r.findPendingEntryByIdentity("alice@x"), "A decided entry is no longer pending");
    }

    @Test
    public void findPendingEntryByIdentity_unknownIdentity_returnsNull() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        assertNull(r.findPendingEntryByIdentity("eve@x"));
    }

    @Test
    public void findPendingEntryByIdentity_null_returnsNull() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x"));
        assertNull(r.findPendingEntryByIdentity(null));
    }

    @Test
    public void findEntryByToken_matchesToken_andNullForUnknown() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        String token = r.getApprovalEntries().get(1).getToken();
        assertSame(r.getApprovalEntries().get(1), r.findEntryByToken(token));
        assertNull(r.findEntryByToken("no-such-token"));
        assertNull(r.findEntryByToken(null));
    }

    // ── isCurrentUserApprover (pending-approver check) ────────────────────────

    @Test
    public void isCurrentUserApprover_pendingApprover_isTrue() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        assertTrue(r.isCurrentUserApprover("alice@x"));
    }

    @Test
    public void isCurrentUserApprover_decidedApprover_isFalse() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        r.findPendingEntryByIdentity("alice@x").setDecision("APPROVED");
        assertFalse(r.isCurrentUserApprover("alice@x"), "A user who already decided is no longer a pending approver");
    }

    @Test
    public void isCurrentUserApprover_nonApprover_isFalse() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x"));
        assertFalse(r.isCurrentUserApprover("eve@x"));
        assertFalse(r.isCurrentUserApprover(null));
    }

    // ── ApprovalEntry decision helpers ────────────────────────────────────────

    @Test
    public void approvalEntry_decisionHelpers_reflectState() {
        OmniAuthJitRequest.ApprovalEntry e = OmniAuthJitRequest.ApprovalEntry.create("a@x");
        assertTrue(e.isPending());
        assertFalse(e.isApproved());
        assertFalse(e.isDenied());

        e.setDecision("APPROVED");
        assertFalse(e.isPending());
        assertTrue(e.isApproved());

        e.setDecision("DENIED");
        assertTrue(e.isDenied());
        assertFalse(e.isApproved());
    }

    @Test
    public void approvalEntry_create_trimsIdentity() {
        assertEquals("a@x", OmniAuthJitRequest.ApprovalEntry.create("  a@x  ").getApproverIdentity());
    }

    // ── Token strength (generated here via UUID) ──────────────────────────────

    @Test
    public void approvalEntry_token_isUuidLength() {
        assertEquals(36, OmniAuthJitRequest.ApprovalEntry.create("a@x").getToken().length());
    }

    @Test
    public void approvalEntry_tokens_areUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(OmniAuthJitRequest.ApprovalEntry.create("a@x").getToken());
        }
        assertEquals(500, tokens.size(), "All generated tokens must be unique");
    }

    @Test
    public void initApprovalEntries_givesEachApproverADistinctToken() {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x", "c@x"));
        List<OmniAuthJitRequest.ApprovalEntry> entries = r.getApprovalEntries();
        assertEquals(3, entries.size());
        Set<String> tokens = new HashSet<>();
        for (OmniAuthJitRequest.ApprovalEntry e : entries) tokens.add(e.getToken());
        assertEquals(3, tokens.size());
    }

    // ── create() factory ──────────────────────────────────────────────────────

    @Test
    public void create_setsPendingStatusAndDeadlineAndId() {
        OmniAuthJitRequest r = OmniAuthJitRequest.create("alice", "folder/job", "need access", 3, 4);
        assertFalse(r.getRequestId().isEmpty());
        assertEquals("alice", r.getRequesterId());
        assertEquals("folder/job", r.getScope());
        assertEquals("need access", r.getReason());
        assertEquals(3, r.getRequestedDurationHours());
        assertEquals(OmniAuthJitRequest.STATUS_PENDING, r.getStatus());
        assertTrue(r.isPending());
        assertNotNull(r.getApprovalDeadline());
        // 4h approval window → deadline is in the future and not yet timed out
        assertFalse(r.isTimedOut());
        // Not yet approved → no expiry set
        assertNull(r.getExpiresAt());
    }

    // ── statusLabel ───────────────────────────────────────────────────────────

    @Test
    public void statusLabel_mapsEachStatus() {
        assertEquals("Pending",   labelFor(OmniAuthJitRequest.STATUS_PENDING));
        assertEquals("Active",    labelFor(OmniAuthJitRequest.STATUS_ACTIVE));
        assertEquals("Denied",    labelFor(OmniAuthJitRequest.STATUS_DENIED));
        assertEquals("Expired",   labelFor(OmniAuthJitRequest.STATUS_EXPIRED));
        assertEquals("Timed Out", labelFor(OmniAuthJitRequest.STATUS_TIMED_OUT));
        assertEquals("Cancelled", labelFor(OmniAuthJitRequest.STATUS_CANCELLED));
        assertEquals("Revoked",   labelFor(OmniAuthJitRequest.STATUS_REVOKED));
    }

    @Test
    public void statusLabel_unknownStatus_echoesRaw() {
        assertEquals("WEIRD", labelFor("WEIRD"));
    }

    private static String labelFor(String status) {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.setStatus(status);
        return r.statusLabel();
    }
}
