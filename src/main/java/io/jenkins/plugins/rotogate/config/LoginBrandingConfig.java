package io.jenkins.plugins.rotogate.config;

import org.kohsuke.stapler.StaplerRequest2;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Login-page branding: the headings and messages shown on the custom login page, the background
 * design, company-logo placement/size and the centre "butler" figure (including the daily/random
 * rotation and the festive lock). Grouped on its own because it is purely presentational and carries
 * the only non-trivial behaviour (butler resolution) in the former global config.
 *
 * <p>Owned by {@link io.jenkins.plugins.rotogate.RotogateGlobalConfig}, which keeps delegating
 * accessors onto it. Setters store the raw value and getters normalise, matching the previous
 * behaviour so existing callers and tests round-trip unchanged.
 */
public class LoginBrandingConfig {

    private String loginHeading          = "";
    private String loginTabTitle         = "";
    private String loginAnnouncementText = "";
    private String loginFooterText       = "";
    // Scheduled (recurring) announcements: a master toggle plus the rules themselves, stored as a
    // JSON array string so they round-trip through XStream / JCasC without a Describable.
    private boolean scheduledAnnouncementsEnabled = false;
    private String  scheduledAnnouncementsJson    = "[]";
    // Which background the login panel shows. Known values: "rotogate" (animated reactor,
    // default) and "starburst" (Jenkins' native starburst). New designs can be added later.
    private String loginBackground       = "rotogate";
    // Where the company logo sits on the login form. Known values: "a" (top of the
    // form column, left-aligned - default), "e" (pinned top-right corner of the panel),
    // "f" (logo centred above the form). Only takes effect when a logo is uploaded.
    private String loginLogoPosition     = "a";
    // Company-logo height on the login form, in px. Known values: "20", "30", "40" (default).
    private String loginLogoSize         = "40";
    // Centre figure on the animated login panel (concrete butler id or rotation mode).
    private String loginButler           = "default";

    /** Whitelist of concrete butler styles. Add the id here when inlining a new butler. */
    private static final Set<String> BUTLERS = Set.of(
            "default", "austin", "jenkinsx", "general", "nerd", "ninja", "santaclaus", "superhero",
            "raleigh", "sandiego", "cowboy");
    /** Rotation modes: not concrete butlers, resolved per render by getResolvedLoginButler(). */
    private static final Set<String> ROTATION_MODES = Set.of("daily", "random");
    /**
     * Ordered pool the rotation modes cycle through - the "cool" styled butlers (native excluded).
     * Santa is gated to December (see buildRotationPool); changing this order changes the daily cadence.
     */
    private static final List<String> ROTATION_POOL = List.of(
            "austin", "jenkinsx", "general", "nerd", "ninja", "superhero",
            "raleigh", "sandiego", "cowboy", "santaclaus");

    public LoginBrandingConfig() { }

    public String getLoginHeading()   { return loginHeading  != null ? loginHeading.trim()  : ""; }
    public void   setLoginHeading(String v)  { this.loginHeading  = v != null ? v.trim() : ""; }
    public String getLoginTabTitle()  { return loginTabTitle != null ? loginTabTitle.trim() : ""; }
    public void   setLoginTabTitle(String v) { this.loginTabTitle = v != null ? v.trim() : ""; }
    public String getLoginAnnouncementText() { return loginAnnouncementText != null ? loginAnnouncementText.trim() : ""; }
    public void   setLoginAnnouncementText(String v) { this.loginAnnouncementText = v != null ? v.trim() : ""; }
    public String getLoginFooterText()       { return loginFooterText != null ? loginFooterText.trim() : ""; }
    public void   setLoginFooterText(String v)       { this.loginFooterText       = v != null ? v.trim() : ""; }

    // Scheduled announcements
    public boolean isScheduledAnnouncementsEnabled() { return scheduledAnnouncementsEnabled; }
    public void    setScheduledAnnouncementsEnabled(boolean v) { this.scheduledAnnouncementsEnabled = v; }
    /** Raw JSON array string of rules (normalised). */
    public String  getScheduledAnnouncementsJson() { return scheduledAnnouncementsJson != null ? scheduledAnnouncementsJson : "[]"; }
    public void    setScheduledAnnouncementsJson(String v) { this.scheduledAnnouncementsJson = ScheduledAnnouncement.normalise(v); }
    /** Parsed rules. */
    public List<ScheduledAnnouncement> getScheduledAnnouncements() {
        return ScheduledAnnouncement.parseList(getScheduledAnnouncementsJson());
    }

