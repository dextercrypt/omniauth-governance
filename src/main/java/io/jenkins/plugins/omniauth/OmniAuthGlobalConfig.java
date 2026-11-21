package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.User;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.omniauth.config.LifecycleConfig;
import io.jenkins.plugins.omniauth.config.LoginBrandingConfig;
import io.jenkins.plugins.omniauth.config.NotificationConfig;
import io.jenkins.plugins.omniauth.config.ProvisioningConfig;
import io.jenkins.plugins.omniauth.config.SecurityConfig;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Global configuration for the plugin. The actual settings now live in cohesive per-area holders —
 * {@link NotificationConfig}, {@link SecurityConfig}, {@link LifecycleConfig},
 * {@link LoginBrandingConfig} and {@link ProvisioningConfig} — and this class is a thin facade over
 * them: it keeps the flat accessor surface (so JCasC, the backup reflection and every jelly/helper
 * caller stay unchanged) and delegates each call to the relevant holder.
 *
 * <p>Persistence writes the holders directly. A pre-split {@code config.xml} (flat fields) is still
 * read through the legacy fields at the bottom of this class and lifted into the holders by
 * {@link #readResolve()}; that shim can be removed once no such files remain in the wild.
 */
@Extension
public class OmniAuthGlobalConfig extends GlobalConfiguration {

    /** Canonical event catalogue. Kept here as an alias so existing references keep resolving. */
    public static final String[] ALL_EVENTS = NotificationConfig.ALL_EVENTS;

    // ── Per-area configuration holders (authoritative state) ──────────────────
    // Left null at declaration on purpose: Jenkins' XStream runs field initializers before it
    // populates from config.xml, so a null check is the only reliable "was this element absent?"
    // signal for readResolve(). The constructor fills any that are still null after load().
    private NotificationConfig  notifications;
    private SecurityConfig      security;
    private LifecycleConfig     lifecycle;
    private LoginBrandingConfig branding;
    private ProvisioningConfig  provisioning;

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static OmniAuthGlobalConfig get() {
        return GlobalConfiguration.all().get(OmniAuthGlobalConfig.class);
    }

    public OmniAuthGlobalConfig() {
        load();
        // First run (no config.xml) or any holder a partial file didn't carry: give it a default.
        if (notifications == null) notifications = new NotificationConfig();
        if (security      == null) security      = new SecurityConfig();
        if (lifecycle     == null) lifecycle     = new LifecycleConfig();
        if (branding      == null) branding      = new LoginBrandingConfig();
        if (provisioning  == null) provisioning  = new ProvisioningConfig();
    }

    /** Deserialization hook: lift a pre-split flat config.xml into the per-area holders. */
    protected Object readResolve() {
        if (notifications == null) { notifications = new NotificationConfig(); migrateNotifications(); }
        if (security      == null) { security      = new SecurityConfig();     migrateSecurity(); }
        if (lifecycle     == null) { lifecycle     = new LifecycleConfig();    migrateLifecycle(); }
        if (branding      == null) { branding      = new LoginBrandingConfig(); migrateBranding(); }
        if (provisioning  == null) { provisioning  = new ProvisioningConfig(); migrateProvisioning(); }
        // Drop every legacy field now that its value lives in a holder, so a later save() does not
        // write the flat elements back alongside the holders. Done unconditionally: even a file that
        // already has the holders may still carry stale flat elements from an interrupted migration.
        clearLegacyFields();
        return this;
    }

    /** Null out all the @Deprecated legacy migration fields so they are never re-serialized. */
    private void clearLegacyFields() {
        for (java.lang.reflect.Field f : OmniAuthGlobalConfig.class.getDeclaredFields()) {
            if (f.isAnnotationPresent(Deprecated.class)) {
                try {
                    f.setAccessible(true);
                    f.set(this, null);
                } catch (ReflectiveOperationException ignored) {
                    // a field we cannot clear simply stays; it is read-only migration scaffolding
                }
            }
        }
    }

    // ── Holder accessors (used by the save handlers) ──────────────────────────
    public NotificationConfig  getNotifications() { return notifications; }
    public SecurityConfig      getSecurity()      { return security; }
    public LifecycleConfig     getLifecycle()     { return lifecycle; }
    public LoginBrandingConfig getBranding()      { return branding; }
    public ProvisioningConfig  getProvisioning()  { return provisioning; }

    // ── Protected users ───────────────────────────────────────────────────────
    public List<String> getProtectedUsers()  { return security.getProtectedUsers(); }
    public boolean isProtected(String uid)    { return security.isProtected(uid); }
    /**
     * Replace the protected-users list and persist, WITHOUT touching any other setting.
     * Use this instead of routing a single-field change through {@link #configure}.
     */
    public void setProtectedUsers(List<String> users) {
        security.setProtectedUsers(users);
        save();
    }

    // ── Thresholds / cleanup / retention (lifecycle) ──────────────────────────
    public int getStaleThresholdDays()       { return lifecycle.getStaleThresholdDays(); }
    @DataBoundSetter public void setStaleThresholdDays(int v)  { lifecycle.setStaleThresholdDays(v); }
    public int getActiveThresholdDays()      { return lifecycle.getActiveThresholdDays(); }
    @DataBoundSetter public void setActiveThresholdDays(int v) { lifecycle.setActiveThresholdDays(v); }
    public int getAuditRetentionDays()       { return lifecycle.getAuditRetentionDays(); }
    @DataBoundSetter public void setAuditRetentionDays(int v) { lifecycle.setAuditRetentionDays(v); }
    public boolean isCleanupEnabled()        { return lifecycle.isCleanupEnabled(); }
    @DataBoundSetter public void setCleanupEnabled(boolean v) { lifecycle.setCleanupEnabled(v); }
    public boolean isCleanupDryRun()         { return lifecycle.isCleanupDryRun(); }
    @DataBoundSetter public void setCleanupDryRun(boolean v)  { lifecycle.setCleanupDryRun(v); }
    public String getCleanupCron()           { return lifecycle.getCleanupCron(); }
    @DataBoundSetter public void setCleanupCron(String v)     { lifecycle.setCleanupCron(v); }
    public int getCleanupMaxDeletions()      { return lifecycle.getCleanupMaxDeletions(); }
    @DataBoundSetter public void setCleanupMaxDeletions(int v) { lifecycle.setCleanupMaxDeletions(v); }
    public String getCleanupNotifyEmail()    { return lifecycle.getCleanupNotifyEmail(); }
    @DataBoundSetter public void setCleanupNotifyEmail(String v) { lifecycle.setCleanupNotifyEmail(v); }

    // SMTP (notifications)
    public String  getSmtpHost()        { return notifications.getSmtpHost(); }
    @DataBoundSetter public void setSmtpHost(String v)     { notifications.setSmtpHost(v); }
    public int     getSmtpPort()        { return notifications.getSmtpPort(); }
    @DataBoundSetter public void setSmtpPort(int v)        { notifications.setSmtpPort(v); }
    public String  getSmtpUsername()    { return notifications.getSmtpUsername(); }
    @DataBoundSetter public void setSmtpUsername(String v) { notifications.setSmtpUsername(v); }
    public Secret  getSmtpPassword()    { return notifications.getSmtpPassword(); }
    // Secret-typed setter so JCasC exports the password ENCRYPTED and can inject it via ${ENV}.
    @DataBoundSetter
    public void    setSmtpPassword(Secret v) { notifications.setSmtpPassword(v); }
    public boolean isSmtpTls()          { return notifications.isSmtpTls(); }
    @DataBoundSetter public void setSmtpTls(boolean v)        { notifications.setSmtpTls(v); }
    public String  getSmtpFromAddress() { return notifications.getSmtpFromAddress(); }
    @DataBoundSetter public void setSmtpFromAddress(String v) { notifications.setSmtpFromAddress(v); }
    public String  getSmtpFromName()    { return notifications.getSmtpFromName(); }
    @DataBoundSetter public void setSmtpFromName(String v)    { notifications.setSmtpFromName(v); }

    // Login branding
    public String  getLoginHeading()   { return branding.getLoginHeading(); }
    public void    setLoginHeading(String v)  { branding.setLoginHeading(v); }
    public String  getLoginTabTitle()  { return branding.getLoginTabTitle(); }
    public void    setLoginTabTitle(String v) { branding.setLoginTabTitle(v); }
    public String  getLoginAnnouncementText() { return branding.getLoginAnnouncementText(); }
    public void    setLoginAnnouncementText(String v) { branding.setLoginAnnouncementText(v); }
    public String  getLoginFooterText()       { return branding.getLoginFooterText(); }
    public void    setLoginFooterText(String v)       { branding.setLoginFooterText(v); }
    public String  getLoginBackground()       { return branding.getLoginBackground(); }
    public void    setLoginBackground(String v) { branding.setLoginBackground(v); }
    public String  getLoginLogoPosition()     { return branding.getLoginLogoPosition(); }
    public void    setLoginLogoPosition(String v) { branding.setLoginLogoPosition(v); }
    public String  getLoginLogoSize()         { return branding.getLoginLogoSize(); }
    public void    setLoginLogoSize(String v) { branding.setLoginLogoSize(v); }
    public String  getLoginButler()           { return branding.getLoginButler(); }
    public void    setLoginButler(String v)   { branding.setLoginButler(v); }
    public String  getResolvedLoginButler()   { return branding.getResolvedLoginButler(); }

    // Notification branding
    public String  getNotificationLogoUrl()  { return notifications.getNotificationLogoUrl(); }
    @DataBoundSetter public void setNotificationLogoUrl(String v)    { notifications.setNotificationLogoUrl(v); }
    public String  getNotificationFooterNote() { return notifications.getNotificationFooterNote(); }
    @DataBoundSetter public void setNotificationFooterNote(String v) { notifications.setNotificationFooterNote(v); }
    public String  getSmtpReplyTo()     { return notifications.getSmtpReplyTo(); }
    @DataBoundSetter public void setSmtpReplyTo(String v)  { notifications.setSmtpReplyTo(v); }
    public String  getNotifyEmails()    { return notifications.getNotifyEmails(); }
    @DataBoundSetter public void setNotifyEmails(String v) { notifications.setNotifyEmails(v); }

    public boolean isSmtpConfigured() { return notifications.isSmtpConfigured(); }

    /**
     * Send a test email using the values currently on the Notifications form (falling back to the
     * stored config for any blank field). Wired from notifications.jelly via {@code f:validateButton}.
     */
    @RequirePOST
    public FormValidation doSendTestEmail(
            @QueryParameter String smtpHost,
            @QueryParameter String smtpPort,
            @QueryParameter String smtpUsername,
            @QueryParameter String smtpPassword,
            @QueryParameter boolean smtpTls,
            @QueryParameter String smtpFromAddress,
            @QueryParameter String smtpFromName,
            @QueryParameter String smtpReplyTo,
            @QueryParameter String notifyEmails) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        String host     = nonBlank(smtpHost)        ? smtpHost.trim()     : getSmtpHost();
        String portStr  = nonBlank(smtpPort)        ? smtpPort.trim()     : String.valueOf(getSmtpPort());
        String username = nonBlank(smtpUsername)    ? smtpUsername        : getSmtpUsername();
        String fromAddr = nonBlank(smtpFromAddress) ? smtpFromAddress     : getSmtpFromAddress();
        String fromName = nonBlank(smtpFromName)    ? smtpFromName        : getSmtpFromName();
        String replyTo  = nonBlank(smtpReplyTo)     ? smtpReplyTo         : getSmtpReplyTo();
        String to       = nonBlank(notifyEmails)    ? notifyEmails        : getNotifyEmails();

        // Split host:port if a combined value was pasted (e.g. from another tool's config).
        if (host != null && host.contains(":")) {
            String[] parts = host.split(":", 2);
            host = parts[0].trim();
            if (portStr.isEmpty() || portStr.equals("465")) portStr = parts[1].trim();
        }

        // Use the submitted password if present, otherwise the stored secret.
        String password = smtpPassword;
        if (password == null || password.trim().isEmpty()) {
            password = getSmtpPassword() != null ? getSmtpPassword().getPlainText() : "";
        } else {
            password = password.trim();
        }

        int port = 465;
        try { port = Integer.parseInt(portStr.trim()); } catch (NumberFormatException ignored) { /* keep 465 */ }

        if (host == null || host.isEmpty() || fromAddr == null || fromAddr.isEmpty()
                || username == null || username.isEmpty() || password.isEmpty()) {
            return FormValidation.error("Fill in host, username, password and from address first");
        }
        if (to == null || to.isEmpty()) {
            return FormValidation.error("Fill in at least one notification recipient");
        }
        try {
            SmtpHelper.test(host, port, username, password, smtpTls, fromAddr, fromName, replyTo, to);
            return FormValidation.ok("Test email sent to " + to);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "unknown error";
            return FormValidation.error("Could not send: " + msg);
        }
    }

    private static boolean nonBlank(String s) { return s != null && !s.trim().isEmpty(); }

    // Notifications master + channels
    public boolean isNotificationsEnabled() { return notifications.isNotificationsEnabled(); }
    @DataBoundSetter public void setNotificationsEnabled(boolean v) { notifications.setNotificationsEnabled(v); }
    public boolean isSmtpEnabled()          { return notifications.isSmtpEnabled(); }
    @DataBoundSetter public void setSmtpEnabled(boolean v)  { notifications.setSmtpEnabled(v); }
    public boolean isSlackEnabled()         { return notifications.isSlackEnabled(); }
    @DataBoundSetter public void setSlackEnabled(boolean v) { notifications.setSlackEnabled(v); }
    public boolean isTeamsEnabled()         { return notifications.isTeamsEnabled(); }
    @DataBoundSetter public void setTeamsEnabled(boolean v) { notifications.setTeamsEnabled(v); }

    public Secret  getSlackWebhookUrl()      { return notifications.getSlackWebhookUrl(); }
    @DataBoundSetter
    public void    setSlackWebhookUrl(Secret v) { notifications.setSlackWebhookUrl(v); }
    public String  getSlackWebhookUrlPlain() { return notifications.getSlackWebhookUrlPlain(); }

    public Secret  getTeamsWebhookUrl()      { return notifications.getTeamsWebhookUrl(); }
    @DataBoundSetter
    public void    setTeamsWebhookUrl(Secret v) { notifications.setTeamsWebhookUrl(v); }
    public String  getTeamsWebhookUrlPlain() { return notifications.getTeamsWebhookUrlPlain(); }

    // JIT history retention
    public int getJitHistoryRetentionDays() { return lifecycle.getJitHistoryRetentionDays(); }
    public void setJitHistoryRetentionDays(int v) { lifecycle.setJitHistoryRetentionDays(v); }

    // Brute force
    public int getBruteForceThreshold() { return security.getBruteForceThreshold(); }
    @DataBoundSetter public void setBruteForceThreshold(int v) { security.setBruteForceThreshold(v); }
    public boolean isBruteForceLockoutEnabled() { return security.isBruteForceLockoutEnabled(); }
    public void setBruteForceLockoutEnabled(boolean v) { security.setBruteForceLockoutEnabled(v); }
    public int getBruteForceLockoutMinutes() { return security.getBruteForceLockoutMinutes(); }
    public void setBruteForceLockoutMinutes(int v) { security.setBruteForceLockoutMinutes(v); }
    public boolean isSosEnabled() { return security.isSosEnabled(); }
    @DataBoundSetter public void setSosEnabled(boolean v) { security.setSosEnabled(v); }

    // Stale warning
    public boolean isStaleWarningEnabled()    { return lifecycle.isStaleWarningEnabled(); }
    @DataBoundSetter public void setStaleWarningEnabled(boolean v)  { lifecycle.setStaleWarningEnabled(v); }
    public String  getStaleWarningCron()      { return lifecycle.getStaleWarningCron(); }
    @DataBoundSetter public void setStaleWarningCron(String v)      { lifecycle.setStaleWarningCron(v); }
    public int     getStaleWarningWindowDays(){ return lifecycle.getStaleWarningWindowDays(); }
    @DataBoundSetter public void setStaleWarningWindowDays(int v)   { lifecycle.setStaleWarningWindowDays(v); }

    // Access review
    public boolean isAccessReviewEnabled()       { return lifecycle.isAccessReviewEnabled(); }
    @DataBoundSetter public void setAccessReviewEnabled(boolean v)     { lifecycle.setAccessReviewEnabled(v); }
    public int     getAccessReviewThresholdDays() { return lifecycle.getAccessReviewThresholdDays(); }
    @DataBoundSetter public void setAccessReviewThresholdDays(int v)   { lifecycle.setAccessReviewThresholdDays(v); }

    // ── Provisioning portal ───────────────────────────────────────────────────
    public boolean isProvisioningEnabled()            { return provisioning.isProvisioningEnabled(); }
    public void    setProvisioningEnabled(boolean v)  { provisioning.setProvisioningEnabled(v); }

    public boolean isProvisioningAllowEntra()           { return provisioning.isProvisioningAllowEntra(); }
    public void    setProvisioningAllowEntra(boolean v) { provisioning.setProvisioningAllowEntra(v); }

    public boolean isProvisioningAllowNative()           { return provisioning.isProvisioningAllowNative(); }
    public void    setProvisioningAllowNative(boolean v) { provisioning.setProvisioningAllowNative(v); }

    public String  getProvisioningAllowedDomains()          { return provisioning.getProvisioningAllowedDomains(); }
    public void    setProvisioningAllowedDomains(String v)  { provisioning.setProvisioningAllowedDomains(v); }

    public int     getProvisioningDefaultDurationDays()         { return provisioning.getProvisioningDefaultDurationDays(); }
    public void    setProvisioningDefaultDurationDays(int v)    { provisioning.setProvisioningDefaultDurationDays(v); }

    public String  getProvisioningExpiryAction()         { return provisioning.getProvisioningExpiryAction(); }
    public void    setProvisioningExpiryAction(String v) { provisioning.setProvisioningExpiryAction(v); }

    public int     getProvisioningExpiryReminderDays()      { return provisioning.getProvisioningExpiryReminderDays(); }
    public void    setProvisioningExpiryReminderDays(int v) { provisioning.setProvisioningExpiryReminderDays(v); }

    public int     getActivationLinkTtlHours()      { return provisioning.getActivationLinkTtlHours(); }
    public void    setActivationLinkTtlHours(int v) { provisioning.setActivationLinkTtlHours(v); }

    public int     getProvisioningMaxPendingPerUser()        { return provisioning.getProvisioningMaxPendingPerUser(); }
    public void    setProvisioningMaxPendingPerUser(int v)   { provisioning.setProvisioningMaxPendingPerUser(v); }

    public int     getProvisioningBreakerMax()          { return provisioning.getProvisioningBreakerMax(); }
    public void    setProvisioningBreakerMax(int v)     { provisioning.setProvisioningBreakerMax(v); }

    public int     getProvisioningBreakerWindowMin()        { return provisioning.getProvisioningBreakerWindowMin(); }
    public void    setProvisioningBreakerWindowMin(int v)   { provisioning.setProvisioningBreakerWindowMin(v); }

    public int     getProvisioningBreakerCooldownMin()        { return provisioning.getProvisioningBreakerCooldownMin(); }
    public void    setProvisioningBreakerCooldownMin(int v)   { provisioning.setProvisioningBreakerCooldownMin(v); }

    /** True when the portal is on and at least one request type is allowed. */
    public boolean isProvisioningUsable() { return provisioning.isProvisioningUsable(); }

    // Per-channel event subscriptions
    public List<String> getSmtpEvents()  { return notifications.getSmtpEvents(); }
    @DataBoundSetter public void setSmtpEvents(List<String> v)  { notifications.setSmtpEvents(v); }
    public List<String> getSlackEvents() { return notifications.getSlackEvents(); }
    @DataBoundSetter public void setSlackEvents(List<String> v) { notifications.setSlackEvents(v); }
    public List<String> getTeamsEvents() { return notifications.getTeamsEvents(); }
    @DataBoundSetter public void setTeamsEvents(List<String> v) { notifications.setTeamsEvents(v); }

    public boolean isSmtpEvent(String event)  { return notifications.isSmtpEvent(event); }
    public boolean isSlackEvent(String event) { return notifications.isSlackEvent(event); }
    public boolean isTeamsEvent(String event) { return notifications.isTeamsEvent(event); }

    public String getSmtpEventsJoined()  { return notifications.getSmtpEventsJoined(); }
    public String getSlackEventsJoined() { return notifications.getSlackEventsJoined(); }
    public String getTeamsEventsJoined() { return notifications.getTeamsEventsJoined(); }

    public List<OmniAuthGlobalConfig.CleanupRunRecord> getCleanupHistory() {
        return lifecycle.getCleanupHistory();
    }

    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>(User.getAll());
        users.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
        return users;
    }

    // ── History mutation ──────────────────────────────────────────────────────

    public synchronized void addCleanupRecord(CleanupRunRecord record) {
        lifecycle.addCleanupRecord(record);
        save();
    }

    // ── configure() ───────────────────────────────────────────────────────────

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Partial update: a field changes ONLY when its key is present in the submitted form, so a
        // page's save handler sends just the fields it owns and a global "System" save (which carries
        // none of our fields) leaves everything untouched. The values land in the per-area holders.
        if (json.has("protectedUsers")) {
            List<String> pu = new ArrayList<>();
            Object raw = json.opt("protectedUsers");
            if (raw instanceof JSONArray) {
                for (Object o : (JSONArray) raw) pu.add(o.toString());
            } else if (raw instanceof String && !((String) raw).isEmpty()) {
                pu.add(raw.toString());
            }
            security.setProtectedUsers(pu);
        }

        if (json.has("staleThresholdDays"))  lifecycle.setStaleThresholdDays(jsonInt(json, "staleThresholdDays",  lifecycle.getStaleThresholdDays()));
        if (json.has("activeThresholdDays")) lifecycle.setActiveThresholdDays(jsonInt(json, "activeThresholdDays", lifecycle.getActiveThresholdDays()));
        if (json.has("auditRetentionDays"))  lifecycle.setAuditRetentionDays(jsonInt(json, "auditRetentionDays", lifecycle.getAuditRetentionDays()));
        if (json.has("cleanupEnabled")) {
            boolean ce = json.optBoolean("cleanupEnabled",  false);
            lifecycle.setCleanupEnabled(ce);
            lifecycle.setCleanupDryRun(!ce || json.optBoolean("cleanupDryRun", true));
        }
        if (json.has("cleanupCron"))         lifecycle.setCleanupCron(jsonStr(json, "cleanupCron",         lifecycle.getCleanupCron()));
        if (json.has("cleanupMaxDeletions")) lifecycle.setCleanupMaxDeletions(jsonInt(json, "cleanupMaxDeletions", lifecycle.getCleanupMaxDeletions()));

        // SMTP — split host:port if the user pasted a combined value.
        boolean portSetFromHost = false;
        if (json.has("smtpHost")) {
            String rawHost = jsonStr(json, "smtpHost", "");
            if (rawHost.contains(":")) {
                String[] hp = rawHost.split(":", 2);
                notifications.setSmtpHost(hp[0].trim());
                try { notifications.setSmtpPort(Integer.parseInt(hp[1].trim())); portSetFromHost = true; } catch (NumberFormatException ignored) { /* keep current port */ }
            } else {
                notifications.setSmtpHost(rawHost);
            }
        }
        if (json.has("smtpPort") && !portSetFromHost) notifications.setSmtpPort(jsonInt(json, "smtpPort", notifications.getSmtpPort()));
        if (json.has("smtpUsername")) notifications.setSmtpUsername(jsonStr(json, "smtpUsername", ""));
        if (json.has("smtpPassword")) {
            String rawPass = jsonStr(json, "smtpPassword", "");
            if (!rawPass.isEmpty()) notifications.setSmtpPassword(Secret.fromString(rawPass));
        }
        if (json.has("smtpTls"))         notifications.setSmtpTls(json.optBoolean("smtpTls", true));
        if (json.has("smtpFromAddress")) notifications.setSmtpFromAddress(jsonStr(json, "smtpFromAddress", ""));
        if (json.has("smtpFromName"))    notifications.setSmtpFromName(jsonStr(json, "smtpFromName",    "Jenkins OmniAuth"));
        if (json.has("smtpReplyTo"))     notifications.setSmtpReplyTo(jsonStr(json, "smtpReplyTo",     ""));
        if (json.has("notifyEmails"))    notifications.setNotifyEmails(jsonStr(json, "notifyEmails",    ""));
        if (json.has("loginHeading"))          branding.setLoginHeading(jsonStr(json, "loginHeading",          ""));
        if (json.has("loginTabTitle"))         branding.setLoginTabTitle(jsonStr(json, "loginTabTitle",         ""));
        if (json.has("loginAnnouncementText")) branding.setLoginAnnouncementText(jsonStr(json, "loginAnnouncementText", ""));
        if (json.has("loginFooterText"))       branding.setLoginFooterText(jsonStr(json, "loginFooterText",       ""));
        if (json.has("loginBackground"))       branding.setLoginBackground(jsonStr(json, "loginBackground",       "omniauth"));
        if (json.has("loginLogoPosition"))     branding.setLoginLogoPosition(jsonStr(json, "loginLogoPosition",     "a"));
        if (json.has("loginLogoSize"))         branding.setLoginLogoSize(jsonStr(json, "loginLogoSize",         "40"));
        if (json.has("loginButler"))           branding.setLoginButler(jsonStr(json, "loginButler",           "default"));
        if (json.has("notificationLogoUrl"))    notifications.setNotificationLogoUrl(jsonStr(json, "notificationLogoUrl",    ""));
        if (json.has("notificationFooterNote")) notifications.setNotificationFooterNote(jsonStr(json, "notificationFooterNote", ""));

        // Notifications master + channels
        if (json.has("notificationsEnabled")) notifications.setNotificationsEnabled(json.optBoolean("notificationsEnabled", false));
        if (json.has("smtpEnabled"))          notifications.setSmtpEnabled(json.optBoolean("smtpEnabled",          false));
        if (json.has("slackEnabled"))         notifications.setSlackEnabled(json.optBoolean("slackEnabled",         false));
        if (json.has("slackWebhookUrl"))      notifications.setSlackWebhookUrl(Secret.fromString(jsonStr(json, "slackWebhookUrl", "")));
        if (json.has("teamsEnabled"))         notifications.setTeamsEnabled(json.optBoolean("teamsEnabled",         false));
        if (json.has("teamsWebhookUrl"))      notifications.setTeamsWebhookUrl(Secret.fromString(jsonStr(json, "teamsWebhookUrl", "")));

        // Brute force
        if (json.has("bruteForceThreshold"))      security.setBruteForceThreshold(jsonInt(json, "bruteForceThreshold", security.getBruteForceThreshold()));
        if (json.has("bruteForceLockoutEnabled")) security.setBruteForceLockoutEnabled(json.optBoolean("bruteForceLockoutEnabled", true));
        if (json.has("bruteForceLockoutMinutes")) security.setBruteForceLockoutMinutes(jsonInt(json, "bruteForceLockoutMinutes", security.getBruteForceLockoutMinutes()));
        if (json.has("sosEnabled"))               security.setSosEnabled(json.optBoolean("sosEnabled", true));

        // Stale warning
        if (json.has("staleWarningEnabled"))    lifecycle.setStaleWarningEnabled(json.optBoolean("staleWarningEnabled", false));
        if (json.has("staleWarningCron"))       lifecycle.setStaleWarningCron(jsonStr(json, "staleWarningCron",       "0 9 * * 1"));
        if (json.has("staleWarningWindowDays")) lifecycle.setStaleWarningWindowDays(jsonInt(json, "staleWarningWindowDays", lifecycle.getStaleWarningWindowDays()));

        // Access review
        if (json.has("accessReviewEnabled"))       lifecycle.setAccessReviewEnabled(json.optBoolean("accessReviewEnabled", false));
        if (json.has("accessReviewThresholdDays")) lifecycle.setAccessReviewThresholdDays(jsonInt(json, "accessReviewThresholdDays", lifecycle.getAccessReviewThresholdDays()));

        // Per-channel event subscriptions
        if (json.has("smtpEvents"))  notifications.setSmtpEvents(jsonStringList(json, "smtpEvents"));
        if (json.has("slackEvents")) notifications.setSlackEvents(jsonStringList(json, "slackEvents"));
        if (json.has("teamsEvents")) notifications.setTeamsEvents(jsonStringList(json, "teamsEvents"));

        save();
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int jsonInt(JSONObject json, String key, int fallback) {
        try {
            Object v = json.opt(key);
            return v == null ? fallback : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) { return fallback; }
    }

    private static List<String> jsonStringList(JSONObject json, String key) {
        List<String> result = new ArrayList<>();
        Object raw = json.opt(key);
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) if (o != null) result.add(o.toString());
        } else if (raw instanceof String && !((String) raw).isEmpty()) {
            result.add(raw.toString());
        }
        return result;
    }

    private static String jsonStr(JSONObject json, String key, String fallback) {
        Object v = json.opt(key);
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    // ── CleanupRunRecord ──────────────────────────────────────────────────────

    public static final class CleanupRunRecord {
        private final String       timestamp;
        private final boolean      dryRun;
        private final int          usersScanned;
        private final int          usersAffected;
        private final int          skippedProtected;
        private final List<String> affectedUserIds;

        public CleanupRunRecord(String timestamp, boolean dryRun,
                                int usersScanned, int usersAffected,
                                int skippedProtected, List<String> affectedUserIds) {
            this.timestamp        = timestamp;
            this.dryRun           = dryRun;
            this.usersScanned     = usersScanned;
            this.usersAffected    = usersAffected;
            this.skippedProtected = skippedProtected;
            this.affectedUserIds  = new ArrayList<>(affectedUserIds);
        }

        public String       getTimestamp()        { return timestamp; }
        public boolean      isDryRun()            { return dryRun; }
        public int          getUsersScanned()     { return usersScanned; }
        public int          getUsersAffected()    { return usersAffected; }
        public int          getSkippedProtected() { return skippedProtected; }
        public List<String> getAffectedUserIds()  { return Collections.unmodifiableList(affectedUserIds); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Legacy flat fields — ONLY read a pre-split config.xml. Populated by XStream from the old flat
    // elements and lifted into the holders by readResolve(); never written to or read elsewhere.
    // Remove this block once no pre-split config.xml can still exist in the wild.
    // ══════════════════════════════════════════════════════════════════════════
    @Deprecated private List<String> protectedUsers;
    @Deprecated private Integer staleThresholdDays;
    @Deprecated private Integer activeThresholdDays;
    @Deprecated private Integer auditRetentionDays;
    @Deprecated private Boolean cleanupEnabled;
    @Deprecated private Boolean cleanupDryRun;
    @Deprecated private String  cleanupCron;
    @Deprecated private Integer cleanupMaxDeletions;
    @Deprecated private String  cleanupNotifyEmail;
    @Deprecated private Boolean notificationsEnabled;
    @Deprecated private Boolean smtpEnabled;
    @Deprecated private Boolean slackEnabled;
    @Deprecated private Secret  slackWebhookUrl;
    @Deprecated private Boolean teamsEnabled;
    @Deprecated private Secret  teamsWebhookUrl;
    @Deprecated private String  smtpHost;
    @Deprecated private Integer smtpPort;
    @Deprecated private String  smtpUsername;
    @Deprecated private Secret  smtpPassword;
    @Deprecated private Boolean smtpTls;
    @Deprecated private String  smtpFromAddress;
    @Deprecated private String  smtpFromName;
    @Deprecated private String  smtpReplyTo;
    @Deprecated private String  notifyEmails;
    @Deprecated private Integer bruteForceThreshold;
    @Deprecated private Boolean bruteForceLockoutEnabled;
    @Deprecated private Integer bruteForceLockoutMinutes;
    @Deprecated private Boolean sosEnabled;
    @Deprecated private Boolean staleWarningEnabled;
    @Deprecated private String  staleWarningCron;
    @Deprecated private Integer staleWarningWindowDays;
    @Deprecated private Boolean accessReviewEnabled;
    @Deprecated private Integer accessReviewThresholdDays;
    @Deprecated private Integer jitHistoryRetentionDays;
    @Deprecated private String  loginHeading;
    @Deprecated private String  loginTabTitle;
    @Deprecated private String  loginAnnouncementText;
    @Deprecated private String  loginFooterText;
    @Deprecated private String  loginBackground;
    @Deprecated private String  loginLogoPosition;
    @Deprecated private String  loginLogoSize;
    @Deprecated private String  loginButler;
    @Deprecated private String  notificationLogoUrl;
    @Deprecated private String  notificationFooterNote;
    @Deprecated private List<String> smtpEvents;
    @Deprecated private List<String> slackEvents;
    @Deprecated private List<String> teamsEvents;
    @Deprecated private Boolean provisioningEnabled;
    @Deprecated private Boolean provisioningAllowEntra;
    @Deprecated private Boolean provisioningAllowNative;
    @Deprecated private String  provisioningAllowedDomains;
    @Deprecated private Integer provisioningDefaultDurationDays;
    @Deprecated private String  provisioningExpiryAction;
    @Deprecated private Integer provisioningMaxPendingPerUser;
    @Deprecated private Integer provisioningBreakerMax;
    @Deprecated private Integer provisioningBreakerWindowMin;
    @Deprecated private Integer provisioningBreakerCooldownMin;
    @Deprecated private Integer provisioningExpiryReminderDays;
    @Deprecated private Integer activationLinkTtlHours;
    @Deprecated private List<CleanupRunRecord> cleanupHistory;

    private void migrateNotifications() {
        if (notificationsEnabled != null) notifications.setNotificationsEnabled(notificationsEnabled);
        if (smtpEnabled  != null) notifications.setSmtpEnabled(smtpEnabled);
        if (slackEnabled != null) notifications.setSlackEnabled(slackEnabled);
        if (slackWebhookUrl != null) notifications.setSlackWebhookUrl(slackWebhookUrl);
        if (teamsEnabled != null) notifications.setTeamsEnabled(teamsEnabled);
        if (teamsWebhookUrl != null) notifications.setTeamsWebhookUrl(teamsWebhookUrl);
        if (smtpHost     != null) notifications.setSmtpHost(smtpHost);
        if (smtpPort     != null) notifications.setSmtpPort(smtpPort);
        if (smtpUsername != null) notifications.setSmtpUsername(smtpUsername);
        if (smtpPassword != null) notifications.setSmtpPassword(smtpPassword);
        if (smtpTls      != null) notifications.setSmtpTls(smtpTls);
        if (smtpFromAddress != null) notifications.setSmtpFromAddress(smtpFromAddress);
        if (smtpFromName != null) notifications.setSmtpFromName(smtpFromName);
        if (smtpReplyTo  != null) notifications.setSmtpReplyTo(smtpReplyTo);
        if (notifyEmails != null) notifications.setNotifyEmails(notifyEmails);
        if (notificationLogoUrl != null) notifications.setNotificationLogoUrl(notificationLogoUrl);
        if (notificationFooterNote != null) notifications.setNotificationFooterNote(notificationFooterNote);
        if (smtpEvents  != null) notifications.setSmtpEvents(smtpEvents);
        if (slackEvents != null) notifications.setSlackEvents(slackEvents);
        if (teamsEvents != null) notifications.setTeamsEvents(teamsEvents);
    }

    private void migrateSecurity() {
        if (bruteForceThreshold != null) security.setBruteForceThreshold(bruteForceThreshold);
        if (bruteForceLockoutEnabled != null) security.setBruteForceLockoutEnabled(bruteForceLockoutEnabled);
        if (bruteForceLockoutMinutes != null) security.setBruteForceLockoutMinutes(bruteForceLockoutMinutes);
        if (sosEnabled != null) security.setSosEnabled(sosEnabled);
        if (protectedUsers != null) security.setProtectedUsers(protectedUsers);
    }

    private void migrateLifecycle() {
        if (staleThresholdDays  != null) lifecycle.setStaleThresholdDays(staleThresholdDays);
        if (activeThresholdDays != null) lifecycle.setActiveThresholdDays(activeThresholdDays);
        if (auditRetentionDays  != null) lifecycle.setAuditRetentionDays(auditRetentionDays);
        if (cleanupEnabled != null) lifecycle.setCleanupEnabled(cleanupEnabled);
        if (cleanupDryRun  != null) lifecycle.setCleanupDryRun(cleanupDryRun);
        if (cleanupCron    != null) lifecycle.setCleanupCron(cleanupCron);
        if (cleanupMaxDeletions != null) lifecycle.setCleanupMaxDeletions(cleanupMaxDeletions);
        if (cleanupNotifyEmail  != null) lifecycle.setCleanupNotifyEmail(cleanupNotifyEmail);
        if (staleWarningEnabled != null) lifecycle.setStaleWarningEnabled(staleWarningEnabled);
        if (staleWarningCron    != null) lifecycle.setStaleWarningCron(staleWarningCron);
        if (staleWarningWindowDays != null) lifecycle.setStaleWarningWindowDays(staleWarningWindowDays);
        if (accessReviewEnabled != null) lifecycle.setAccessReviewEnabled(accessReviewEnabled);
        if (accessReviewThresholdDays != null) lifecycle.setAccessReviewThresholdDays(accessReviewThresholdDays);
        if (jitHistoryRetentionDays != null) lifecycle.setJitHistoryRetentionDays(jitHistoryRetentionDays);
        if (cleanupHistory != null) for (CleanupRunRecord r : cleanupHistory) lifecycle.addCleanupRecord(r);
    }

    private void migrateBranding() {
        if (loginHeading != null) branding.setLoginHeading(loginHeading);
        if (loginTabTitle != null) branding.setLoginTabTitle(loginTabTitle);
        if (loginAnnouncementText != null) branding.setLoginAnnouncementText(loginAnnouncementText);
        if (loginFooterText != null) branding.setLoginFooterText(loginFooterText);
        if (loginBackground != null) branding.setLoginBackground(loginBackground);
        if (loginLogoPosition != null) branding.setLoginLogoPosition(loginLogoPosition);
        if (loginLogoSize != null) branding.setLoginLogoSize(loginLogoSize);
        if (loginButler != null) branding.setLoginButler(loginButler);
    }

    private void migrateProvisioning() {
        if (provisioningEnabled != null) provisioning.setProvisioningEnabled(provisioningEnabled);
        if (provisioningAllowEntra != null) provisioning.setProvisioningAllowEntra(provisioningAllowEntra);
        if (provisioningAllowNative != null) provisioning.setProvisioningAllowNative(provisioningAllowNative);
        if (provisioningAllowedDomains != null) provisioning.setProvisioningAllowedDomains(provisioningAllowedDomains);
        if (provisioningDefaultDurationDays != null) provisioning.setProvisioningDefaultDurationDays(provisioningDefaultDurationDays);
        if (provisioningExpiryAction != null) provisioning.setProvisioningExpiryAction(provisioningExpiryAction);
        if (provisioningMaxPendingPerUser != null) provisioning.setProvisioningMaxPendingPerUser(provisioningMaxPendingPerUser);
        if (provisioningBreakerMax != null) provisioning.setProvisioningBreakerMax(provisioningBreakerMax);
        if (provisioningBreakerWindowMin != null) provisioning.setProvisioningBreakerWindowMin(provisioningBreakerWindowMin);
        if (provisioningBreakerCooldownMin != null) provisioning.setProvisioningBreakerCooldownMin(provisioningBreakerCooldownMin);
        if (provisioningExpiryReminderDays != null) provisioning.setProvisioningExpiryReminderDays(provisioningExpiryReminderDays);
        if (activationLinkTtlHours != null) provisioning.setActivationLinkTtlHours(activationLinkTtlHours);
    }
}
