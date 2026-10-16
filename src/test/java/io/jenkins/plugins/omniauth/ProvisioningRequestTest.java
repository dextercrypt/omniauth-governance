package io.jenkins.plugins.omniauth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for the {@link ProvisioningRequest} POJO: the {@code create} factory,
 * state checks, status/type constants, the requestedAccess accessor, and label helpers.
 */
public class ProvisioningRequestTest {

    @Test
    public void create_setsFieldsAndPendingStatus() {
        ProvisioningRequest r = ProvisioningRequest.create(
                ProvisioningRequest.TYPE_NATIVE, "alice", "Alice A", "alice@example.com",
                null, "need CI access", 14, "10.0.0.1");

        assertNotNull(r.getRequestId());
        assertFalse("requestId should be a non-empty UUID", r.getRequestId().isEmpty());
        assertEquals(ProvisioningRequest.TYPE_NATIVE, r.getRequestType());
        assertEquals("alice", r.getUserId());
        assertEquals("Alice A", r.getFullName());
        assertEquals("alice@example.com", r.getEmail());
        assertEquals("", r.getOid());               // null oid -> ""
        assertEquals("need CI access", r.getReason());
        assertEquals(14, r.getRequestedDurationDays());
        assertEquals("10.0.0.1", r.getSourceIp());
        assertEquals(ProvisioningRequest.STATUS_PENDING, r.getStatus());
        assertTrue(r.isPending());
        assertNotNull(r.getRequestedAt());
        assertFalse(r.getRequestedAt().isEmpty());
    }

    @Test
    public void create_trimsIdentityFields() {
        ProvisioningRequest r = ProvisioningRequest.create(
                ProvisioningRequest.TYPE_ENTRA, "  bob  ", "  Bob B  ", "  bob@x.io  ",
                "  oid-123  ", "  reason  ", 0, null);
        assertEquals("bob", r.getUserId());
        assertEquals("Bob B", r.getFullName());
        assertEquals("bob@x.io", r.getEmail());
        assertEquals("oid-123", r.getOid());
        assertEquals("reason", r.getReason());
        assertEquals("", r.getSourceIp());          // null sourceIp -> ""
    }

    @Test
    public void create_negativeDurationFlooredToZero() {
        ProvisioningRequest r = ProvisioningRequest.create(
                ProvisioningRequest.TYPE_NATIVE, "x", "", "", null, "r", -5, null);
        assertEquals(0, r.getRequestedDurationDays());
        assertTrue(r.isPermanent());
    }

    @Test
    public void statusConstantValues() {
        assertEquals("PENDING", ProvisioningRequest.STATUS_PENDING);
        assertEquals("APPROVED", ProvisioningRequest.STATUS_APPROVED);
        assertEquals("REJECTED", ProvisioningRequest.STATUS_REJECTED);
        assertEquals("CANCELLED", ProvisioningRequest.STATUS_CANCELLED);
    }

    @Test
    public void typeConstantValues() {
        assertEquals("ENTRA", ProvisioningRequest.TYPE_ENTRA);
        assertEquals("NATIVE", ProvisioningRequest.TYPE_NATIVE);
    }

    @Test
    public void stateChecks_reflectStatus() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setStatus(ProvisioningRequest.STATUS_APPROVED);
        assertTrue(r.isApproved());
        assertFalse(r.isPending());
        r.setStatus(ProvisioningRequest.STATUS_REJECTED);
        assertTrue(r.isRejected());
        r.setStatus(ProvisioningRequest.STATUS_CANCELLED);
        assertTrue(r.isCancelled());
    }

    @Test
    public void typeChecks_reflectRequestType() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setRequestType(ProvisioningRequest.TYPE_ENTRA);
        assertTrue(r.isEntra());
        assertFalse(r.isNative());
        r.setRequestType(ProvisioningRequest.TYPE_NATIVE);
        assertTrue(r.isNative());
        assertFalse(r.isEntra());
    }

    @Test
    public void requestedAccess_getterSetterTrimsAndDefaultsEmpty() {
        ProvisioningRequest r = new ProvisioningRequest();
        assertEquals("", r.getRequestedAccess());          // default non-null
        r.setRequestedAccess("  deploy to prod  ");
        assertEquals("deploy to prod", r.getRequestedAccess());
        r.setRequestedAccess(null);
        assertEquals("", r.getRequestedAccess());
    }

    @Test
    public void requestedDuration_setterFloorsNegative() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setRequestedDurationDays(-3);
        assertEquals(0, r.getRequestedDurationDays());
    }

    @Test
    public void isPermanent_zeroOrNegativeIsPermanent() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setRequestedDurationDays(0);
        assertTrue(r.isPermanent());
        r.setRequestedDurationDays(5);
        assertFalse(r.isPermanent());
    }

    @Test
    public void typeLabel_perType() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setRequestType(ProvisioningRequest.TYPE_ENTRA);
        assertEquals("Microsoft Entra", r.typeLabel());
        r.setRequestType(ProvisioningRequest.TYPE_NATIVE);
        assertEquals("Native", r.typeLabel());
        r.setRequestType("OTHER");
        assertEquals("OTHER", r.typeLabel());   // falls through to raw type
    }

    @Test
    public void statusLabel_perStatus() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setStatus(ProvisioningRequest.STATUS_PENDING);
        assertEquals("Pending", r.statusLabel());
        r.setStatus(ProvisioningRequest.STATUS_APPROVED);
        assertEquals("Approved", r.statusLabel());
        r.setStatus(ProvisioningRequest.STATUS_REJECTED);
        assertEquals("Rejected", r.statusLabel());
        r.setStatus(ProvisioningRequest.STATUS_CANCELLED);
        assertEquals("Cancelled", r.statusLabel());
    }

    @Test
    public void durationLabel_permanentAndSingularPlural() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setRequestedDurationDays(0);
        assertEquals("Permanent", r.durationLabel());
        r.setRequestedDurationDays(1);
        assertEquals("1 day", r.durationLabel());
        r.setRequestedDurationDays(5);
        assertEquals("5 days", r.durationLabel());
    }

    @Test
    public void timeAgo_blankWhenNoTimestampOrUnparseable() {
        ProvisioningRequest r = new ProvisioningRequest();
        assertEquals("", r.timeAgo());                // null requestedAt
        r.setRequestedAt("not-a-timestamp");
        assertEquals("", r.timeAgo());                // unparseable -> ""
    }

    @Test
    public void timeAgo_recentIsJustNow() {
        ProvisioningRequest r = new ProvisioningRequest();
        r.setRequestedAt(java.time.Instant.now().toString());
        assertEquals("just now", r.timeAgo());
    }
}
