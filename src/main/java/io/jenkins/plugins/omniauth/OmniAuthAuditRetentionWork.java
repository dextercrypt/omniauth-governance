package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.util.logging.Logger;

/**
 * Daily sweep that prunes audit-log events older than the configured retention window
 * ({@link OmniAuthGlobalConfig#getAuditRetentionDays()}; 0 = keep forever). The cheap write-path
 * cleanup already drops whole stale month-files; this worker trims the boundary file precisely so
 * the retained window matches the configured number of days, not just whole months.
 */
@Extension
public class OmniAuthAuditRetentionWork extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthAuditRetentionWork.class.getName());

    public OmniAuthAuditRetentionWork() {
        super("OmniAuth Audit Log Retention");
    }

    @Override
    public long getRecurrencePeriod() {
        return DAY;
    }

    @Override
    protected void execute(TaskListener listener) {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        int days = cfg != null ? cfg.getAuditRetentionDays() : 365;
        if (days <= 0) return; // keep forever
        OmniAuthAuditLog log = OmniAuthAuditLog.get();
        if (log == null) return;
        int removed = log.pruneOlderThan(days);
        if (removed > 0) {
            LOGGER.info("OmniAuth audit retention: pruned " + removed + " event(s) older than " + days + " days");
        }
    }
}
