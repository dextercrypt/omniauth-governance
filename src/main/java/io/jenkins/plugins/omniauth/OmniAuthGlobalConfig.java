package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.User;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Extension
public class OmniAuthGlobalConfig extends GlobalConfiguration {

    private static final int MAX_HISTORY = 10;

    // ── Protected users ───────────────────────────────────────────────────────
    private List<String> protectedUsers = new ArrayList<>();

    // ── Thresholds ────────────────────────────────────────────────────────────
    private int staleThresholdDays  = 90;
    private int activeThresholdDays = 30;

    // ── Audit log retention (days; 0 = keep forever) ──────────────────────────
    private int auditRetentionDays = 365;

    // ── Automated cleanup ─────────────────────────────────────────────────────
    private boolean cleanupEnabled      = false;
    private boolean cleanupDryRun       = true;
    private String  cleanupCron         = "0 2 * * 0";
    private int     cleanupMaxDeletions = 50;
    private String  cleanupNotifyEmail  = ""; // legacy — kept for migration, replaced by notifyEmails

    // ── Notifications master + channels ──────────────────────────────────────
    private boolean notificationsEnabled = false;
    private boolean smtpEnabled          = false;
    private boolean slackEnabled         = false;
    private Secret  slackWebhookUrl      = null;   // incoming-webhook URL = bearer secret -> encrypted at rest
    private boolean teamsEnabled         = false;
    private Secret  teamsWebhookUrl      = null;   // incoming-webhook URL = bearer secret -> encrypted at rest

    // ── SMTP configuration ────────────────────────────────────────────────────
    private String  smtpHost        = "";
    private int     smtpPort        = 587;
    private String  smtpUsername    = "";
    private Secret  smtpPassword    = null;
    private boolean smtpTls         = true;
    private String  smtpFromAddress = "";
    private String  smtpFromName    = "Jenkins OmniAuth";
    private String  smtpReplyTo     = "";
    private String  notifyEmails    = ""; // comma-separated recipients

    // ── Brute force detection + lockout ───────────────────────────────────────
    private int bruteForceThreshold = 5;
    /** Enforce a temporary account lockout once the threshold is reached (not just alert). */
    private boolean bruteForceLockoutEnabled = true;
    /** How long a locked account stays locked, in minutes. */
    private int bruteForceLockoutMinutes = 15;

    // ── SOS Mode (lockout recovery) ───────────────────────────────────────────
    /** When true (default), a {@code JENKINS_HOME/omniauth-sos.request} trigger file arms SOS Mode
     *  and opens {@code /sos/recover}. See {@link OmniAuthSos}. */
    private boolean sosEnabled = true;

    // ── Stale warning ─────────────────────────────────────────────────────────
    private boolean staleWarningEnabled    = false;
    private String  staleWarningCron       = "0 9 * * 1";
    private int     staleWarningWindowDays = 14;

    // ── Access review ─────────────────────────────────────────────────────────
    private boolean accessReviewEnabled       = false;
    private int     accessReviewThresholdDays = 90;

    // ── JIT history retention ─────────────────────────────────────────────────
    private int jitHistoryRetentionDays = 90;

    // ── Login page branding ───────────────────────────────────────────────────
    private String loginHeading          = "";
    private String loginTabTitle         = "";
    private String loginAnnouncementText = "";
    private String loginFooterText       = "";
    // Which background the login panel shows. Known values: "omniauth" (animated reactor,
    // default) and "starburst" (Jenkins' native starburst). New designs can be added later.
    private String loginBackground       = "omniauth";
    // Where the company logo sits on the login form. Known values: "a" (top of the
    // form column, left-aligned — default), "e" (pinned top-right corner of the panel),
    // "f" (logo centred above the form). Only takes effect when a logo is uploaded.
    private String loginLogoPosition     = "a";
    // Company-logo height on the login form, in px. Known values: "20", "30", "40" (default).
    private String loginLogoSize         = "40";
    // Centre figure on the animated login panel. Known values: "default" (native Jenkins
    // butler) and "austin" (the "Willie Nelson"/Austin Jenkins butler, CC BY-SA 3.0).
    private String loginButler           = "default";

