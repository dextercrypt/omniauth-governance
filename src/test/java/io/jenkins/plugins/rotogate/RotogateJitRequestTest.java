package io.jenkins.plugins.rotogate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-POJO unit tests for {@link RotogateJitRequest} and its {@link RotogateJitRequest.ApprovalEntry}.
 * No Jenkins required - exercises the state-check logic (isTimedOut / isExpiredActive incl.
 * corrupt-timestamp fail-closed), approval tracking (allMatch), identity lookup, status labels
 * and token generation strength.
 */
public class RotogateJitRequestTest {

    private static String pastIso()   { return Instant.now().minus(1, ChronoUnit.HOURS).toString(); }
    private static String futureIso()  { return Instant.now().plus(1, ChronoUnit.HOURS).toString(); }

    private RotogateJitRequest pending(String deadline) {
        RotogateJitRequest r = new RotogateJitRequest();
        r.setStatus(RotogateJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(deadline);
        return r;
    }

    private RotogateJitRequest active(String expiresAt) {
        RotogateJitRequest r = new RotogateJitRequest();
        r.setStatus(RotogateJitRequest.STATUS_ACTIVE);
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
        RotogateJitRequest r = pending(pastIso());
        r.setStatus(RotogateJitRequest.STATUS_ACTIVE);
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
        RotogateJitRequest r = active(pastIso());
        r.setStatus(RotogateJitRequest.STATUS_PENDING);
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
        RotogateJitRequest r = active(futureIso());
        r.setStatus(RotogateJitRequest.STATUS_PENDING);
        assertEquals(0, r.secondsRemaining());
    }

    // ── isFullyApproved (allMatch) ────────────────────────────────────────────

    @Test
    public void isFullyApproved_noEntries_isFalse() {
        assertFalse(new RotogateJitRequest().isFullyApproved());
    }

    @Test
    public void isFullyApproved_allApproved_isTrue() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x"));
        r.getApprovalEntries().forEach(e -> e.setDecision("APPROVED"));
        assertTrue(r.isFullyApproved());
    }

