package io.jenkins.plugins.rotogate.config;

import hudson.util.Secret;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for the Notifications area: the master switch, the SMTP / Slack / Microsoft Teams
 * channels, their per-channel event subscriptions and the shared notification branding.
 *
 * <p>This is a plain holder owned by {@link io.jenkins.plugins.rotogate.RotogateGlobalConfig}. The
 * global config keeps thin delegating accessors onto it, so JCasC, the backup reflection and every
 * jelly/helper caller keep using the same flat property names while the fields and their parsing
 * live here, in one cohesive class, instead of being hand-assembled into JSON inside a save handler.
 */
public class NotificationConfig {

    /** Canonical catalogue of notifiable events. Kept here because it describes this area. */
    private static final String[] ALL_EVENTS = {
        "cleanup", "userDeleted", "configChanged", "protectedListChanged",
        "graphApiFailure", "adminGranted", "bruteForce", "staleWarning", "accessReview",
        // JIT (were dispatched but missing from the catalog, so they defaulted off - now included)
        "jitRequest", "jitApproved", "jitDenied",
        // Provisioning + account lifecycle (admin-facing)
        "provisionRequest", "accountExpired", "accountLocked"
    };

    /** Immutable view of the canonical event catalogue, safe to expose to callers. */
    public static List<String> allEvents() {
        return List.of(ALL_EVENTS);
    }

    // ── Master + channels ─────────────────────────────────────────────────────
    private boolean notificationsEnabled = false;
    private boolean smtpEnabled          = false;
    private boolean slackEnabled         = false;
    private Secret  slackWebhookUrl      = null;   // incoming-webhook URL = bearer secret -> encrypted at rest
    private boolean teamsEnabled         = false;
    private Secret  teamsWebhookUrl      = null;   // incoming-webhook URL = bearer secret -> encrypted at rest

    // ── SMTP configuration ────────────────────────────────────────────────────
    private String  smtpHost        = "";
    private int     smtpPort        = 465;
    private String  smtpUsername    = "";
    private Secret  smtpPassword    = null;
    private boolean smtpTls         = true;
    private String  smtpFromAddress = "";
    private String  smtpFromName    = "Jenkins Rotogate";
    private String  smtpReplyTo     = "";
    private String  notifyEmails    = ""; // comma-separated recipients

    // ── Notification branding ─────────────────────────────────────────────────
    private String notificationLogoUrl   = "";
    private String notificationFooterNote = "";

    // ── Per-channel event subscriptions ──────────────────────────────────────
    private List<String> smtpEvents  = new ArrayList<>(Arrays.asList(ALL_EVENTS));
    private List<String> slackEvents = new ArrayList<>();
    private List<String> teamsEvents = new ArrayList<>();

    public NotificationConfig() { }