    // ── Notification branding ─────────────────────────────────────────────────
    private String notificationLogoUrl  = "";
    private String notificationFooterNote = "";

    // ── Per-channel event subscriptions ──────────────────────────────────────
    private List<String> smtpEvents  = new ArrayList<>(java.util.Arrays.asList(ALL_EVENTS));
    private List<String> slackEvents = new ArrayList<>();
    private List<String> teamsEvents = new ArrayList<>();

    public static final String[] ALL_EVENTS = {
        "cleanup", "userDeleted", "configChanged", "protectedListChanged",
        "graphApiFailure", "adminGranted", "bruteForce", "staleWarning", "accessReview",
        // JIT (were dispatched but missing from the catalog, so they defaulted off — now included)
        "jitRequest", "jitApproved", "jitDenied",
        // Provisioning + account lifecycle (admin-facing)
        "provisionRequest", "accountExpired", "accountLocked"
    };

    // ── Cleanup history ───────────────────────────────────────────────────────
    private List<CleanupRunRecord> cleanupHistory = new ArrayList<>();

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static OmniAuthGlobalConfig get() {
        return GlobalConfiguration.all().get(OmniAuthGlobalConfig.class);
    }

    public OmniAuthGlobalConfig() { load(); }

    // ── Getters ───────────────────────────────────────────────────────────────

    public List<String> getProtectedUsers()  { return Collections.unmodifiableList(protectedUsers); }
    public boolean isProtected(String uid)   { return uid != null && protectedUsers.contains(uid); }
    /**
     * Replace the protected-users list and persist, WITHOUT touching any other setting.
     * Use this instead of routing a single-field change through {@link #configure} — that
     * reparses every field from the given JSON and resets anything absent to its default.
     */
    public void setProtectedUsers(List<String> users) {
        this.protectedUsers = (users == null) ? new ArrayList<>() : new ArrayList<>(users);
        save();
    }
    public int getStaleThresholdDays()       { return staleThresholdDays; }
    @DataBoundSetter public void setStaleThresholdDays(int v)  { this.staleThresholdDays = v; }
    public int getActiveThresholdDays()      { return activeThresholdDays; }
    @DataBoundSetter public void setActiveThresholdDays(int v) { this.activeThresholdDays = v; }
    public int getAuditRetentionDays()       { return auditRetentionDays; }
    @DataBoundSetter public void setAuditRetentionDays(int v) { this.auditRetentionDays = Math.max(0, v); }
    public boolean isCleanupEnabled()        { return cleanupEnabled; }
    @DataBoundSetter public void setCleanupEnabled(boolean v) { this.cleanupEnabled = v; }
    public boolean isCleanupDryRun()         { return cleanupDryRun; }
    @DataBoundSetter public void setCleanupDryRun(boolean v)  { this.cleanupDryRun = v; }
    public String getCleanupCron()           { return cleanupCron; }
    @DataBoundSetter public void setCleanupCron(String v)     { this.cleanupCron = v; }
    public int getCleanupMaxDeletions()      { return cleanupMaxDeletions; }
    @DataBoundSetter public void setCleanupMaxDeletions(int v) { this.cleanupMaxDeletions = v; }
    public String getCleanupNotifyEmail()    { return cleanupNotifyEmail; }
    @DataBoundSetter public void setCleanupNotifyEmail(String v) { this.cleanupNotifyEmail = v; }

