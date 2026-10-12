package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.User;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enforces time-limited accounts created by the provisioning portal. Accounts carrying an
 * {@link OmniAuthAccountExpiryProperty} whose expiry has passed are, per the portal's
 * expiry-action setting, either <b>disabled</b> (global access revoked — the user can no longer
 * do anything, and the existing stale-cleanup eventually removes them) or <b>deleted</b> outright.
 *
 * <p>Protected users are always skipped.
 */
@Extension
public class OmniAuthAccountExpiryWorker extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthAccountExpiryWorker.class.getName());

    public OmniAuthAccountExpiryWorker() {
        super("OmniAuth Account Expiry");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.HOURS.toMillis(6);
    }

    @Override
    protected void execute(TaskListener listener) {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        boolean delete = cfg != null && "DELETE".equals(cfg.getProvisioningExpiryAction());
        OmniAuthAuditLog audit = OmniAuthAuditLog.get();

        for (User user : User.getAll()) {
            OmniAuthAccountExpiryProperty p = user.getProperty(OmniAuthAccountExpiryProperty.class);
            if (p == null || p.isDisabled() || !p.isExpired()) continue;

            String id = user.getId();
            if (cfg != null && cfg.isProtected(id)) {
                LOGGER.log(Level.FINE, "Account {0} expired but is protected — skipping", id);
                continue;
            }

            try {
                if (delete) {
                    OmniAuthManagementLink.purgeGlobalMatrix(id);
                    user.delete();
                    if (audit != null) audit.logUserDeleted("system", id);
                    LOGGER.log(Level.INFO, "Time-limited account expired → deleted: {0}", id);
                } else {
                    // Disable: revoke global access (loses Hudson.Read → effectively locked out),
                    // then mark so we don't reprocess. Stale cleanup removes it later.
                    OmniAuthManagementLink.purgeGlobalMatrix(id);
                    p.setDisabled(true);
                    user.save();
                    LOGGER.log(Level.INFO, "Time-limited account expired → disabled (access revoked): {0}", id);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to enforce account expiry for " + id, e);
            }
        }
    }
}
