package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.util.logging.Logger;

@Extension
public class RotogateExpiryWorker extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(RotogateExpiryWorker.class.getName());

    public RotogateExpiryWorker() {
        super("Rotogate Assignment Expiry Check");
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR;
    }

    @Override
    protected void execute(TaskListener listener) {
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        if (config == null) return;

        // Remove and audit in one pass: audit exactly the assignments that were removed,
        // evaluated once under the config lock (avoids a second isExpired() pass that could
        // remove an assignment the audit snapshot missed).
        RotogateAuditLog audit = RotogateAuditLog.get();
        java.util.List<RotogateAssignment> removed = config.removeExpiredReturning();
        if (audit != null) {
            for (RotogateAssignment a : removed) {
                audit.logExpired(a.getUserId(), a.getScope());
            }
        }
        if (!removed.isEmpty()) {
            LOGGER.info("Rotogate: auto-revoked " + removed.size() + " expired assignment(s)");
        }
    }
}