    // SMTP
    public String  getSmtpHost()        { return smtpHost; }
    @DataBoundSetter public void setSmtpHost(String v)     { this.smtpHost = v; }
    public int     getSmtpPort()        { return smtpPort; }
    @DataBoundSetter public void setSmtpPort(int v)        { this.smtpPort = v; }
    public String  getSmtpUsername()    { return smtpUsername; }
    @DataBoundSetter public void setSmtpUsername(String v) { this.smtpUsername = v; }
    public Secret  getSmtpPassword()    { return smtpPassword; }
    // Secret-typed setter so JCasC exports the password ENCRYPTED and can inject it via ${ENV}.
    @DataBoundSetter
    public void    setSmtpPassword(Secret v) { this.smtpPassword = v; }
    public boolean isSmtpTls()          { return smtpTls; }
    @DataBoundSetter public void setSmtpTls(boolean v)        { this.smtpTls = v; }
    public String  getSmtpFromAddress() { return smtpFromAddress; }
    @DataBoundSetter public void setSmtpFromAddress(String v) { this.smtpFromAddress = v; }
    public String  getSmtpFromName()    { return smtpFromName; }
    @DataBoundSetter public void setSmtpFromName(String v)    { this.smtpFromName = v; }
    public String  getLoginHeading()   { return loginHeading  != null ? loginHeading.trim()  : ""; }
    public String  getLoginTabTitle()  { return loginTabTitle != null ? loginTabTitle.trim() : ""; }
    public void    setLoginHeading(String v)  { this.loginHeading  = v != null ? v.trim() : ""; }
    public void    setLoginTabTitle(String v) { this.loginTabTitle = v != null ? v.trim() : ""; }
    public String  getLoginAnnouncementText() { return loginAnnouncementText != null ? loginAnnouncementText.trim() : ""; }
    public String  getLoginFooterText()       { return loginFooterText != null ? loginFooterText.trim() : ""; }
    public void    setLoginAnnouncementText(String v) { this.loginAnnouncementText = v != null ? v.trim() : ""; }
    public void    setLoginFooterText(String v)       { this.loginFooterText       = v != null ? v.trim() : ""; }
    public String  getLoginBackground() {
        String v = loginBackground != null ? loginBackground.trim() : "";
        return v.isEmpty() ? "omniauth" : v;
    }
    public void    setLoginBackground(String v) { this.loginBackground = (v != null && !v.trim().isEmpty()) ? v.trim() : "omniauth"; }
    public String  getLoginLogoPosition() {
        String v = loginLogoPosition != null ? loginLogoPosition.trim() : "";
        return (v.equals("a") || v.equals("e") || v.equals("f")) ? v : "a";
    }
    public void    setLoginLogoPosition(String v) {
        String t = v != null ? v.trim() : "";
        this.loginLogoPosition = (t.equals("e") || t.equals("f")) ? t : "a";
    }
    public String  getLoginLogoSize() {
        String v = loginLogoSize != null ? loginLogoSize.trim() : "";
        return (v.equals("20") || v.equals("30") || v.equals("40")) ? v : "40";
    }
    public void    setLoginLogoSize(String v) {
        String t = v != null ? v.trim() : "";
        this.loginLogoSize = (t.equals("20") || t.equals("30")) ? t : "40";
    }
    /** Whitelist of concrete butler styles. Add the id here when inlining a new butler. */
    private static final java.util.Set<String> BUTLERS =
        java.util.Set.of(
                "default", "austin", "jenkinsx", "general", "nerd", "ninja", "santaclaus", "superhero",
                "raleigh", "sandiego", "cowboy");
    /** Rotation modes: not concrete butlers, resolved per render by getResolvedLoginButler(). */
    private static final java.util.Set<String> ROTATION_MODES = java.util.Set.of("daily", "random");
    /**
     * Ordered pool the rotation modes cycle through — the "cool" styled butlers (native excluded).
     * Santa is gated to December (see buildRotationPool); changing this order changes the daily cadence.
     */
    private static final java.util.List<String> ROTATION_POOL = java.util.List.of(
            "austin", "jenkinsx", "general", "nerd", "ninja", "superhero",
            "raleigh", "sandiego", "cowboy", "santaclaus");

    /** Raw stored selection — a concrete butler id OR a rotation mode ("daily"/"random"). For settings + persistence. */
    public String  getLoginButler() {
        String v = loginButler != null ? loginButler.trim() : "";
        return (BUTLERS.contains(v) || ROTATION_MODES.contains(v)) ? v : "default";
    }
    public void    setLoginButler(String v) {
        String t = v != null ? v.trim() : "";
        this.loginButler = (BUTLERS.contains(t) || ROTATION_MODES.contains(t)) ? t : "default";
    }

