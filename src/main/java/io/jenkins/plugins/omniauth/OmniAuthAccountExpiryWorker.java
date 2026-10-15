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

        int reminderDays = cfg != null ? cfg.getProvisioningExpiryReminderDays() : 0;

        for (User user : User.getAll()) {
            OmniAuthAccountExpiryProperty p = user.getProperty(OmniAuthAccountExpiryProperty.class);
            if (p == null || p.isDisabled()) continue;

            String id = user.getId();

            // --- Pre-expiry reminder (account not yet expired, within the reminder window) ---
            if (!p.isExpired()) {
                long daysLeft = p.daysUntilExpiry();
                if (reminderDays > 0 && !p.isReminderSent() && daysLeft >= 0 && daysLeft <= reminderDays
                        && !(cfg != null && cfg.isProtected(id))) {
                    String email = resolveEmail(user);
                    // Only mark reminded once we actually have somewhere to send — otherwise an address
                    // added later would never get a reminder. With no email we simply re-check next run.
                    if (email != null) {
                        // Guard the send too, so one failure here can't abort the sweep for the
                        // remaining accounts in this run.
                        try {
                            NotificationService.sendExpiryReminder(cfg, id, email, p.getExpiresAt(), daysLeft);
                            p.setReminderSent(true); user.save();
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Failed to send/mark expiry reminder for " + id, e);
                        }
                    }
                }
                continue; // not expired yet — nothing more to do
            }

            // --- Expired: enforce ---
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
                    NotificationService.sendAccountExpired(cfg, id, "Deleted");
                } else {
                    // Disable: revoke global access (loses Hudson.Read → effectively locked out),
                    // then mark so we don't reprocess. Stale cleanup removes it later.
                    OmniAuthManagementLink.purgeGlobalMatrix(id);
                    p.setDisabled(true);
                    user.save();
                    LOGGER.log(Level.INFO, "Time-limited account expired → disabled (access revoked): {0}", id);
                    NotificationService.sendAccountExpired(cfg, id, "Disabled");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to enforce account expiry for " + id, e);
            }
        }
    }

    /** Best-effort read of a user's email from the Mailer user property (reflective — Mailer is optional). */
    private static String resolveEmail(User user) {
        try {
            ClassLoader uberCl = jenkins.model.Jenkins.get().getPluginManager().uberClassLoader;
            Class<?> mailerPropClass = Class.forName("hudson.tasks.Mailer$UserProperty", true, uberCl);
            Object prop = user.getProperty((Class) mailerPropClass.asSubclass(hudson.model.UserProperty.class));
            if (prop == null) return null;
            Object addr = mailerPropClass.getMethod("getAddress").invoke(prop);
            String s = (addr == null) ? null : addr.toString().trim();
            return (s != null && s.contains("@")) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
