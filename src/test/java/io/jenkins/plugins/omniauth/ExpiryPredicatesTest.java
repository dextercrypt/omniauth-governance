package io.jenkins.plugins.omniauth;

import org.junit.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for the time-based predicates that govern access revocation. The security
 * requirement is that a CORRUPT / unparseable timestamp FAILS CLOSED — i.e. the grant is
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

    // ───────────────────────── OmniAuthAssignment.isExpired() ─────────────────────────

    @Test public void assignment_pastExpiry_isExpired() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setExpiresAt(past());
        assertTrue(a.isExpired());
    }

    @Test public void assignment_futureExpiry_notExpired() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setExpiresAt(future());
        assertFalse(a.isExpired());
    }

    @Test public void assignment_nullExpiry_notExpired() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setExpiresAt(null);
        assertFalse("no expiry date means never auto-expires", a.isExpired());
    }

    @Test public void assignment_blankExpiry_notExpired() throws Exception {
        OmniAuthAssignment a = new OmniAuthAssignment();
        setField(a, "expiresAt", "   "); // setter would null it; force blank directly
        assertFalse(a.isExpired());
    }

    @Test public void assignment_corruptExpiry_failsClosed() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setExpiresAt(CORRUPT);
        assertTrue("corrupt expiry must be treated as EXPIRED (fail closed)", a.isExpired());
    }

    @Test public void assignment_corruptExpiry2_failsClosed() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setExpiresAt(CORRUPT2);
        assertTrue(a.isExpired());
    }

    // ───────────────────────── OmniAuthAssignment.isReviewDue() ───────────────────────

    @Test public void reviewDue_withExpiryDate_excluded() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setExpiresAt(future());
        a.setReviewedAt(daysAgo(1000));
        assertFalse("assignments with any expiry are excluded from review", a.isReviewDue(90));
    }

    @Test public void reviewDue_recentlyReviewed_notDue() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setReviewedAt(Instant.now().toString());
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_reviewedLongAgo_isDue() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setReviewedAt(daysAgo(100));
        assertTrue(a.isReviewDue(90));
    }

    @Test public void reviewDue_corruptReviewedAt_notDue_documented() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setReviewedAt(CORRUPT);
        // Documented behavior: an unparseable reviewedAt short-circuits to not-due.
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_grantedLongAgo_isDue() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setGrantedAt(daysAgo(100));
        assertTrue(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_grantedRecently_notDue() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setGrantedAt(daysAgo(1));
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_corruptGrantedAt_notDue_documented() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        a.setGrantedAt(CORRUPT);
        assertFalse(a.isReviewDue(90));
    }

    @Test public void reviewDue_noReview_noGrant_isDue() {
        OmniAuthAssignment a = new OmniAuthAssignment();
        // fresh: no reviewedAt, no grantedAt, no expiry -> unknown age -> immediately overdue
        assertTrue(a.isReviewDue(90));
    }

    // ───────────────────────── OmniAuthJitRequest.isTimedOut() ────────────────────────

    private static OmniAuthJitRequest jit(String status) {
        OmniAuthJitRequest r = new OmniAuthJitRequest();
        r.setStatus(status);
        return r;
    }

    @Test public void jitTimedOut_pendingPastDeadline_isTimedOut() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(past());
        assertTrue(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingFutureDeadline_notTimedOut() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(future());
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_notPending_notTimedOut() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setApprovalDeadline(past()); // past deadline but not PENDING -> irrelevant
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingNullDeadline_notTimedOut() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(null);
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingBlankDeadline_notTimedOut() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_PENDING);
        r.setApprovalDeadline("   ");
        assertFalse(r.isTimedOut());
    }

    @Test public void jitTimedOut_pendingCorruptDeadline_failsClosed() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_PENDING);
        r.setApprovalDeadline(CORRUPT);
        assertTrue("corrupt deadline must be treated as TIMED OUT (fail closed)", r.isTimedOut());
    }

    // ───────────────────────── OmniAuthJitRequest.isExpiredActive() ───────────────────

    @Test public void jitExpiredActive_activePastExpiry_isExpired() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(past());
        assertTrue(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeFutureExpiry_notExpired() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(future());
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_notActive_notExpired() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_PENDING);
        r.setExpiresAt(past()); // past expiry but not ACTIVE -> irrelevant
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeNullExpiry_notExpired() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(null);
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeBlankExpiry_notExpired() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt("   ");
        assertFalse(r.isExpiredActive());
    }

    @Test public void jitExpiredActive_activeCorruptExpiry_failsClosed() {
        OmniAuthJitRequest r = jit(OmniAuthJitRequest.STATUS_ACTIVE);
        r.setExpiresAt(CORRUPT);
        assertTrue("corrupt expiry must be treated as EXPIRED (fail closed)", r.isExpiredActive());
    }

    // ─────────────────── OmniAuthAccountExpiryProperty.isExpired() ─────────────────────

    @Test public void accountExpiry_pastExpiry_isExpired() {
        assertTrue(new OmniAuthAccountExpiryProperty(past()).isExpired());
    }

    @Test public void accountExpiry_futureExpiry_notExpired() {
        assertFalse(new OmniAuthAccountExpiryProperty(future()).isExpired());
    }

    @Test public void accountExpiry_nullExpiry_notExpired() {
        assertFalse(new OmniAuthAccountExpiryProperty(null).isExpired());
    }

    @Test public void accountExpiry_blankExpiry_notExpired() {
        assertFalse(new OmniAuthAccountExpiryProperty("   ").isExpired());
    }

    @Test public void accountExpiry_corruptExpiry_failsClosed() {
        assertTrue("corrupt account expiry must fail closed (treat as expired)",
                new OmniAuthAccountExpiryProperty(CORRUPT).isExpired());
    }

    // ─────────────── OmniAuthAccountExpiryProperty.daysUntilExpiry() ───────────────────

    @Test public void accountExpiry_daysUntil_future_positive() {
        long d = new OmniAuthAccountExpiryProperty(daysAhead(2)).daysUntilExpiry();
        assertTrue("future expiry should report a positive day count, got " + d, d >= 1);
    }

    @Test public void accountExpiry_daysUntil_past_minusOne() {
        assertEquals(-1, new OmniAuthAccountExpiryProperty(past()).daysUntilExpiry());
    }

    @Test public void accountExpiry_daysUntil_null_minusOne() {
        assertEquals(-1, new OmniAuthAccountExpiryProperty(null).daysUntilExpiry());
    }

    @Test public void accountExpiry_daysUntil_corrupt_minusOne() {
        assertEquals(-1, new OmniAuthAccountExpiryProperty(CORRUPT).daysUntilExpiry());
    }
}