    /** The concrete butler to actually render: rotation modes resolve here, everything else passes through. */
    public String  getResolvedLoginButler() {
        String raw = getLoginButler();
        if (!ROTATION_MODES.contains(raw)) {
            return raw; // explicit fixed pick (incl. native) is always respected — festive lock never overrides it
        }
        // Festive lock: Dec 21 → Jan 1, rotation modes always show Santa
        if (isFestiveWindow(java.time.LocalDate.now())) {
            return "santaclaus";
        }
        java.util.List<String> pool = buildRotationPool();
        if (pool.isEmpty()) {
            return "default";
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        int idx = "daily".equals(raw)
                ? (today.getDayOfYear() % pool.size())
                : java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size());
        return pool.get(idx);
    }

    /** The Christmas lock window: Dec 21 through Jan 1 (inclusive). */
    private static boolean isFestiveWindow(java.time.LocalDate d) {
        int month = d.getMonthValue();
        int day = d.getDayOfMonth();
        return (month == 12 && day >= 21) || (month == 1 && day == 1);
    }

    /** Rotation pool for the current date — Santa only joins in December. */
    private static java.util.List<String> buildRotationPool() {
        boolean december = java.time.LocalDate.now().getMonthValue() == 12;
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (String b : ROTATION_POOL) {
            if ("santaclaus".equals(b) && !december) {
                continue;
            }
            pool.add(b);
        }
        return pool;
    }

    public String  getNotificationLogoUrl() {
        return notificationLogoUrl != null ? notificationLogoUrl.trim() : "";
    }
    @DataBoundSetter public void setNotificationLogoUrl(String v)    { this.notificationLogoUrl = v; }
    public String  getNotificationFooterNote() {
        return notificationFooterNote != null ? notificationFooterNote.trim() : "";
    }
    @DataBoundSetter public void setNotificationFooterNote(String v) { this.notificationFooterNote = v; }
    public String  getSmtpReplyTo()     { return smtpReplyTo; }
    @DataBoundSetter public void setSmtpReplyTo(String v)  { this.smtpReplyTo = v; }
    public String  getNotifyEmails()    { return notifyEmails; }
    @DataBoundSetter public void setNotifyEmails(String v) { this.notifyEmails = v; }

    public boolean isSmtpConfigured() {
        return smtpHost != null && !smtpHost.isEmpty()
            && smtpFromAddress != null && !smtpFromAddress.isEmpty()
            && smtpUsername != null && !smtpUsername.isEmpty()
            && smtpPassword != null;
    }

    // Notifications master + channels
    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    @DataBoundSetter public void setNotificationsEnabled(boolean v) { this.notificationsEnabled = v; }
    public boolean isSmtpEnabled()          { return smtpEnabled; }
    @DataBoundSetter public void setSmtpEnabled(boolean v)  { this.smtpEnabled = v; }
    public boolean isSlackEnabled()         { return slackEnabled; }
    @DataBoundSetter public void setSlackEnabled(boolean v) { this.slackEnabled = v; }
    public boolean isTeamsEnabled()         { return teamsEnabled; }
    @DataBoundSetter public void setTeamsEnabled(boolean v) { this.teamsEnabled = v; }

    // Webhook URLs are bearer secrets. The canonical getters return Secret so JCasC exports them
    // ENCRYPTED (never plaintext) and so a jenkins.yaml may inject them via ${ENV}; the *Plain()
    // accessors give the raw URL to internal callers (SlackHelper/TeamsHelper) and the config form.
    public Secret  getSlackWebhookUrl()      { return slackWebhookUrl; }
    @DataBoundSetter
    public void    setSlackWebhookUrl(Secret v) { this.slackWebhookUrl = v; }
    public String  getSlackWebhookUrlPlain() { return slackWebhookUrl != null ? slackWebhookUrl.getPlainText() : ""; }

    public Secret  getTeamsWebhookUrl()      { return teamsWebhookUrl; }
    @DataBoundSetter
    public void    setTeamsWebhookUrl(Secret v) { this.teamsWebhookUrl = v; }
    public String  getTeamsWebhookUrlPlain() { return teamsWebhookUrl != null ? teamsWebhookUrl.getPlainText() : ""; }

    // JIT history retention
    public int getJitHistoryRetentionDays() { return jitHistoryRetentionDays > 0 ? jitHistoryRetentionDays : 90; }
    public void setJitHistoryRetentionDays(int v) { this.jitHistoryRetentionDays = v > 0 ? v : 90; }

