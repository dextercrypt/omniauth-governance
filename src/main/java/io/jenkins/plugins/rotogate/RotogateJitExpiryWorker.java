package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.util.logging.Logger;

@Extension
public class RotogateJitExpiryWorker extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(RotogateJitExpiryWorker.class.getName());

    public RotogateJitExpiryWorker() {
        super("Rotogate JIT Request Expiry Check");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN; // every minute — JIT windows can be as short as 1 hour
    }

    @Override
    protected void execute(TaskListener listener) {
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        if (store == null) return;

        java.util.List<RotogateJitRequest> transitioned = store.processExpiredAndTimedOut();
        if (!transitioned.isEmpty()) {
            LOGGER.info("Rotogate JIT: transitioned " + transitioned.size() + " request(s) to EXPIRED/TIMED_OUT");
            RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
            RotogateAuditLog audit = RotogateAuditLog.get();
            for (RotogateJitRequest req : transitioned) {
                if (RotogateJitRequest.STATUS_TIMED_OUT.equals(req.getStatus())) {
                    // Pending request that was never approved in time.
                    if (audit != null) audit.logJitTimedOut(req.getRequesterId(), req.getScope());
                    if (req.getApprovedCount() > 0) {
                        NotificationService.sendJitTimedOutPartial(cfg, req);
                    }
                } else if (RotogateJitRequest.STATUS_EXPIRED.equals(req.getStatus())) {
                    // Active grant whose window elapsed.
                    if (audit != null) audit.logJitExpired(req.getRequesterId(), req.getScope());
                }
            }
        }

        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        int retentionDays = cfg != null ? cfg.getJitHistoryRetentionDays() : 90;
        int purged = store.purgeOldRecords(retentionDays);
        if (purged > 0) {
            LOGGER.info("Rotogate JIT: purged " + purged + " terminal request(s) older than " + retentionDays + " days");
        }
    }
}
