package io.jenkins.plugins.rotogate;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Extension
@Symbol("rotogateAssignments")
public class RotogateAssignmentConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(RotogateAssignmentConfig.class.getName());

    private List<RotogateAssignment> assignments = new CopyOnWriteArrayList<>();
    private List<RotogateGroupEntity> groups = new CopyOnWriteArrayList<>();

    public RotogateAssignmentConfig() {
        load();
    }

    private Object readResolve() {
        if (!(assignments instanceof CopyOnWriteArrayList)) {
            assignments = new CopyOnWriteArrayList<>(assignments != null ? assignments : Collections.emptyList());
        }
        if (!(groups instanceof CopyOnWriteArrayList)) {
            groups = new CopyOnWriteArrayList<>(groups != null ? groups : Collections.emptyList());
        }
        return this;
    }

    public static RotogateAssignmentConfig get() {
        return GlobalConfiguration.all().get(RotogateAssignmentConfig.class);
    }

    public List<RotogateAssignment> getAssignments() {
        return Collections.unmodifiableList(assignments);
    }

    public void setAssignments(List<RotogateAssignment> assignments) {
        this.assignments = assignments != null ? new CopyOnWriteArrayList<>(assignments) : new CopyOnWriteArrayList<>();
    }

    public List<RotogateAssignment> getAssignmentsForUser(String userId, String authType) {
        return assignments.stream()
                .filter(a -> a.getUserId().equals(userId)
                        && a.getAuthType().equalsIgnoreCase(authType))
                .collect(Collectors.toList());
    }

    public synchronized void addAssignment(RotogateAssignment a) {
        assignments.add(a);
        save();
        RotogateAuthorizationStrategy.invalidateCache();
    }

    public synchronized boolean confirmReview(String userId, String authType, String scope) {
        String normalizedScope = scope != null ? scope : "";
        boolean found = false;
        for (RotogateAssignment a : assignments) {
            if (a.getUserId().equals(userId)
                    && a.getAuthType().equalsIgnoreCase(authType)
                    && a.getScope().equals(normalizedScope)) {
                a.setReviewedAt(java.time.Instant.now().toString());
                found = true;
            }
        }
        if (found) {
            save();
            RotogateAuthorizationStrategy.invalidateCache();
        }
        return found;
    }

    public synchronized void removeAssignment(String userId, String authType, String scope) {
        String normalizedScope = scope != null ? scope : "";
        boolean removed = assignments.removeIf(a ->
                a.getUserId().equals(userId)
                && a.getAuthType().equalsIgnoreCase(authType)
                && a.getScope().equals(normalizedScope));
        if (removed) {
            save();
            RotogateAuthorizationStrategy.invalidateCache();
        }
    }

    public synchronized int removeExpiredAssignments() {
        return removeExpiredReturning().size();
    }

    /**
     * Removes all currently-expired assignments and returns exactly those that were removed,
     * evaluated once under the lock - so the caller can audit precisely what it deleted
     * (no second isExpired() pass that could diverge as time moves).
     */
    public synchronized java.util.List<RotogateAssignment> removeExpiredReturning() {
        java.util.List<RotogateAssignment> expired = new java.util.ArrayList<>();
        for (RotogateAssignment a : assignments) {
            if (a.isExpired()) expired.add(a);
        }
        if (!expired.isEmpty()) {
            assignments.removeAll(expired);
            save();
            RotogateAuthorizationStrategy.invalidateCache();
        }
        return expired;
    }

    public synchronized void updateAssignment(String userId, String authType, String scope, RotogateAssignment updated) {
        String normalizedScope = scope != null ? scope : "";
        assignments.removeIf(a ->
                a.getUserId().equals(userId)
                && a.getAuthType().equalsIgnoreCase(authType)
                && a.getScope().equals(normalizedScope));
        assignments.add(updated);
        save();
        RotogateAuthorizationStrategy.invalidateCache();
    }

    public boolean hasAssignment(String userId, String authType, String scope) {
        String normalizedScope = scope != null ? scope : "";
        return assignments.stream().anyMatch(a ->
                a.getUserId().equals(userId)
                && a.getAuthType().equalsIgnoreCase(authType)
                && a.getScope().equals(normalizedScope));
    }

    // ── Group entity methods ──────────────────────────────────────────────────

    public List<RotogateGroupEntity> getGroups() {
        return Collections.unmodifiableList(groups);
    }

    // Data-bound so the Access-Management group registry round-trips through JCasC, consistent
    // with assignments. (Group membership itself lives in Entra; this is the registered-group list.)
    @org.kohsuke.stapler.DataBoundSetter
    public void setGroups(List<RotogateGroupEntity> groups) {
        this.groups = groups != null ? new CopyOnWriteArrayList<>(groups) : new CopyOnWriteArrayList<>();
    }

    public synchronized void addGroup(RotogateGroupEntity group) {
        groups.add(group);
        save();
    }

    public synchronized void removeGroup(String groupOid) {
        groups.removeIf(g -> g.getGroupOid().equals(groupOid));
        save();
    }

    public RotogateGroupEntity findGroup(String groupOid) {
        return groups.stream()
                .filter(g -> g.getGroupOid().equals(groupOid))
                .findFirst().orElse(null);
    }

    public boolean hasGroup(String groupOid) {
        return groups.stream().anyMatch(g -> g.getGroupOid().equals(groupOid));
    }

    /** Returns true if any GROUP entities have been added to Access Management. */
    public boolean hasAnyGroups() {
        return !groups.isEmpty();
    }

    /** Resolves display name for a group - called when first member logs in. */
    public synchronized void resolveGroupDisplayName(String groupOid, String displayName) {
        for (RotogateGroupEntity g : groups) {
            if (g.getGroupOid().equals(groupOid) && !g.isResolved()) {
                g.setDisplayName(displayName);
                save();
                break;
            }
        }
    }
}