    // Brute force
    public int getBruteForceThreshold() { return bruteForceThreshold; }
    @DataBoundSetter public void setBruteForceThreshold(int v) { this.bruteForceThreshold = v; }
    public boolean isBruteForceLockoutEnabled() { return bruteForceLockoutEnabled; }
    public void setBruteForceLockoutEnabled(boolean v) { this.bruteForceLockoutEnabled = v; }
    public int getBruteForceLockoutMinutes() { return bruteForceLockoutMinutes > 0 ? bruteForceLockoutMinutes : 15; }
    public void setBruteForceLockoutMinutes(int v) { this.bruteForceLockoutMinutes = v > 0 ? v : 15; }
    public boolean isSosEnabled() { return sosEnabled; }
    @DataBoundSetter public void setSosEnabled(boolean v) { this.sosEnabled = v; }

    // Stale warning
    public boolean isStaleWarningEnabled()    { return staleWarningEnabled; }
    @DataBoundSetter public void setStaleWarningEnabled(boolean v)  { this.staleWarningEnabled = v; }
    public String  getStaleWarningCron()      { return staleWarningCron; }
    @DataBoundSetter public void setStaleWarningCron(String v)      { this.staleWarningCron = v; }
    public int     getStaleWarningWindowDays(){ return staleWarningWindowDays; }
    @DataBoundSetter public void setStaleWarningWindowDays(int v)   { this.staleWarningWindowDays = v; }

    // Access review
    public boolean isAccessReviewEnabled()       { return accessReviewEnabled; }
    @DataBoundSetter public void setAccessReviewEnabled(boolean v)     { this.accessReviewEnabled = v; }
    public int     getAccessReviewThresholdDays() { return accessReviewThresholdDays; }
    @DataBoundSetter public void setAccessReviewThresholdDays(int v)   { this.accessReviewThresholdDays = v; }

    // ── Provisioning portal (self-service access requests) ────────────────────
    // Persisted here; the portal's admin page sets these via its own save handler,
    // and configure() leaves them untouched because it only reads keys it knows.
    private boolean provisioningEnabled            = false;
    private boolean provisioningAllowEntra         = true;
    private boolean provisioningAllowNative        = false;
    private String  provisioningAllowedDomains     = "";   // comma/space separated; empty = any
    private int     provisioningDefaultDurationDays = 0;   // 0 = permanent
    private String  provisioningExpiryAction       = "DISABLE"; // DISABLE | DELETE
    private int     provisioningMaxPendingPerUser  = 1;    // Entra: pending cap per requester
    private int     provisioningBreakerMax         = 100;  // native: max requests in the window
    private int     provisioningBreakerWindowMin   = 10;   // native: rolling window (minutes)
    private int     provisioningBreakerCooldownMin = 30;   // native: pause duration once tripped
    private int     provisioningExpiryReminderDays = 7;    // email the account this many days before expiry (0 = off)
    private int     activationLinkTtlHours         = 72;   // native approval: one-time activation link lifetime

    public boolean isProvisioningEnabled()            { return provisioningEnabled; }
    public void    setProvisioningEnabled(boolean v)  { this.provisioningEnabled = v; }

    public boolean isProvisioningAllowEntra()           { return provisioningAllowEntra; }
    public void    setProvisioningAllowEntra(boolean v) { this.provisioningAllowEntra = v; }

    public boolean isProvisioningAllowNative()           { return provisioningAllowNative; }
    public void    setProvisioningAllowNative(boolean v) { this.provisioningAllowNative = v; }

    public String  getProvisioningAllowedDomains()          { return provisioningAllowedDomains != null ? provisioningAllowedDomains : ""; }
    public void    setProvisioningAllowedDomains(String v)  { this.provisioningAllowedDomains = v != null ? v.trim() : ""; }

    public int     getProvisioningDefaultDurationDays()         { return Math.max(0, provisioningDefaultDurationDays); }
    public void    setProvisioningDefaultDurationDays(int v)    { this.provisioningDefaultDurationDays = Math.max(0, v); }