    @Test
    public void isFullyApproved_onePending_isFalse() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x"));
        r.getApprovalEntries().get(0).setDecision("APPROVED");
        // second stays pending
        assertFalse(r.isFullyApproved(), "A single pending approver blocks full approval");
    }

    @Test
    public void isFullyApproved_oneDenied_isFalse() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x"));
        r.getApprovalEntries().get(0).setDecision("APPROVED");
        r.getApprovalEntries().get(1).setDecision("DENIED");
        assertFalse(r.isFullyApproved());
    }

    // ── approved/total counts ─────────────────────────────────────────────────

    @Test
    public void approvedAndTotalCounts_trackDecisions() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x", "c@x"));
        r.getApprovalEntries().get(0).setDecision("APPROVED");
        r.getApprovalEntries().get(1).setDecision("APPROVED");
        assertEquals(2, r.getApprovedCount());
        assertEquals(3, r.getTotalApprovers());
    }

    // ── findPendingEntryByIdentity / findEntryByToken ─────────────────────────

    @Test
    public void findPendingEntryByIdentity_pendingMatch_returnsEntry() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        RotogateJitRequest.ApprovalEntry e = r.findPendingEntryByIdentity("bob@x");
        assertNotNull(e);
        assertEquals("bob@x", e.getApproverIdentity());
    }

    @Test
    public void findPendingEntryByIdentity_alreadyDecided_returnsNull() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        r.findPendingEntryByIdentity("alice@x").setDecision("APPROVED");
        assertNull(r.findPendingEntryByIdentity("alice@x"), "A decided entry is no longer pending");
    }

    @Test
    public void findPendingEntryByIdentity_unknownIdentity_returnsNull() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        assertNull(r.findPendingEntryByIdentity("eve@x"));
    }

    @Test
    public void findPendingEntryByIdentity_null_returnsNull() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x"));
        assertNull(r.findPendingEntryByIdentity(null));
    }

    @Test
    public void findEntryByToken_matchesToken_andNullForUnknown() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        String token = r.getApprovalEntries().get(1).getToken();
        assertSame(r.getApprovalEntries().get(1), r.findEntryByToken(token));
        assertNull(r.findEntryByToken("no-such-token"));
        assertNull(r.findEntryByToken(null));
    }

    // ── isCurrentUserApprover (pending-approver check) ────────────────────────

    @Test
    public void isCurrentUserApprover_pendingApprover_isTrue() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        assertTrue(r.isCurrentUserApprover("alice@x"));
    }

    @Test
    public void isCurrentUserApprover_decidedApprover_isFalse() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x", "bob@x"));
        r.findPendingEntryByIdentity("alice@x").setDecision("APPROVED");
        assertFalse(r.isCurrentUserApprover("alice@x"), "A user who already decided is no longer a pending approver");
    }

    @Test
    public void isCurrentUserApprover_nonApprover_isFalse() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("alice@x"));
        assertFalse(r.isCurrentUserApprover("eve@x"));
        assertFalse(r.isCurrentUserApprover(null));
    }

    // ── ApprovalEntry decision helpers ────────────────────────────────────────

    @Test
    public void approvalEntry_decisionHelpers_reflectState() {
        RotogateJitRequest.ApprovalEntry e = RotogateJitRequest.ApprovalEntry.create("a@x");
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
        assertEquals("a@x", RotogateJitRequest.ApprovalEntry.create("  a@x  ").getApproverIdentity());
    }

    // ── Token strength (generated here via UUID) ──────────────────────────────

    @Test
    public void approvalEntry_token_isUuidLength() {
        assertEquals(36, RotogateJitRequest.ApprovalEntry.create("a@x").getToken().length());
    }

    @Test
    public void approvalEntry_tokens_areUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(RotogateJitRequest.ApprovalEntry.create("a@x").getToken());
        }
        assertEquals(500, tokens.size(), "All generated tokens must be unique");
    }

    @Test
    public void initApprovalEntries_givesEachApproverADistinctToken() {
        RotogateJitRequest r = new RotogateJitRequest();
        r.initApprovalEntries(Arrays.asList("a@x", "b@x", "c@x"));
        List<RotogateJitRequest.ApprovalEntry> entries = r.getApprovalEntries();
        assertEquals(3, entries.size());
        Set<String> tokens = new HashSet<>();
        for (RotogateJitRequest.ApprovalEntry e : entries) tokens.add(e.getToken());
        assertEquals(3, tokens.size());
    }

    // ── create() factory ──────────────────────────────────────────────────────

    @Test
    public void create_setsPendingStatusAndDeadlineAndId() {
        RotogateJitRequest r = RotogateJitRequest.create("alice", "folder/job", "need access", 3, 4);
        assertFalse(r.getRequestId().isEmpty());
        assertEquals("alice", r.getRequesterId());
        assertEquals("folder/job", r.getScope());
        assertEquals("need access", r.getReason());
        assertEquals(3, r.getRequestedDurationHours());
        assertEquals(RotogateJitRequest.STATUS_PENDING, r.getStatus());
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
        assertEquals("Pending",   labelFor(RotogateJitRequest.STATUS_PENDING));
        assertEquals("Active",    labelFor(RotogateJitRequest.STATUS_ACTIVE));
        assertEquals("Denied",    labelFor(RotogateJitRequest.STATUS_DENIED));
        assertEquals("Expired",   labelFor(RotogateJitRequest.STATUS_EXPIRED));
        assertEquals("Timed Out", labelFor(RotogateJitRequest.STATUS_TIMED_OUT));
        assertEquals("Cancelled", labelFor(RotogateJitRequest.STATUS_CANCELLED));
        assertEquals("Revoked",   labelFor(RotogateJitRequest.STATUS_REVOKED));
    }

    @Test
    public void statusLabel_unknownStatus_echoesRaw() {
        assertEquals("WEIRD", labelFor("WEIRD"));
    }

    private static String labelFor(String status) {
        RotogateJitRequest r = new RotogateJitRequest();
        r.setStatus(status);
        return r.statusLabel();
    }
}
