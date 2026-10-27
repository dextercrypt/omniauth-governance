package io.jenkins.plugins.omniauth;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProvisioningRequestConfig}, the persistent request store.
 *
 * <p>The store extends {@link jenkins.model.GlobalConfiguration} and calls {@code load()}/
 * {@code save()} against {@code JENKINS_HOME}, so a {@link JenkinsRule} is used. Each test gets a
 * fresh Jenkins (and thus an empty store singleton), so no explicit reset is needed.
 */
@WithJenkins
public class ProvisioningRequestConfigTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    private ProvisioningRequestConfig store() {
        ProvisioningRequestConfig s = ProvisioningRequestConfig.get();
        assertNotNull(s, "store extension should be registered");
        return s;
    }

    private static ProvisioningRequest pending(String userId) {
        return ProvisioningRequest.create(
                ProvisioningRequest.TYPE_NATIVE, userId, "", userId + "@example.com",
                null, "reason", 0, null);
    }

    // ── countPendingForUser ────────────────────────────────────────────────────

    @Test
    public void countPendingForUser_caseInsensitive() {
        ProvisioningRequestConfig s = store();
        s.addRequest(pending("Alice"));
        s.addRequest(pending("alice"));
        assertEquals(2, s.countPendingForUser("ALICE"));
        assertEquals(2, s.countPendingForUser("alice"));
        assertEquals(0, s.countPendingForUser("bob"));
    }

    @Test
    public void countPendingForUser_nullIsZero() {
        assertEquals(0, store().countPendingForUser(null));
    }

    @Test
    public void countPendingForUser_ignoresDecidedRequests() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("carol");
        s.addRequest(r);
        assertEquals(1, s.countPendingForUser("carol"));
        s.markApproved(r.getRequestId(), "admin", "ok");
        assertEquals(0, s.countPendingForUser("carol"));
    }

    // ── addRequestIfUnderCap ────────────────────────────────────────────────────

    @Test
    public void addRequestIfUnderCap_trueBelowCap() {
        assertTrue(store().addRequestIfUnderCap(pending("dan"), 3));
    }

    @Test
    public void addRequestIfUnderCap_falseAtPendingCap() {
        ProvisioningRequestConfig s = store();
        assertTrue(s.addRequestIfUnderCap(pending("erin"), 2));
        assertTrue(s.addRequestIfUnderCap(pending("erin"), 2));
        // Third same-user pending request exceeds the cap of 2.
        assertFalse(s.addRequestIfUnderCap(pending("erin"), 2));
        assertEquals(2, s.countPendingForUser("erin"));
    }

    @Test
    public void addRequestIfUnderCap_capIsCaseInsensitive() {
        ProvisioningRequestConfig s = store();
        assertTrue(s.addRequestIfUnderCap(pending("Frank"), 1));
        assertFalse(s.addRequestIfUnderCap(pending("frank"), 1));
    }

    @Test
    public void addRequestIfUnderCap_nonPositiveMaxTreatedAsOne() {
        ProvisioningRequestConfig s = store();
        assertTrue(s.addRequestIfUnderCap(pending("gina"), 0));
        assertFalse(s.addRequestIfUnderCap(pending("gina"), 0));
    }

    @Test
    public void addRequestIfUnderCap_falseWhenStoreAtTotalCeiling() throws Exception {
        ProvisioningRequestConfig s = store();
        // Fill the backing list to MAX_TOTAL_REQUESTS (2000) directly to avoid 2000 disk writes,
        // each a distinct pending user so the per-user cap is not what rejects the new request.
        Field f = ProvisioningRequestConfig.class.getDeclaredField("requests");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ProvisioningRequest> backing = (List<ProvisioningRequest>) f.get(s);
        for (int i = 0; i < 2000; i++) {
            backing.add(pending("filler-" + i));
        }
        // A brand-new user is below the per-user cap, so only the 2000-total ceiling can reject it.
        assertFalse(s.addRequestIfUnderCap(pending("overflow-user"), 100));
    }

    // ── findById / findPendingForUser ───────────────────────────────────────────

    @Test
    public void findById_returnsMatchingRequestOrNull() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("hank");
        s.addRequest(r);
        assertSame(r, s.findById(r.getRequestId()));
        assertNull(s.findById("no-such-id"));
        assertNull(s.findById(null));
    }

    @Test
    public void findPendingForUser_caseInsensitiveAndNullSafe() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("Ivy");
        s.addRequest(r);
        assertSame(r, s.findPendingForUser("ivy"));
        assertNull(s.findPendingForUser("nobody"));
        assertNull(s.findPendingForUser(null));
    }

    @Test
    public void findPendingForUser_skipsDecided() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("jack");
        s.addRequest(r);
        s.markRejected(r.getRequestId(), "admin", "no");
        assertNull(s.findPendingForUser("jack"));
    }

    // ── markApproved / markRejected ─────────────────────────────────────────────

    @Test
    public void markApproved_onlyActsOnPending() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("kim");
        s.addRequest(r);
        assertTrue(s.markApproved(r.getRequestId(), "admin", "ok"));
        assertTrue(r.isApproved());
        assertEquals("admin", r.getDecidedBy());
        assertFalse(r.getDecidedAt().isEmpty());
        // Already decided -> second call is a no-op returning false.
        assertFalse(s.markApproved(r.getRequestId(), "admin2", "again"));
    }

    @Test
    public void markRejected_onlyActsOnPending() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("leo");
        s.addRequest(r);
        assertTrue(s.markRejected(r.getRequestId(), "admin", "denied"));
        assertTrue(r.isRejected());
        assertFalse(s.markRejected(r.getRequestId(), "admin", "again"));
    }

    @Test
    public void markApproved_unknownIdIsFalse() {
        assertFalse(store().markApproved("ghost", "admin", "x"));
    }

    @Test
    public void markRejected_cannotFlipAnApprovedRequest() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("mia");
        s.addRequest(r);
        s.markApproved(r.getRequestId(), "admin", "ok");
        assertFalse(s.markRejected(r.getRequestId(), "admin", "no"));
        assertTrue(r.isApproved());
    }

    // ── cancel ──────────────────────────────────────────────────────────────────

    @Test
    public void cancel_byOwnerSucceedsCaseInsensitive() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("Nina");
        s.addRequest(r);
        assertTrue(s.cancel(r.getRequestId(), "nina"));
        assertTrue(r.isCancelled());
    }

    @Test
    public void cancel_byNonOwnerRejected() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("otto");
        s.addRequest(r);
        assertFalse(s.cancel(r.getRequestId(), "someone-else"));
        assertTrue(r.isPending());
    }

    @Test
    public void cancel_nullRequesterAllowed() {
        // null requesterId means an administrative cancel — ownership not enforced.
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("pam");
        s.addRequest(r);
        assertTrue(s.cancel(r.getRequestId(), null));
        assertTrue(r.isCancelled());
    }

    @Test
    public void cancel_nonPendingRejected() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("quin");
        s.addRequest(r);
        s.markApproved(r.getRequestId(), "admin", "ok");
        assertFalse(s.cancel(r.getRequestId(), "quin"));
    }

    // ── purgeOldRecords ──────────────────────────────────────────────────────────

    @Test
    public void purgeOldRecords_removesOnlyOldDecidedRecords() {
        ProvisioningRequestConfig s = store();

        ProvisioningRequest oldDecided = pending("rita");
        s.addRequest(oldDecided);
        s.markApproved(oldDecided.getRequestId(), "admin", "ok");
        oldDecided.setDecidedAt(Instant.now().minus(60, ChronoUnit.DAYS).toString());

        ProvisioningRequest recentDecided = pending("sam");
        s.addRequest(recentDecided);
        s.markApproved(recentDecided.getRequestId(), "admin", "ok"); // decidedAt = now

        ProvisioningRequest stillPending = pending("tom");
        s.addRequest(stillPending);

        int removed = s.purgeOldRecords(30);
        assertEquals(1, removed);
        assertNull(s.findById(oldDecided.getRequestId()));          // purged
        assertNotNull(s.findById(recentDecided.getRequestId()));    // too recent
        assertNotNull(s.findById(stillPending.getRequestId()));     // pending never purged
    }

    @Test
    public void purgeOldRecords_nonPositiveRetentionIsNoop() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest r = pending("uma");
        s.addRequest(r);
        s.markApproved(r.getRequestId(), "admin", "ok");
        r.setDecidedAt(Instant.now().minus(999, ChronoUnit.DAYS).toString());
        assertEquals(0, s.purgeOldRecords(0));
        assertEquals(0, s.purgeOldRecords(-5));
        assertNotNull(s.findById(r.getRequestId()));
    }

    // ── getRecentRequests ─────────────────────────────────────────────────────────

    @Test
    public void getRecentRequests_newestFirstAndCappedAtLimit() {
        ProvisioningRequestConfig s = store();
        ProvisioningRequest a = pending("a");
        ProvisioningRequest b = pending("b");
        ProvisioningRequest c = pending("c");
        a.setRequestedAt("2020-01-01T00:00:00Z");  // oldest
        b.setRequestedAt("2021-01-01T00:00:00Z");
        c.setRequestedAt("2022-01-01T00:00:00Z");  // newest
        s.addRequest(a);
        s.addRequest(b);
        s.addRequest(c);

        List<ProvisioningRequest> recent = s.getRecentRequests(2);
        assertEquals(2, recent.size());
        assertSame(c, recent.get(0));   // newest first
        assertSame(b, recent.get(1));
    }

    @Test
    public void getRecentRequests_limitLargerThanSizeReturnsAll() {
        ProvisioningRequestConfig s = store();
        s.addRequest(pending("x"));
        s.addRequest(pending("y"));
        assertEquals(2, s.getRecentRequests(10).size());
    }
}
