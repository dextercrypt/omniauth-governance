package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.util.logging.Logger;

@Extension
public class OmniAuthExpiryWorker extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthExpiryWorker.class.getName());

    public OmniAuthExpiryWorker() {
        super("OmniAuth Assignment Expiry Check");
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR;
    }

    @Override
    protected void execute(TaskListener listener) {
        OmniAuthAssignmentConfig config = OmniAuthAssignmentConfig.get();
        if (config == null) return;

        // Remove and audit in one pass: audit exactly the assignments that were removed,
        // evaluated once under the config lock (avoids a second isExpired() pass that could
        // remove an assignment the audit snapshot missed).
        OmniAuthAuditLog audit = OmniAuthAuditLog.get();
        java.util.List<OmniAuthAssignment> removed = config.removeExpiredReturning();
        if (audit != null) {
            for (OmniAuthAssignment a : removed) {
                audit.logExpired(a.getUserId(), a.getScope());
            }
        }
        if (!removed.isEmpty()) {
            LOGGER.info("OmniAuth: auto-revoked " + removed.size() + " expired assignment(s)");
        }
    }
}
