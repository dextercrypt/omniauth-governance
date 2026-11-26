package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.util.logging.Logger;

/**
 * Daily sweep that prunes decided provisioning (access-request) records older than the configured
 * retention window ({@link RotogateGlobalConfig#getProvisioningRetentionDays()}; 0 = keep forever).
 *
 * <p>Mirrors {@link RotogateAuditRetentionWork} and the JIT retention sweep: pending requests are
 * never touched, only terminal ones (approved / rejected / cancelled) past the cutoff. Without this,
 * the provisioning store only had a 2000-record hard cap after which new requests were silently
 * dropped - this ages decided records out so the store stays within its cap in normal operation.
 */
@Extension
public class RotogateProvisioningRetentionWork extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(RotogateProvisioningRetentionWork.class.getName());

    public RotogateProvisioningRetentionWork() {
        super("Rotogate Access-Request Retention");
    }

    @Override
    public long getRecurrencePeriod() {
        return DAY;
    }

    @Override
    protected void execute(TaskListener listener) {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        int days = cfg != null ? cfg.getProvisioningRetentionDays() : 365;
        if (days <= 0) return; // keep forever
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        if (store == null) return;
        int purged = store.purgeOldRecords(days);
        if (purged > 0) {
            LOGGER.info("Rotogate access-request retention: purged " + purged
                    + " decided request(s) older than " + days + " days");
        }
    }
}
