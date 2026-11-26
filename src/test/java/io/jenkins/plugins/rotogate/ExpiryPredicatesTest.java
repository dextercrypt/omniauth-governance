package io.jenkins.plugins.rotogate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the time-based predicates that govern access revocation. The security
 * requirement is that a CORRUPT / unparseable timestamp FAILS CLOSED - i.e. the grant is
 * treated as expired / timed out and revoked, never silently kept alive. These are plain
 * POJOs, so no JenkinsRule is needed.
 */
public class ExpiryPredicatesTest {

    private static String past()    { return Instant.now().minus(1, ChronoUnit.HOURS).toString(); }
    private static String future()  { return Instant.now().plus(1, ChronoUnit.HOURS).toString(); }
    private static String daysAgo(int d)   { return Instant.now().minus(d, ChronoUnit.DAYS).toString(); }
    private static String daysAhead(int d) { return Instant.now().plus(d, ChronoUnit.DAYS).toString(); }

    private static final String CORRUPT  = "not-a-timestamp";
    private static final String CORRUPT2 = "2020-99-99T99:99:99Z"; // well-formed-ish but invalid

    /** Force a blank into a field whose setter would otherwise null it, to exercise the isBlank() guard. */
    private static void setField(Object target, String field, String value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ───────────────────────── RotogateAssignment.isExpired() ─────────────────────────

    @Test public void assignment_pastExpiry_isExpired() {
        RotogateAssignment a = new RotogateAssignment();
        a.setExpiresAt(past());
        assertTrue(a.isExpired());
    }

    @Test public void assignment_futureExpiry_notExpired() {
        RotogateAssignment a = new RotogateAssignment();
        a.setExpiresAt(future());
        assertFalse(a.isExpired());
    }

    @Test public void assignment_nullExpiry_notExpired() {
        RotogateAssignment a = new RotogateAssignment();
        a.setExpiresAt(null);
        assertFalse(a.isExpired(), "no expiry date means never auto-expires");
    }

    @Test public void assignment_blankExpiry_notExpired() throws Exception {
        RotogateAssignment a = new RotogateAssignment();
        setField(a, "expiresAt", "   "); // setter would null it; force blank directly
        assertFalse(a.isExpired());
    }

    @Test public void assignment_corruptExpiry_failsClosed() {
        RotogateAssignment a = new RotogateAssignment();
        a.setExpiresAt(CORRUPT);
        assertTrue(a.isExpired(), "corrupt expiry must be treated as EXPIRED (fail closed)");
    }

    @Test public void assignment_corruptExpiry2_failsClosed() {
        RotogateAssignment a = new RotogateAssignment();
        a.setExpiresAt(CORRUPT2);
        assertTrue(a.isExpired());
    }

    // ───────────────────────── RotogateAssignment.isReviewDue() ───────────────────────

    @Test public void reviewDue_withExpiryDate_excluded() {
        RotogateAssignment a = new RotogateAssignment();
        a.setExpiresAt(future());
        a.setReviewedAt(daysAgo(1000));
        assertFalse(a.isReviewDue(90), "assignments with any expiry are excluded from review");
    }

    @Test public void reviewDue_recentlyReviewed_notDue() {
        RotogateAssignment a = new RotogateAssignment();
        a.setReviewedAt(Instant.now().toString());
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_reviewedLongAgo_isDue() {
        RotogateAssignment a = new RotogateAssignment();
        a.setReviewedAt(daysAgo(100));
        assertTrue(a.isReviewDue(90));
    }

    @Test public void reviewDue_corruptReviewedAt_notDue_documented() {
        RotogateAssignment a = new RotogateAssignment();
        a.setReviewedAt(CORRUPT);
        // Documented behavior: an unparseable reviewedAt short-circuits to not-due.
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_grantedLongAgo_isDue() {
        RotogateAssignment a = new RotogateAssignment();
        a.setGrantedAt(daysAgo(100));
        assertTrue(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_grantedRecently_notDue() {
        RotogateAssignment a = new RotogateAssignment();
        a.setGrantedAt(daysAgo(1));
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_corruptGrantedAt_notDue_documented() {
        RotogateAssignment a = new RotogateAssignment();
        a.setGrantedAt(CORRUPT);
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_noGrant_isDue() {
        RotogateAssignment a = new RotogateAssignment();
        // fresh: no reviewedAt, no grantedAt, no expiry -> unknown age -> immediately overdue
        assertTrue(a.isReviewDue(90));
    }

    // ───────────────────────── RotogateJitRequest.isTimedOut() ────────────────────────

    private static RotogateJitRequest jit(String status) {
        RotogateJitRequest r = new RotogateJitRequest();
        r.setStatus(status);
        return r;
    }

    @Test public void jitTimedOut_pendingPastDeadline_isTimedOut() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(past());
        assertTrue(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingFutureDeadline_notTimedOut() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(future());
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_notPending_notTimedOut() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_ACTIVE);
        r.setApprovalDeadline(past()); // past deadline but not PENDING -> irrelevant
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingNullDeadline_notTimedOut() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(null);
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingBlankDeadline_notTimedOut() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_PENDING);
        r.setApprovalDeadline("   ");
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingCorruptDeadline_failsClosed() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(CORRUPT);
        assertTrue(r.isTimedOut(), "corrupt deadline must be treated as TIMED OUT (fail closed)");
    }

    // ───────────────────────── RotogateJitRequest.isExpiredActive() ───────────────────

    @Test public void jitExpiredActive_activePastExpiry_isExpired() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(past());
        assertTrue(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeFutureExpiry_notExpired() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(future());
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_notActive_notExpired() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_PENDING);
        r.setExpiresAt(past()); // past expiry but not ACTIVE -> irrelevant
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeNullExpiry_notExpired() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(null);
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeBlankExpiry_notExpired() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_ACTIVE);
        r.setExpiresAt("   ");
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeCorruptExpiry_failsClosed() {
        RotogateJitRequest r = jit(RotogateJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(CORRUPT);
        assertTrue(r.isExpiredActive(), "corrupt expiry must be treated as EXPIRED (fail closed)");
    }

    // ─────────────────── RotogateAccountExpiryProperty.isExpired() ─────────────────────

    @Test public void accountExpiry_pastExpiry_isExpired() {
        assertTrue(new RotogateAccountExpiryProperty(past()).isExpired());
    }

    @Test public void accountExpiry_futureExpiry_notExpired() {
        assertFalse(new RotogateAccountExpiryProperty(future()).isExpired());
    }

    @Test public void accountExpiry_nullExpiry_notExpired() {
        assertFalse(new RotogateAccountExpiryProperty(null).isExpired());
    }

    @Test public void accountExpiry_blankExpiry_notExpired() {
        assertFalse(new RotogateAccountExpiryProperty("   ").isExpired());
    }

    @Test public void accountExpiry_corruptExpiry_failsClosed() {
        assertTrue(new RotogateAccountExpiryProperty(CORRUPT).isExpired(), "corrupt account expiry must fail closed (treat as expired)");
    }

    // ─────────────── RotogateAccountExpiryProperty.daysUntilExpiry() ───────────────────

    @Test public void accountExpiry_daysUntil_future_positive() {
        long d = new RotogateAccountExpiryProperty(daysAhead(2)).daysUntilExpiry();
        assertTrue(d >= 1, "future expiry should report a positive day count, got " + d);
    }

    @Test public void accountExpiry_daysUntil_past_minusOne() {
        assertEquals(-1, new RotogateAccountExpiryProperty(past()).daysUntilExpiry());
    }

    @Test public void accountExpiry_daysUntil_null_minusOne() {
        assertEquals(-1, new RotogateAccountExpiryProperty(null).daysUntilExpiry());
    }

    @Test public void accountExpiry_daysUntil_corrupt_minusOne() {
        assertEquals(-1, new RotogateAccountExpiryProperty(CORRUPT).daysUntilExpiry());
    }
}