    public String  getProvisioningExpiryAction()         { return "DELETE".equals(provisioningExpiryAction) ? "DELETE" : "DISABLE"; }
    public void    setProvisioningExpiryAction(String v) { this.provisioningExpiryAction = "DELETE".equalsIgnoreCase(v) ? "DELETE" : "DISABLE"; }

    public int     getProvisioningExpiryReminderDays()      { return Math.max(0, provisioningExpiryReminderDays); }
    public void    setProvisioningExpiryReminderDays(int v) { this.provisioningExpiryReminderDays = Math.max(0, v); }

    public int     getActivationLinkTtlHours()      { return activationLinkTtlHours > 0 ? activationLinkTtlHours : 72; }
    public void    setActivationLinkTtlHours(int v) { this.activationLinkTtlHours = v > 0 ? v : 72; }

    public int     getProvisioningMaxPendingPerUser()        { return provisioningMaxPendingPerUser > 0 ? provisioningMaxPendingPerUser : 1; }
    public void    setProvisioningMaxPendingPerUser(int v)   { this.provisioningMaxPendingPerUser = v > 0 ? v : 1; }

    public int     getProvisioningBreakerMax()          { return provisioningBreakerMax > 0 ? provisioningBreakerMax : 100; }
    public void    setProvisioningBreakerMax(int v)     { this.provisioningBreakerMax = v > 0 ? v : 100; }

    public int     getProvisioningBreakerWindowMin()        { return provisioningBreakerWindowMin > 0 ? provisioningBreakerWindowMin : 10; }
    public void    setProvisioningBreakerWindowMin(int v)   { this.provisioningBreakerWindowMin = v > 0 ? v : 10; }

    public int     getProvisioningBreakerCooldownMin()        { return provisioningBreakerCooldownMin > 0 ? provisioningBreakerCooldownMin : 30; }
    public void    setProvisioningBreakerCooldownMin(int v)   { this.provisioningBreakerCooldownMin = v > 0 ? v : 30; }

    /** True when the portal is on and at least one request type is allowed. */
    public boolean isProvisioningUsable() {
        return provisioningEnabled && (provisioningAllowEntra || provisioningAllowNative);
    }

    // Per-channel event subscriptions
    public List<String> getSmtpEvents()  { return Collections.unmodifiableList(smtpEvents); }
    @DataBoundSetter public void setSmtpEvents(List<String> v)  { this.smtpEvents  = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
    public List<String> getSlackEvents() { return Collections.unmodifiableList(slackEvents); }
    @DataBoundSetter public void setSlackEvents(List<String> v) { this.slackEvents = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
    public List<String> getTeamsEvents() { return Collections.unmodifiableList(teamsEvents); }
    @DataBoundSetter public void setTeamsEvents(List<String> v) { this.teamsEvents = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

    public boolean isSmtpEvent(String event)  { return smtpEvents.contains(event); }
    public boolean isSlackEvent(String event) { return slackEvents.contains(event); }
    public boolean isTeamsEvent(String event) { return teamsEvents.contains(event); }

    public String getSmtpEventsJoined()  { return String.join(",", smtpEvents); }
    public String getSlackEventsJoined() { return String.join(",", slackEvents); }
    public String getTeamsEventsJoined() { return String.join(",", teamsEvents); }

    public List<CleanupRunRecord> getCleanupHistory() {
        return Collections.unmodifiableList(cleanupHistory);
    }

    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>(User.getAll());
        users.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));
        return users;
    }

    // ── History mutation ──────────────────────────────────────────────────────

    public synchronized void addCleanupRecord(CleanupRunRecord record) {
        cleanupHistory.add(0, record); // newest first
        if (cleanupHistory.size() > MAX_HISTORY) {
            cleanupHistory = new ArrayList<>(cleanupHistory.subList(0, MAX_HISTORY));
        }
        save();
    }

