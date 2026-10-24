package io.jenkins.plugins.omniauth;

import hudson.model.User;
import jenkins.model.Jenkins;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.security.GlobalMatrixAuthorizationStrategy;

import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;

/**
 * <h2>SOS Mode</h2>
 *
 * A cold fail-safe for the "locked out of admin" case that Break Glass does <em>not</em> cover:
 * every admin is SSO-only and the identity provider is down, or the authorization strategy was
 * misconfigured so no one holds {@link Jenkins#ADMINISTER}. SOS lets an operator who still has
 * host access to {@code JENKINS_HOME} open a narrow, audited recovery window without disabling
 * security or hand-editing {@code config.xml}.
 *
 * <h3>Arming — file-driven, live-checked, no restart</h3>
 * The operator drops an empty file {@code $JENKINS_HOME/omniauth-sos.request}. SOS is
 * <b>armed</b> whenever that file exists, is newer than {@value #WINDOW_MINUTES} minutes, and the
 * feature is enabled in the global config. The state is recomputed from the file on every check,
 * so arming and disarming take effect immediately with no restart. The file's own modification
 * time is the clock — the window cannot be extended by a running process.
 *
 * <h3>Recovery — recovery code only, identity from the code</h3>
 * While armed, {@code /sos/recover} ({@link OmniAuthSosRootAction}) accepts a single-use
 * {@link OmniAuthRecoveryCodesProperty recovery code}. A matching code identifies the admin it
 * belongs to (no username is asked), that admin is re-granted {@link Jenkins#ADMINISTER}, and a
 * session is established for them. Five failed attempts auto-disarm SOS ({@code sos_locked}).
 *
 * <h3>Trust model</h3>
 * Only salted hashes of the recovery codes live on disk, so even a full {@code JENKINS_HOME} read
 * does not yield a usable credential — it defeats the partial-access attacker (path-traversal read,
 * poisoned backup, log leak) that the old "drop a file → instant admin" design did not. Against an
 * attacker who can <em>write</em> arbitrary files as the Jenkins user, host-level {@code config.xml}
 * editing remains the ultimate (and equivalent) escape hatch; SOS does not widen that surface.
 *
 * <p>All transitions are audited: {@code sos_armed}, {@code sos_recovery_success} (with the resolved
 * admin + IP), {@code sos_recovery_failed} (IP), {@code sos_deactivated}, {@code sos_expired},
 * {@code sos_locked}.
 */
