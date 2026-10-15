package io.jenkins.plugins.omniauth;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Persistent store for {@link ProvisioningRequest}s submitted through the provisioning portal.
 *
 * <p>Mirrors {@link OmniAuthJitRequestStore}: an {@code @Extension} {@link GlobalConfiguration}
 * that persists a list to {@code JENKINS_HOME}. State transitions ({@code markApproved} /
 * {@code markRejected}) only flip the request status here; the side effects of approval —
 * creating the Jenkins account and applying permissions — are performed by the management-link
 * handler, exactly as the JIT store leaves account/session effects to its caller.
 */
@Extension
@Symbol("omniAuthProvisioningRequests")
public class ProvisioningRequestConfig extends GlobalConfiguration {

    private List<ProvisioningRequest> requests = new CopyOnWriteArrayList<>();

    public ProvisioningRequestConfig() { load(); }

    private Object readResolve() {
        if (!(requests instanceof CopyOnWriteArrayList)) {
            requests = new CopyOnWriteArrayList<>(
                    requests != null ? requests : Collections.emptyList());
        }
        return this;
    }

    public static ProvisioningRequestConfig get() {
        return GlobalConfiguration.all().get(ProvisioningRequestConfig.class);
    }

    public synchronized void addRequest(ProvisioningRequest r) {
        requests.add(r);
        save();
    }

    /**
     * Adds the request only if the user has no pending request yet; returns true if added.
     * The find+add is atomic under the store lock, closing the check-then-act race where two
     * near-simultaneous submissions both pass an external findPendingForUser() check and create
     * duplicate PENDING rows.
     */
    public synchronized boolean addRequestIfNoPending(ProvisioningRequest r) {
        if (r != null && r.getUserId() != null) {
            for (ProvisioningRequest existing : requests) {
                if (existing.isPending() && existing.getUserId().equalsIgnoreCase(r.getUserId())) {
                    return false;
                }
            }
        }
        requests.add(r);
        save();
        return true;
    }

    public ProvisioningRequest findById(String requestId) {
        if (requestId == null) return null;
        return requests.stream()
                .filter(r -> r.getRequestId().equals(requestId))
                .findFirst().orElse(null);
    }

    /** A still-pending request for this user id, if any — used to block duplicate submissions. */
    public ProvisioningRequest findPendingForUser(String userId) {
        if (userId == null) return null;
        return requests.stream()
                .filter(r -> r.getUserId().equalsIgnoreCase(userId) && r.isPending())
                .findFirst().orElse(null);
    }

    public List<ProvisioningRequest> getPendingRequests() {
        return requests.stream()
                .filter(ProvisioningRequest::isPending)
                .collect(Collectors.toList());
    }

    public int getPendingCount() {
        return (int) requests.stream().filter(ProvisioningRequest::isPending).count();
    }

    public List<ProvisioningRequest> getAllRequests() {
        return new ArrayList<>(requests);
    }

    /** Recent requests, newest first, capped at {@code limit}. */
    public List<ProvisioningRequest> getRecentRequests(int limit) {
        List<ProvisioningRequest> sorted = new ArrayList<>(requests);
        sorted.sort((a, b) -> {
            String ta = a.getRequestedAt();
            String tb = b.getRequestedAt();
            return tb.compareTo(ta); // ISO-8601 sorts lexicographically
        });
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    /** Mark a pending request approved. Returns false if not found or not pending. */
    public synchronized boolean markApproved(String requestId, String decidedBy, String comment) {
        ProvisioningRequest r = findById(requestId);
        if (r == null || !r.isPending()) return false;
        r.setStatus(ProvisioningRequest.STATUS_APPROVED);
        r.setDecidedBy(decidedBy);
        r.setDecidedAt(java.time.Instant.now().toString());
        r.setDecisionComment(comment);
        save();
        return true;
    }

    /** Mark a pending request rejected. Returns false if not found or not pending. */
    public synchronized boolean markRejected(String requestId, String decidedBy, String comment) {
        ProvisioningRequest r = findById(requestId);
        if (r == null || !r.isPending()) return false;
        r.setStatus(ProvisioningRequest.STATUS_REJECTED);
        r.setDecidedBy(decidedBy);
        r.setDecidedAt(java.time.Instant.now().toString());
        r.setDecisionComment(comment);
        save();
        return true;
    }

    /** Requester cancels their own pending request. */
    public synchronized boolean cancel(String requestId, String requesterId) {
        ProvisioningRequest r = findById(requestId);
        if (r == null || !r.isPending()) return false;
        if (requesterId != null && !r.getUserId().equalsIgnoreCase(requesterId)) return false;
        r.setStatus(ProvisioningRequest.STATUS_CANCELLED);
        r.setDecidedAt(java.time.Instant.now().toString());
        save();
        return true;
    }

    /** Purge decided requests older than {@code retentionDays}. Returns count removed. */
    public synchronized int purgeOldRecords(int retentionDays) {
        if (retentionDays <= 0) return 0;
        java.time.Instant cutoff = java.time.Instant.now()
                .minus(retentionDays, java.time.temporal.ChronoUnit.DAYS);
        List<ProvisioningRequest> toRemove = requests.stream()
                .filter(r -> !r.isPending())
                .filter(r -> isOlderThan(r.getDecidedAt(), cutoff))
                .collect(Collectors.toList());
        if (!toRemove.isEmpty()) {
            requests.removeAll(toRemove);
            save();
        }
        return toRemove.size();
    }

    private static boolean isOlderThan(String iso, java.time.Instant cutoff) {
        if (iso == null || iso.isBlank()) return false;
        try { return java.time.Instant.parse(iso).isBefore(cutoff); }
        catch (Exception e) { return false; }
    }
}