    public String getLoginBackground() {
        String v = loginBackground != null ? loginBackground.trim() : "";
        return v.isEmpty() ? "rotogate" : v;
    }
    public void setLoginBackground(String v) { this.loginBackground = (v != null && !v.trim().isEmpty()) ? v.trim() : "rotogate"; }

    public String getLoginLogoPosition() {
        String v = loginLogoPosition != null ? loginLogoPosition.trim() : "";
        return (v.equals("a") || v.equals("e") || v.equals("f")) ? v : "a";
    }
    public void setLoginLogoPosition(String v) {
        String t = v != null ? v.trim() : "";
        this.loginLogoPosition = (t.equals("e") || t.equals("f")) ? t : "a";
    }

    public String getLoginLogoSize() {
        String v = loginLogoSize != null ? loginLogoSize.trim() : "";
        return (v.equals("20") || v.equals("30") || v.equals("40")) ? v : "40";
    }
    public void setLoginLogoSize(String v) {
        String t = v != null ? v.trim() : "";
        this.loginLogoSize = (t.equals("20") || t.equals("30")) ? t : "40";
    }

    /** Raw stored selection - a concrete butler id OR a rotation mode ("daily"/"random"). */
    public String getLoginButler() {
        String v = loginButler != null ? loginButler.trim() : "";
        return (BUTLERS.contains(v) || ROTATION_MODES.contains(v)) ? v : "default";
    }
    public void setLoginButler(String v) {
        String t = v != null ? v.trim() : "";
        this.loginButler = (BUTLERS.contains(t) || ROTATION_MODES.contains(t)) ? t : "default";
    }

    /** The concrete butler to actually render: rotation modes resolve here, everything else passes through. */
    public String getResolvedLoginButler() {
        String raw = getLoginButler();
        if (!ROTATION_MODES.contains(raw)) {
            return raw; // explicit fixed pick (incl. native) is always respected - festive lock never overrides it
        }
        // Festive lock: Dec 21 → Jan 1, rotation modes always show Santa
        if (isFestiveWindow(LocalDate.now())) {
            return "santaclaus";
        }
        List<String> pool = buildRotationPool();
        if (pool.isEmpty()) {
            return "default";
        }
        LocalDate today = LocalDate.now();
        int idx = "daily".equals(raw)
                ? (today.getDayOfYear() % pool.size())
                : ThreadLocalRandom.current().nextInt(pool.size());
        return pool.get(idx);
    }

    /** The Christmas lock window: Dec 21 through Jan 1 (inclusive). */
    private static boolean isFestiveWindow(LocalDate d) {
        int month = d.getMonthValue();
        int day = d.getDayOfMonth();
        return (month == 12 && day >= 21) || (month == 1 && day == 1);
    }

    /** Rotation pool for the current date - Santa only joins in December. */
    private static List<String> buildRotationPool() {
        boolean december = LocalDate.now().getMonthValue() == 12;
        List<String> pool = new ArrayList<>();
        for (String b : ROTATION_POOL) {
            if ("santaclaus".equals(b) && !december) {
                continue;
            }
            pool.add(b);
        }
        return pool;
    }

    /** Apply the login-branding fields submitted by the Settings page (all partial). */
    public void updateFrom(StaplerRequest2 req) {
        if (req.getParameter("loginHeading")          != null) setLoginHeading(trim(req.getParameter("loginHeading")));
        if (req.getParameter("loginTabTitle")         != null) setLoginTabTitle(trim(req.getParameter("loginTabTitle")));
        if (req.getParameter("loginAnnouncementText") != null) setLoginAnnouncementText(trim(req.getParameter("loginAnnouncementText")));
        if (req.getParameter("loginFooterText")       != null) setLoginFooterText(trim(req.getParameter("loginFooterText")));
        if (req.getParameter("loginBackground")       != null) setLoginBackground(trim(req.getParameter("loginBackground")));
        if (req.getParameter("loginLogoPosition")     != null) setLoginLogoPosition(trim(req.getParameter("loginLogoPosition")));
        if (req.getParameter("loginLogoSize")         != null) setLoginLogoSize(trim(req.getParameter("loginLogoSize")));
        if (req.getParameter("loginButler")           != null) setLoginButler(trim(req.getParameter("loginButler")));
        // Scheduled announcements: checkbox presence for the toggle, JSON blob for the rules.
        scheduledAnnouncementsEnabled = req.getParameter("scheduledAnnouncementsEnabled") != null;
        if (req.getParameter("scheduledAnnouncementsJson") != null)
            setScheduledAnnouncementsJson(req.getParameter("scheduledAnnouncementsJson"));
    }

    private static String trim(String v) { return v != null ? v.trim() : ""; }
}