    // ── Master + channels ─────────────────────────────────────────────────────
    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean v) { this.notificationsEnabled = v; }
    public boolean isSmtpEnabled()  { return smtpEnabled; }
    public void setSmtpEnabled(boolean v)  { this.smtpEnabled = v; }
    public boolean isSlackEnabled() { return slackEnabled; }
    public void setSlackEnabled(boolean v) { this.slackEnabled = v; }
    public boolean isTeamsEnabled() { return teamsEnabled; }
    public void setTeamsEnabled(boolean v) { this.teamsEnabled = v; }

    // Webhook URLs are bearer secrets: the Secret-typed accessors let JCasC export them encrypted
    // and inject them via ${ENV}; the *Plain() variants give the raw URL to the channel helpers.
    public Secret  getSlackWebhookUrl() { return slackWebhookUrl; }
    public void    setSlackWebhookUrl(Secret v) { this.slackWebhookUrl = v; }
    public String  getSlackWebhookUrlPlain() { return slackWebhookUrl != null ? slackWebhookUrl.getPlainText() : ""; }
    public Secret  getTeamsWebhookUrl() { return teamsWebhookUrl; }
    public void    setTeamsWebhookUrl(Secret v) { this.teamsWebhookUrl = v; }
    public String  getTeamsWebhookUrlPlain() { return teamsWebhookUrl != null ? teamsWebhookUrl.getPlainText() : ""; }

    // ── SMTP ──────────────────────────────────────────────────────────────────
    public String  getSmtpHost()        { return smtpHost; }
    public void    setSmtpHost(String v)     { this.smtpHost = v; }
    public int     getSmtpPort()        { return smtpPort; }
    public void    setSmtpPort(int v)        { this.smtpPort = v; }
    public String  getSmtpUsername()    { return smtpUsername; }
    public void    setSmtpUsername(String v) { this.smtpUsername = v; }
    public Secret  getSmtpPassword()    { return smtpPassword; }
    public void    setSmtpPassword(Secret v) { this.smtpPassword = v; }
    public boolean isSmtpTls()          { return smtpTls; }
    public void    setSmtpTls(boolean v)     { this.smtpTls = v; }
    public String  getSmtpFromAddress() { return smtpFromAddress; }
    public void    setSmtpFromAddress(String v) { this.smtpFromAddress = v; }
    public String  getSmtpFromName()    { return smtpFromName; }
    public void    setSmtpFromName(String v) { this.smtpFromName = v; }
    public String  getSmtpReplyTo()     { return smtpReplyTo; }
    public void    setSmtpReplyTo(String v)  { this.smtpReplyTo = v; }
    public String  getNotifyEmails()    { return notifyEmails; }
    public void    setNotifyEmails(String v) { this.notifyEmails = v; }

    public boolean isSmtpConfigured() {
        return smtpHost != null && !smtpHost.isEmpty()
            && smtpFromAddress != null && !smtpFromAddress.isEmpty()
            && smtpUsername != null && !smtpUsername.isEmpty()
            && smtpPassword != null;
    }

    // ── Branding ──────────────────────────────────────────────────────────────
    public String  getNotificationLogoUrl() {
        return notificationLogoUrl != null ? notificationLogoUrl.trim() : "";
    }
    public void    setNotificationLogoUrl(String v) { this.notificationLogoUrl = v; }
    public String  getNotificationFooterNote() {
        return notificationFooterNote != null ? notificationFooterNote.trim() : "";
    }
    public void    setNotificationFooterNote(String v) { this.notificationFooterNote = v; }

    // ── Event subscriptions ───────────────────────────────────────────────────
    public List<String> getSmtpEvents()  { return Collections.unmodifiableList(smtpEvents); }
    public void setSmtpEvents(List<String> v)  { this.smtpEvents  = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
    public List<String> getSlackEvents() { return Collections.unmodifiableList(slackEvents); }
    public void setSlackEvents(List<String> v) { this.slackEvents = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
    public List<String> getTeamsEvents() { return Collections.unmodifiableList(teamsEvents); }
    public void setTeamsEvents(List<String> v) { this.teamsEvents = v != null ? new ArrayList<>(v) : new ArrayList<>(); }

    public boolean isSmtpEvent(String event)  { return smtpEvents.contains(event); }
    public boolean isSlackEvent(String event) { return slackEvents.contains(event); }
    public boolean isTeamsEvent(String event) { return teamsEvents.contains(event); }

    public String getSmtpEventsJoined()  { return String.join(",", smtpEvents); }
    public String getSlackEventsJoined() { return String.join(",", slackEvents); }
    public String getTeamsEventsJoined() { return String.join(",", teamsEvents); }

    /**
     * Apply a submission from the Notifications page. The page always posts its checkboxes, so those
     * are read as presence; the text / secret / list fields are partial (an absent parameter leaves
     * the stored value untouched), matching the previous {@code configure()} semantics exactly.
     */
    public void updateFrom(StaplerRequest2 req) {
        notificationsEnabled = req.getParameter("notificationsEnabled") != null;
        smtpEnabled          = req.getParameter("smtpEnabled")          != null;
        slackEnabled         = req.getParameter("slackEnabled")         != null;
        teamsEnabled         = req.getParameter("teamsEnabled")         != null;
        smtpTls              = req.getParameter("smtpTls")              != null;

        if (req.getParameter("slackWebhookUrl") != null) {
            slackWebhookUrl = Secret.fromString(trimParam(req, "slackWebhookUrl"));
        }
        if (req.getParameter("teamsWebhookUrl") != null) {
            teamsWebhookUrl = Secret.fromString(trimParam(req, "teamsWebhookUrl"));
        }

        // SMTP host, splitting a pasted "host:port" value just as configure() did.
        if (req.getParameter("smtpHost") != null) {
            String rawHost = trimParam(req, "smtpHost");
            if (rawHost.contains(":")) {
                String[] hp = rawHost.split(":", 2);
                smtpHost = hp[0].trim();
                try { smtpPort = Integer.parseInt(hp[1].trim()); } catch (NumberFormatException ignored) { /* keep port */ }
            } else {
                smtpHost = rawHost;
                if (req.getParameter("smtpPort") != null) smtpPort = parseInt(trimParam(req, "smtpPort"), smtpPort);
            }
        } else if (req.getParameter("smtpPort") != null) {
            smtpPort = parseInt(trimParam(req, "smtpPort"), smtpPort);
        }

        if (req.getParameter("smtpUsername")    != null) smtpUsername    = trimParam(req, "smtpUsername");
        if (req.getParameter("smtpPassword")    != null) {
            String rawPass = trimParam(req, "smtpPassword");
            if (!rawPass.isEmpty()) smtpPassword = Secret.fromString(rawPass); // blank keeps the stored secret
        }
        if (req.getParameter("smtpFromAddress") != null) smtpFromAddress = trimParam(req, "smtpFromAddress");
        if (req.getParameter("smtpFromName")    != null) smtpFromName    = orDefault(trimParam(req, "smtpFromName"), "Jenkins Rotogate");
        if (req.getParameter("smtpReplyTo")     != null) smtpReplyTo     = trimParam(req, "smtpReplyTo");
        if (req.getParameter("notifyEmails")    != null) notifyEmails    = trimParam(req, "notifyEmails");
        if (req.getParameter("notificationLogoUrl")    != null) notificationLogoUrl    = trimParam(req, "notificationLogoUrl");
        if (req.getParameter("notificationFooterNote") != null) notificationFooterNote = trimParam(req, "notificationFooterNote");

        smtpEvents  = paramList(req, "smtpEvents");
        slackEvents = paramList(req, "slackEvents");
        teamsEvents = paramList(req, "teamsEvents");
    }

    private static String trimParam(StaplerRequest2 req, String name) {
        String v = req.getParameter(name);
        return v != null ? v.trim() : "";
    }

    private static String orDefault(String v, String def) { return v.isEmpty() ? def : v; }

    private static int parseInt(String v, int fallback) {
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static List<String> paramList(StaplerRequest2 req, String name) {
        List<String> out = new ArrayList<>();
        String[] vals = req.getParameterValues(name);
        if (vals != null) for (String v : vals) if (v != null) out.add(v);
        return out;
    }
}