    // ── configure() ───────────────────────────────────────────────────────────

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Protected users
        protectedUsers = new ArrayList<>();
        Object raw = json.opt("protectedUsers");
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) protectedUsers.add(o.toString());
        } else if (raw instanceof String && !((String) raw).isEmpty()) {
            protectedUsers.add(raw.toString());
        }

        staleThresholdDays  = jsonInt(json, "staleThresholdDays",  90);
        activeThresholdDays = jsonInt(json, "activeThresholdDays", 30);
        auditRetentionDays  = Math.max(0, jsonInt(json, "auditRetentionDays", 365));
        cleanupEnabled      = json.optBoolean("cleanupEnabled",  false);
        cleanupDryRun       = !cleanupEnabled || json.optBoolean("cleanupDryRun", true);
        cleanupCron         = jsonStr(json, "cleanupCron",         "0 2 * * 0");
        cleanupMaxDeletions = jsonInt(json, "cleanupMaxDeletions", 50);

        // SMTP — split host:port if user pasted a combined value
        String rawHost  = jsonStr(json, "smtpHost", "");
        if (rawHost.contains(":")) {
            String[] hp = rawHost.split(":", 2);
            smtpHost = hp[0].trim();
            try { smtpPort = Integer.parseInt(hp[1].trim()); } catch (NumberFormatException ignored) { smtpPort = 587; }
        } else {
            smtpHost = rawHost;
            smtpPort = jsonInt(json, "smtpPort", 587);
        }
        smtpUsername    = jsonStr(json, "smtpUsername",    "");
        String rawPass  = jsonStr(json, "smtpPassword",    "");
        if (!rawPass.isEmpty()) smtpPassword = Secret.fromString(rawPass);
        smtpTls         = json.optBoolean("smtpTls", true);
        smtpFromAddress = jsonStr(json, "smtpFromAddress", "");
        smtpFromName    = jsonStr(json, "smtpFromName",    "Jenkins OmniAuth");
        smtpReplyTo     = jsonStr(json, "smtpReplyTo",     "");
        notifyEmails          = jsonStr(json, "notifyEmails",          "");
        loginHeading          = jsonStr(json, "loginHeading",          "");
        loginTabTitle         = jsonStr(json, "loginTabTitle",         "");
        loginAnnouncementText = jsonStr(json, "loginAnnouncementText", "");
        loginFooterText       = jsonStr(json, "loginFooterText",       "");
        loginBackground       = jsonStr(json, "loginBackground",       "omniauth");
        loginLogoPosition     = jsonStr(json, "loginLogoPosition",     "a");
        loginLogoSize         = jsonStr(json, "loginLogoSize",         "40");
        loginButler           = jsonStr(json, "loginButler",           "default");
        notificationLogoUrl   = jsonStr(json, "notificationLogoUrl",   "");
        notificationFooterNote = jsonStr(json, "notificationFooterNote", "");

        // Notifications master + channels
        notificationsEnabled = json.optBoolean("notificationsEnabled", false);
        smtpEnabled          = json.optBoolean("smtpEnabled",          false);
        slackEnabled         = json.optBoolean("slackEnabled",         false);
        slackWebhookUrl      = Secret.fromString(jsonStr(json, "slackWebhookUrl", ""));
        teamsEnabled         = json.optBoolean("teamsEnabled",         false);
        teamsWebhookUrl      = Secret.fromString(jsonStr(json, "teamsWebhookUrl", ""));

        // Brute force
        bruteForceThreshold = jsonInt(json, "bruteForceThreshold", 5);
        bruteForceLockoutEnabled = json.optBoolean("bruteForceLockoutEnabled", true);
        bruteForceLockoutMinutes = jsonInt(json, "bruteForceLockoutMinutes", 15);
        sosEnabled = json.optBoolean("sosEnabled", true);

        // Stale warning
        staleWarningEnabled    = json.optBoolean("staleWarningEnabled", false);
        staleWarningCron       = jsonStr(json, "staleWarningCron",       "0 9 * * 1");
        staleWarningWindowDays = jsonInt(json, "staleWarningWindowDays", 14);

        // Access review
        accessReviewEnabled       = json.optBoolean("accessReviewEnabled", false);
        accessReviewThresholdDays = jsonInt(json, "accessReviewThresholdDays", 90);

        // Per-channel event subscriptions
        smtpEvents  = jsonStringList(json, "smtpEvents");
        slackEvents = jsonStringList(json, "slackEvents");
        teamsEvents = jsonStringList(json, "teamsEvents");

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
}