public final class OmniAuthSos {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthSos.class.getName());

    /** Trigger file, relative to {@code JENKINS_HOME}. Its presence + mtime arm the window. */
    public static final String TRIGGER_FILE = "omniauth-sos.request";
    /** How long after the trigger file's mtime SOS stays armed. */
    public static final int WINDOW_MINUTES = 30;
    private static final long WINDOW_MS = Duration.ofMinutes(WINDOW_MINUTES).toMillis();
    /** Failed recovery attempts that auto-disarm SOS. */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /**
     * mtime of the armed file we have already written {@code sos_armed} for, so re-arming a fresh
     * file logs again but repeated checks of the same armed file do not. {@code -1} = nothing logged.
     */
    private static volatile long armedMtimeLogged = -1L;
    /** Failed recovery attempts against the current armed window. */
    private static final AtomicInteger failedAttempts = new AtomicInteger(0);

    private OmniAuthSos() {}

    static File triggerFile() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j == null ? null : new File(j.getRootDir(), TRIGGER_FILE);
    }

    private static boolean featureEnabled() {
        try {
            OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
            return cfg == null || cfg.isSosEnabled();
        } catch (Exception e) {
            return true; // fail-open on config read: a lockout fail-safe must not be disabled by a config glitch
        }
    }

    /**
     * True only when OmniAuth is the active Jenkins {@link hudson.security.SecurityRealm}. This is the
     * <b>realm gate</b>: SOS is a recovery path <em>into OmniAuth logins</em>, so it must stay inert on
     * any instance that does not use OmniAuth to authenticate — an idle install (plugin present but a
     * different realm) or an abandoned one (switched away, leaving stale recovery-code hashes on disk).
     * In both cases SOS never arms, so it cannot be a backdoor. The real lockout cases are unaffected:
     * when SSO is merely unreachable, or the authorization strategy is misconfigured, the configured
     * realm object is still {@link OmniAuthSecurityRealm}, so this returns true.
     */
    private static boolean realmIsOmniAuth() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.getSecurityRealm() instanceof OmniAuthSecurityRealm;
    }

    /** True when SOS is currently armed and usable (OmniAuth realm active, feature enabled, fresh trigger). */
    public static boolean isArmed() {
        if (!realmIsOmniAuth()) return false;
        if (!featureEnabled()) return false;
        File f = triggerFile();
        if (f == null || !f.isFile()) return false;
        long age = System.currentTimeMillis() - f.lastModified();
        return age >= 0 && age <= WINDOW_MS;
    }

    /** Whole minutes left in the current armed window, or 0 when not armed. */
    public static long minutesRemaining() {
        File f = triggerFile();
        if (f == null || !f.isFile()) return 0;
        long remaining = WINDOW_MS - (System.currentTimeMillis() - f.lastModified());
        return remaining <= 0 ? 0 : (remaining + 59_999) / 60_000;
    }

    /**
     * If SOS just became armed with a trigger file we have not announced yet, write {@code sos_armed}
     * once and reset the failed-attempt counter for the new window. Called from the worker and from
     * the recovery endpoint so the event is recorded promptly whichever happens first.
     */
    public static synchronized void noteArmedIfNeeded() {
        if (!isArmed()) return;
        File f = triggerFile();
        long mtime = f.lastModified();
        if (mtime == armedMtimeLogged) return;
        armedMtimeLogged = mtime;
        failedAttempts.set(0);
        OmniAuthAuditLog audit = OmniAuthAuditLog.get();
        if (audit != null) audit.logSosArmed();
        LOGGER.log(Level.WARNING, "OmniAuth SOS Mode ARMED — recovery open at /sos/recover for {0} minutes.",
                WINDOW_MINUTES);
    }

    /**
     * Record a failed recovery attempt. When the count reaches {@link #MAX_FAILED_ATTEMPTS} the window
     * is auto-disarmed ({@code sos_locked}). Returns true if that attempt tripped the lockout.
     */
    public static synchronized boolean recordFailedAttempt() {
        int n = failedAttempts.incrementAndGet();
        OmniAuthAuditLog audit = OmniAuthAuditLog.get();
        if (audit != null) audit.logSosRecoveryFailed();
        if (n >= MAX_FAILED_ATTEMPTS) {
            if (deleteTrigger()) {
                if (audit != null) audit.logSosLocked();
                resetState();
                LOGGER.log(Level.WARNING, "OmniAuth SOS Mode LOCKED after {0} failed attempts — disarmed.", n);
            }
            return true;
        }
        return false;
    }

    /** Attempts left before auto-lockout in the current window. */
    public static int attemptsRemaining() {
        return Math.max(0, MAX_FAILED_ATTEMPTS - failedAttempts.get());
    }

    /** Manual deactivation by an administrator (banner or Settings). Deletes the trigger and audits. */
    public static synchronized void deactivate(String by) {
        if (deleteTrigger()) {
            OmniAuthAuditLog audit = OmniAuthAuditLog.get();
            if (audit != null) audit.logSosDeactivated(by);
        }
        resetState();
    }

    /** Window elapsed: delete the stale trigger and audit {@code sos_expired}. Called by the worker. */
    public static synchronized void expire() {
        if (deleteTrigger()) {
            OmniAuthAuditLog audit = OmniAuthAuditLog.get();
            if (audit != null) audit.logSosExpired();
            LOGGER.log(Level.INFO, "OmniAuth SOS Mode window elapsed — auto-disarmed.");
        }
        resetState();
    }

    /** True when a trigger file exists but has aged past the window (worker should {@link #expire()}). */
    public static boolean triggerIsStale() {
        File f = triggerFile();
        if (f == null || !f.isFile()) return false;
        return System.currentTimeMillis() - f.lastModified() > WINDOW_MS;
    }

    private static boolean deleteTrigger() {
        File f = triggerFile();
        if (f == null || !f.exists()) return false;
        if (!f.delete()) {
            LOGGER.log(Level.WARNING, "OmniAuth SOS: could not delete trigger file {0} — "
                    + "delete it by hand to close the recovery window.", f);
            return false;
        }
        return true;
    }

    private static void resetState() {
        armedMtimeLogged = -1L;
        failedAttempts.set(0);
    }

    // ----- recovery -----------------------------------------------------------

    /**
     * Find the admin whose recovery code matches {@code submittedCode}, consuming it on success.
     * Returns the matching {@link User}, or null if no enrolled user has that code. Iterates all users
     * with recovery codes; the per-code hashes make the test cheap and the match unique.
     */
    public static User resolveAndConsume(String submittedCode) {
        if (submittedCode == null || submittedCode.isBlank()) return null;
        for (User u : User.getAll()) {
            OmniAuthRecoveryCodesProperty p = u.getProperty(OmniAuthRecoveryCodesProperty.class);
            if (p == null || !p.isEnrolled()) continue;
            if (p.matchAndConsume(submittedCode)) {
                try {
                    u.save();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "SOS: failed to persist consumed recovery code for " + u.getId(), e);
                }
                return u;
            }
        }
        return null;
    }

    /**
     * (Re)grant global {@link Jenkins#ADMINISTER} to {@code sid} on the active <b>matrix-based</b>
     * authorization strategy — repairs the "nobody is admin" lockout. Additive and idempotent.
     *
     * <p>Works with any {@link GlobalMatrixAuthorizationStrategy} and its subclasses — OmniAuth's own
     * strategy, plus the stock <em>Matrix-based</em> and <em>Project-based Matrix</em> strategies — since
     * they share the same {@code matrixauth} grant API. Returns false for a non-matrix strategy
     * (Role-Based, custom, "anyone can do anything"): the recovered user is still signed in, but admin
     * could not be restored this way — the caller surfaces that and {@code config.xml} is the fallback.
     */
    public static boolean grantAdminister(String sid) {
        try {
            hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
            if (!(strat instanceof GlobalMatrixAuthorizationStrategy)) return false;
            GlobalMatrixAuthorizationStrategy matrix = (GlobalMatrixAuthorizationStrategy) strat;
            matrix.add(Jenkins.ADMINISTER, new PermissionEntry(AuthorizationType.USER, sid));
            Jenkins.get().save();
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SOS: could not grant ADMINISTER to " + sid, e);
            return false;
        }
    }

    /** Friendly name of the active authorization strategy, for the "admin not restored" message. */
    public static String activeAuthorizationStrategyName() {
        try {
            hudson.security.AuthorizationStrategy s = Jenkins.get().getAuthorizationStrategy();
            if (s == null) return "unknown";
            String dn = s.getDescriptor() != null ? s.getDescriptor().getDisplayName() : null;
            return (dn != null && !dn.isBlank()) ? dn : s.getClass().getSimpleName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
