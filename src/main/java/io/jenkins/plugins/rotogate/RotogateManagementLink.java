package io.jenkins.plugins.rotogate;
import io.jenkins.plugins.rotogate.sso.EntraOAuthConfig;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ManagementLink;
import hudson.model.Run;
import hudson.model.User;
import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.AuthorizationType;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Adds "Rotogate - Entra User Management" under Manage Jenkins → Security.
 *
 * Sub-pages are served by Stapler's automatic view routing: a GET to
 * {@code /manage/rotogate-management/<name>} renders {@code <name>.jelly} with no routing method, and
 * each page's {@code <l:layout permission="${app.ADMINISTER}">} enforces access. Only pages that need
 * to run logic before rendering (e.g. {@code doCheckAccess}) keep a {@code do*} method.
 */
@Extension
public class RotogateManagementLink extends ManagementLink {

    private static final Logger LOGGER = Logger.getLogger(RotogateManagementLink.class.getName());
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();
    static final int DEFAULT_STALE_THRESHOLD_DAYS = 90;

    private static int staleThresholdDays() {
        RotogateGlobalConfig c = RotogateGlobalConfig.get();
        return c != null ? c.getStaleThresholdDays() : DEFAULT_STALE_THRESHOLD_DAYS;
    }

    private static int activeThresholdDays() {
        RotogateGlobalConfig c = RotogateGlobalConfig.get();
        return c != null ? c.getActiveThresholdDays() : 30;
    }

    // -------------------------------------------------------------------------
    // ManagementLink metadata
    // -------------------------------------------------------------------------

    @Override public String getIconFileName()    { return "/plugin/rotogate/images/icon.svg"; }
    @Override public String getDisplayName()     { return "Rotogate Management"; }
    @Override public String getDescription()     { return "Manage users, review access, monitor security, and clean up stale accounts."; }
    @Override public String getUrlName()         { return "rotogate-management"; }
    @Override public Permission getRequiredPermission() { return Jenkins.ADMINISTER; }
    @Override public Category getCategory()      { return Category.SECURITY; }

    // -------------------------------------------------------------------------
    // Sub-page routing
    // -------------------------------------------------------------------------

    public List<Map<String, String>> getAuditEvents() {
        RotogateAuditLog log = RotogateAuditLog.get();
        return log != null ? log.readRecent(200) : Collections.emptyList();
    }

    /** Audit-log storage usage (events, size, oldest event) for the Settings → Audit Log card. */
    public RotogateAuditLog.StorageInfo getAuditStorage() {
        RotogateAuditLog log = RotogateAuditLog.get();
        return log != null ? log.getStorageInfo() : new RotogateAuditLog.StorageInfo(0, 0, "", 0);
    }

    /**
     * True when the current admin signs in through SSO (Entra) and so has no local password to
     * re-enter. Sensitive-action confirmations fall back to a typed phrase for these users -
     * {@code manager2.authenticate} can never succeed for an account with no password hash.
     */
    public boolean isCurrentUserSso() {
        User u = User.current();
        return u != null && isEntraIdentity(u.getProperty(RotogateUserProperty.class));
    }

    @POST
    public void doPurgeAuditLog(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String currentUser = Jenkins.getAuthentication2().getName();

        if (isCurrentUserSso()) {
            // SSO admins have no local password; confirm with a typed phrase instead.
            String phrase = req.getParameter("confirmPhrase");
            if (phrase == null || !phrase.trim().equalsIgnoreCase("DELETE")) {
                rsp.setContentType("application/json");
                rsp.getWriter().write("{\"error\":\"wrongPhrase\"}");
                return;
            }
        } else {
            // Native accounts re-enter their password.
            String password = req.getParameter("confirmPassword");
            try {
                Jenkins.get().getSecurityRealm().getSecurityComponents().manager2
                        .authenticate(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(currentUser, password));
            } catch (Exception e) {
                LoginContextFilter.FRESH_LOGINS.remove("__failed__" + currentUser);
                LoginContextFilter.FAILED_CONTEXT.remove("__failed__" + currentUser);
                rsp.setContentType("application/json");
                rsp.getWriter().write("{\"error\":\"wrongPassword\"}");
                return;
            }
            // Suppress the login event that manager2.authenticate() fires - this is a password
            // verification, not a real login, so it should not appear in the audit log
            LoginContextFilter.FRESH_LOGINS.remove(currentUser);
        }

        RotogateAuditLog log = RotogateAuditLog.get();
        if (log != null) {
            int deleted = log.purgeAll();
            // Record the purge AFTER deletion so the entry survives in a fresh log file -
            // destruction of the audit trail must itself leave a trace.
            log.logAuditPurged(currentUser, deleted);
        }
        rsp.setContentType("application/json");
        rsp.getWriter().write("{\"ok\":true}");
    }

    // ── Backup & Restore (UI import/export) ──────────────────────────────────
    // Export/import Rotogate config (roles, assignments, groups, global settings) as a
    // JCasC-shaped YAML file. The security realm and all secrets are out of scope by design, so an
    // import can never change how you sign in. Import is made safe by: validate-before-apply, an
    // automatic pre-import snapshot (Revert), and a lockout guard that un-applies any import which
    // would strip the acting admin's access.

    private static final java.util.logging.Logger BR_LOG =
            java.util.logging.Logger.getLogger(RotogateManagementLink.class.getName());

    @POST
    public void doExportConfig(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String yaml = RotogateConfigBackup.export();
        String fname = "rotogate-backup-" + java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmm").withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now()) + ".yaml";
        rsp.setContentType("application/x-yaml; charset=utf-8");
        rsp.setHeader("Content-Disposition", "attachment; filename=\"" + fname + "\"");
        rsp.getWriter().write(yaml);
        RotogateAuditLog.get().logConfigExported(Jenkins.getAuthentication2().getName());
    }

    @POST
    public void doImportConfig(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        // Decode the uploaded form as UTF-8 so non-ASCII content survives; otherwise a default
        // Latin-1 decode can turn multi-byte chars into control chars the YAML parser rejects.
        req.setCharacterEncoding("UTF-8");
        String base = req.getContextPath() + "/manage/rotogate-management/backup";

        RotogateConfigBackup.Parsed parsed;
        try {
            parsed = RotogateConfigBackup.parse(req.getParameter("config"));
        } catch (IllegalArgumentException e) {
            rsp.sendRedirect(base + "?error=" + enc(e.getMessage()));
            return;
        }

        // Compute the plan (what will change) before touching anything, then snapshot + apply.
        RotogateConfigBackup.Diff plan = RotogateConfigBackup.diff(parsed);
        String snapshot = RotogateConfigBackup.export();
        saveSnapshot(snapshot);
        RotogateConfigBackup.applyParsed(parsed);

        // Lockout guard: if the acting admin would lose ADMINISTER, revert and refuse.
        if (!Jenkins.get().getACL().hasPermission2(Jenkins.getAuthentication2(), Jenkins.ADMINISTER)) {
            RotogateConfigBackup.applyParsed(RotogateConfigBackup.parse(snapshot));
            rsp.sendRedirect(base + "?error=" + enc(
                    "Import refused: it would remove your own administrator access. Reverted - no changes applied."));
            return;
        }

        recordHistory("import", plan.summary());
        BR_LOG.info("Rotogate config imported by " + Jenkins.getAuthentication2().getName()
                + " (roles=" + parsed.roleCount() + ", assignments=" + parsed.assignmentCount()
                + ", groups=" + parsed.groupCount() + ", settings=" + parsed.globalCount() + ")");
        RotogateAuditLog.get().logConfigImported(Jenkins.getAuthentication2().getName(),
                "roles=" + parsed.roleCount() + ", assignments=" + parsed.assignmentCount()
                + ", groups=" + parsed.groupCount() + ", settings=" + parsed.globalCount());
        rsp.sendRedirect(base + "?imported=1&roles=" + parsed.roleCount()
                + "&assignments=" + parsed.assignmentCount() + "&groups=" + parsed.groupCount());
    }

    /**
     * Returns the import "plan" as JSON - what applying the uploaded config would add / remove /
     * change relative to the live configuration - without applying anything. Powers the preview
     * modal the admin confirms before a real import.
     */
    @POST
    public void doPreviewImport(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        req.setCharacterEncoding("UTF-8");
        net.sf.json.JSONObject out;
        try {
            RotogateConfigBackup.Parsed parsed = RotogateConfigBackup.parse(req.getParameter("config"));
            out = planToJson(RotogateConfigBackup.diff(parsed));
        } catch (IllegalArgumentException e) {
            out = planError(e.getMessage());
        }
        writeJson(rsp, out);
    }

    /**
     * Returns the "plan" for rolling back to a chosen snapshot - the same diff shown for an import,
     * but computed against the snapshot's contents - without applying anything. Powers the preview
     * modal before a rollback, so a restore is as auditable as an import.
     */
    @POST
    public void doPreviewRevert(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        net.sf.json.JSONObject out;
        try {
            String chosen = req.getParameter("snapshot");
            java.io.File snap = (chosen == null || chosen.isBlank()) ? latestSnapshot() : resolveSnapshot(chosen);
            if (snap == null) throw new IllegalArgumentException("That restore point is no longer available.");
            String yaml = java.nio.file.Files.readString(snap.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            out = planToJson(RotogateConfigBackup.diff(RotogateConfigBackup.parse(yaml)));
        } catch (IllegalArgumentException e) {
            out = planError(e.getMessage());
        }
        writeJson(rsp, out);
    }

    /** Serialises a {@link RotogateConfigBackup.Diff} to the plan JSON the preview modal renders. */
    private static net.sf.json.JSONObject planToJson(RotogateConfigBackup.Diff d) {
        net.sf.json.JSONObject out = new net.sf.json.JSONObject();
        out.put("ok", true);
        out.put("empty", d.isEmpty());
        out.put("total", d.totalChanges());
        out.put("summary", d.summary());
        out.put("roles", changeGroupJson(d.rolesAdded, d.rolesRemoved, d.rolesChanged));
        out.put("groups", changeGroupJson(d.groupsAdded, d.groupsRemoved, d.groupsChanged));
        out.put("assignments", changeGroupJson(d.assignmentsAdded, d.assignmentsRemoved, d.assignmentsChanged));
        net.sf.json.JSONArray settings = new net.sf.json.JSONArray();
        for (RotogateConfigBackup.SettingChange c : d.settingsChanged) {
            net.sf.json.JSONObject s = new net.sf.json.JSONObject();
            s.put("key", c.key);
            s.put("from", truncate(c.from, 120));
            s.put("to", truncate(c.to, 120));
            settings.add(s);
        }
        out.put("settings", settings);
        return out;
    }

    private static net.sf.json.JSONObject planError(String message) {
        net.sf.json.JSONObject out = new net.sf.json.JSONObject();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private static void writeJson(StaplerResponse2 rsp, net.sf.json.JSONObject out) throws Exception {
        rsp.setContentType("application/json; charset=utf-8");
        rsp.getWriter().write(out.toString());
    }

    private static net.sf.json.JSONObject changeGroupJson(List<String> added, List<String> removed,
                                                          List<RotogateConfigBackup.ItemChange> changed) {
        net.sf.json.JSONObject o = new net.sf.json.JSONObject();
        o.put("added", net.sf.json.JSONArray.fromObject(added));
        o.put("removed", net.sf.json.JSONArray.fromObject(removed));
        net.sf.json.JSONArray ch = new net.sf.json.JSONArray();
        for (RotogateConfigBackup.ItemChange c : changed) {
            net.sf.json.JSONObject co = new net.sf.json.JSONObject();
            co.put("name", c.name);
            net.sf.json.JSONArray lines = new net.sf.json.JSONArray();
            for (RotogateConfigBackup.ChangeLine cl : c.lines) {
                net.sf.json.JSONObject lo = new net.sf.json.JSONObject();
                lo.put("label", cl.label);
                net.sf.json.JSONArray segs = new net.sf.json.JSONArray();
                for (RotogateConfigBackup.Seg sg : cl.segs) {
                    net.sf.json.JSONObject so = new net.sf.json.JSONObject();
                    so.put("text", sg.text);
                    so.put("tone", sg.tone);
                    segs.add(so);
                }
                lo.put("segs", segs);
                lines.add(lo);
            }
            co.put("lines", lines);
            ch.add(co);
        }
        o.put("changed", ch);
        return o;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @POST
    public void doRevertImport(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String base = req.getContextPath() + "/manage/rotogate-management/backup";

        // A specific snapshot may be chosen from the history list; default to the most recent.
        String chosen = req.getParameter("snapshot");
        java.io.File snap = (chosen == null || chosen.isBlank()) ? latestSnapshot() : resolveSnapshot(chosen);
        if (snap == null) { rsp.sendRedirect(base + "?error=" + enc("That restore point is no longer available.")); return; }

        String yaml = java.nio.file.Files.readString(snap.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        RotogateConfigBackup.Parsed parsed;
        try {
            parsed = RotogateConfigBackup.parse(yaml);
        } catch (IllegalArgumentException e) {
            rsp.sendRedirect(base + "?error=" + enc("That restore point could not be read: " + e.getMessage()));
            return;
        }
        RotogateConfigBackup.applyParsed(parsed);
        recordHistory("revert", "restored point " + displayTimeOf(snap));
        BR_LOG.info("Rotogate config reverted to snapshot " + snap.getName() + " by "
                + Jenkins.getAuthentication2().getName());
        RotogateAuditLog.get().logConfigReverted(Jenkins.getAuthentication2().getName(),
                "restored point " + displayTimeOf(snap));
        rsp.sendRedirect(base + "?reverted=1");
    }

    /** True when at least one restore point exists (controls the Revert card in the UI). */
    public boolean isRevertAvailable() { return latestSnapshot() != null; }

    /** Resolves a snapshot by file name, rejecting anything outside the snapshot directory. */
    private static java.io.File resolveSnapshot(String name) {
        if (!name.matches("pre-import-\\d{8}-\\d{6}\\.yaml")) return null;
        java.io.File f = new java.io.File(snapshotDir(), name);
        return f.isFile() ? f : null;
    }

    private static java.io.File snapshotDir() {
        java.io.File d = new java.io.File(Jenkins.get().getRootDir(), "rotogate-backups");
        if (!d.exists() && !d.mkdirs() && !d.isDirectory()) {
            LOGGER.log(Level.WARNING, "Failed to create backup directory {0}", d);
        }
        return d;
    }

    private void saveSnapshot(String yaml) throws Exception {
        java.io.File dir = snapshotDir();
        String name = "pre-import-" + java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now()) + ".yaml";
        java.nio.file.Files.writeString(new java.io.File(dir, name).toPath(), yaml,
                java.nio.charset.StandardCharsets.UTF_8);
        java.io.File[] files = dir.listFiles((d, n) -> n.startsWith("pre-import-") && n.endsWith(".yaml"));
        if (files != null && files.length > 5) {
            java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
            for (int i = 0; i < files.length - 5; i++) {
                if (!files[i].delete() && files[i].exists()) {
                    LOGGER.log(Level.FINE, "Failed to delete old snapshot {0}", files[i]);
                }
            }
        }
    }

    private static java.io.File latestSnapshot() {
        java.io.File[] files = snapshotDir().listFiles((d, n) -> n.startsWith("pre-import-") && n.endsWith(".yaml"));
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
        return files[files.length - 1];
    }


    /** The restore points (pre-import snapshots), newest first, for the Revert history list. */
    public List<SnapshotInfo> getSnapshots() {
        java.io.File[] files = snapshotDir().listFiles((d, n) -> n.startsWith("pre-import-") && n.endsWith(".yaml"));
        List<SnapshotInfo> out = new ArrayList<>();
        if (files == null) return out;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName).reversed());
        for (java.io.File f : files) {
            int roles = 0, assignments = 0, groups = 0;
            try {
                String yaml = java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                RotogateConfigBackup.Parsed p = RotogateConfigBackup.parse(yaml);
                roles = p.roleCount(); assignments = p.assignmentCount(); groups = p.groupCount();
            } catch (Exception e) {
                // a snapshot that can't be parsed still appears, just without counts
                LOGGER.log(Level.FINE, "Could not parse snapshot " + f.getName() + " for counts", e);
            }
            out.add(new SnapshotInfo(f.getName(), displayTimeOf(f), roles, assignments, groups));
        }
        return out;
    }

    /** Parses the timestamp out of a {@code pre-import-yyyyMMdd-HHmmss.yaml} name into a readable string. */
    private static String displayTimeOf(java.io.File snap) {
        String n = snap.getName();
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(
                    n.substring("pre-import-".length(), n.length() - ".yaml".length()),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return n;
        }
    }

    // ── Import history (what changed, when, by whom) ──────────────────────────

    private static final int HISTORY_KEEP = 20;  // retained on disk
    private static final int HISTORY_SHOW = 5;    // surfaced in the UI


    private static java.io.File historyFile() {
        return new java.io.File(snapshotDir(), "import-history.json");
    }

    /** Appends an event to the import-history log (newest first), capped at {@link #HISTORY_KEEP}. */
    private static void recordHistory(String action, String summary) {
        try {
            net.sf.json.JSONArray arr = readHistoryArray();
            net.sf.json.JSONObject e = new net.sf.json.JSONObject();
            e.put("time", System.currentTimeMillis());
            e.put("user", Jenkins.getAuthentication2().getName());
            e.put("action", action);
            e.put("summary", summary == null ? "" : summary);
            arr.add(0, e);
            while (arr.size() > HISTORY_KEEP) arr.remove(arr.size() - 1);
            java.nio.file.Files.writeString(historyFile().toPath(), arr.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            BR_LOG.log(Level.WARNING, "Could not record Rotogate import-history event", ex);
        }
    }

    private static net.sf.json.JSONArray readHistoryArray() {
        java.io.File f = historyFile();
        if (!f.isFile()) return new net.sf.json.JSONArray();
        try {
            String json = java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            if (json.isBlank()) return new net.sf.json.JSONArray();
            return net.sf.json.JSONArray.fromObject(json);
        } catch (Exception ex) {
            return new net.sf.json.JSONArray();
        }
    }

    /** The most recent import/revert events (newest first) for the history table under Revert. */
    public List<HistoryEntry> getImportHistory() {
        net.sf.json.JSONArray arr = readHistoryArray();
        List<HistoryEntry> out = new ArrayList<>();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        for (int i = 0; i < arr.size() && out.size() < HISTORY_SHOW; i++) {
            net.sf.json.JSONObject e = arr.getJSONObject(i);
            String when = fmt.format(java.time.Instant.ofEpochMilli(e.optLong("time")));
            out.add(new HistoryEntry(when, e.optString("user", "unknown"),
                    e.optString("action", ""), e.optString("summary", "")));
        }
        return out;
    }

    /** True when there is at least one history event to show. */
    public boolean isHistoryAvailable() { return !getImportHistory().isEmpty(); }

    /** True when Rotogate is the active security realm (native sign-in is then always available). */
    public boolean isRotogateRealm() {
        return Jenkins.get().getSecurityRealm() instanceof RotogateSecurityRealm;
    }

    /** True when SSO sign-in is actually available - the toggle is on and a provider is configured. */
    public boolean isSsoConfigured() {
        Jenkins j = Jenkins.get();
        return (j.getSecurityRealm() instanceof RotogateSecurityRealm)
                && ((RotogateSecurityRealm) j.getSecurityRealm()).isSsoActive();
    }

    /** This plugin's version, read live from its wrapper (build qualifier stripped). For the Info page. */
    public String getPluginVersion() {
        hudson.PluginWrapper w = Jenkins.get().getPluginManager().getPlugin("rotogate");
        if (w == null) return "unknown";
        String v = w.getVersion();
        if (v == null) return "unknown";
        int sp = v.indexOf(' ');            // strip " (private-… )" dev build suffix
        return sp > 0 ? v.substring(0, sp) : v;
    }

    /** The caller's own HTTP session id, so sessions.jelly can flag "this is you" (no routing method needed). */
    public String getCurrentSessionId() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        jakarta.servlet.http.HttpSession s = (req != null) ? req.getSession(false) : null;
        return (s != null) ? s.getId() : null;
    }

    public java.util.List<ActiveSessionManager.ActiveSession> getActiveSessions() {
        return ActiveSessionManager.getAll();
    }

    public RotogateUserProperty getCurrentUserBreakGlassProp() {
        User current = User.current();
        if (current == null) return null;
        return current.getProperty(RotogateUserProperty.class);
    }

    @POST
    public void doBreakGlassEnrollInit(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");

        byte[] secretBytes = new byte[20];
        SECURE_RANDOM.nextBytes(secretBytes);
        String secret = org.jboss.aerogear.security.otp.api.Base32.encode(secretBytes);

        User currentUser = User.current();
        String userId = currentUser != null ? currentUser.getId() : "unknown";

        // Account label: prefer Entra UPN (their corporate email), fallback to Jenkins username
        RotogateUserProperty bgProp = currentUser != null ? currentUser.getProperty(RotogateUserProperty.class) : null;
        String account = (bgProp != null && bgProp.getEntraUpn() != null && !bgProp.getEntraUpn().isEmpty())
                ? bgProp.getEntraUpn() : userId;

        // Issuer: "Jenkins (hostname) OA" - fits on mobile, identifies instance, hints Rotogate
        String rootUrl = Jenkins.get().getRootUrl();
        String host = "jenkins";
        if (rootUrl != null) {
            try { host = new java.net.URL(rootUrl).getHost(); } catch (Exception ignored) {}
        }
        String issuer = "Jenkins (" + host + ") OA";

        String issuerEnc  = java.net.URLEncoder.encode(issuer,  "UTF-8").replace("+", "%20");
        String accountEnc = java.net.URLEncoder.encode(account, "UTF-8").replace("+", "%20");
        String uri = "otpauth://totp/" + issuerEnc + "%3A" + accountEnc
                   + "?secret=" + secret + "&issuer=" + issuerEnc;

        com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
        com.google.zxing.common.BitMatrix matrix = writer.encode(uri, com.google.zxing.BarcodeFormat.QR_CODE, 220, 220);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
        String qr = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());

        String escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"");
        rsp.getWriter().write("{\"secret\":\"" + escaped + "\",\"qr\":\"data:image/png;base64," + qr + "\"}");
    }

    @POST
    public void doBreakGlassEnrollVerify(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");

        String secret     = req.getParameter("secret");
        String code       = req.getParameter("code");
        String deviceName = req.getParameter("deviceName");
        if (secret == null || secret.isBlank() || code == null || code.trim().length() != 6) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Invalid request.\"}");
            return;
        }
        if (deviceName == null || deviceName.isBlank()) deviceName = "My Device";
        try {
            if (!new org.jboss.aerogear.security.otp.Totp(secret.trim()).verify(code.trim())) {
                rsp.getWriter().write("{\"success\":false,\"error\":\"Code is incorrect. Check your app and try again.\"}");
                return;
            }
        } catch (Exception ex) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Invalid secret format.\"}");
            return;
        }
        User current = User.current();
        if (current == null) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Not authenticated.\"}");
            return;
        }
        RotogateUserProperty prop = current.getProperty(RotogateUserProperty.class);
        if (prop == null) {
            prop = new RotogateUserProperty(null, null);
            current.addProperty(prop);
        }
        if (!prop.addBreakGlassDevice(deviceName.trim(), secret.trim())) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Maximum of 3 devices reached. Remove one before adding another.\"}");
            return;
        }
        current.save();
        RotogateAuditLog.get().logBreakGlassEnrolled(current.getId());
        rsp.getWriter().write("{\"success\":true}");
    }

    @POST
    public void doBreakGlassEnrollRemove(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");
        String deviceId = req.getParameter("deviceId");
        User current = User.current();
        if (current != null && deviceId != null) {
            RotogateUserProperty prop = current.getProperty(RotogateUserProperty.class);
            if (prop != null) {
                prop.removeBreakGlassDevice(deviceId);
                current.save();
                RotogateAuditLog.get().logBreakGlassUnenrolled(current.getId());
            }
        }
        rsp.getWriter().write("{\"success\":true}");
    }

    @POST
    public void doBreakGlassActivate(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");

        String reason = req.getParameter("reason");
        String totpCode = req.getParameter("totpCode");

        if (reason == null || reason.trim().isEmpty()) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Reason is required.\"}");
            return;
        }
        if (totpCode == null || totpCode.trim().length() != 6) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Enter the 6-digit TOTP code.\"}");
            return;
        }

        User current = User.current();
        if (current == null) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Not authenticated.\"}");
            return;
        }
        String userId = current.getId();

        RotogateUserProperty prop = current.getProperty(RotogateUserProperty.class);
        if (prop == null || !prop.isBreakGlassTotpEnrolled()) {
            RotogateAuditLog.get().logBreakGlassFailed(userId, "No TOTP enrolled");
            rsp.getWriter().write("{\"success\":false,\"error\":\"No TOTP enrolled. Configure Break Glass in Rotogate → Break Glass.\"}");
            return;
        }

        if (!prop.verifyBreakGlassCode(totpCode.trim())) {
            RotogateAuditLog.get().logBreakGlassFailed(userId, "Invalid TOTP code");
            rsp.getWriter().write("{\"success\":false,\"error\":\"Invalid TOTP code. Try again.\"}");
            return;
        }

        long expiry = System.currentTimeMillis() + 15L * 60 * 1000;
        req.getSession().setAttribute("rotogate.breakGlass.expiry", expiry);
        req.getSession().setAttribute("rotogate.breakGlass.user", userId);
        req.getSession().setAttribute("rotogate.breakGlass.reason", reason.trim());

        RotogateAuditLog.get().logBreakGlassActivate(userId, reason.trim());
        rsp.getWriter().write("{\"success\":true}");
    }

    @POST
    public void doBreakGlassDeactivate(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");

        req.getSession().removeAttribute("rotogate.breakGlass.expiry");
        req.getSession().removeAttribute("rotogate.breakGlass.user");
        req.getSession().removeAttribute("rotogate.breakGlass.reason");

        User current = User.current();
        String userId = current != null ? current.getId() : "unknown";
        RotogateAuditLog.get().logBreakGlassDeactivate(userId);
        rsp.getWriter().write("{\"success\":true}");
    }

    @POST
    public void doTotpReminderSkip(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");
        User current = User.current();
        if (current != null) {
            RotogateUserProperty prop = current.getProperty(RotogateUserProperty.class);
            if (prop == null) {
                prop = new RotogateUserProperty(null, null);
                current.addProperty(prop);
            }
            if (prop.getBreakGlassEnrollSkips() < 3) {
                prop.setBreakGlassEnrollSkips(prop.getBreakGlassEnrollSkips() + 1);
                current.save();
                RotogateAuditLog.get().logConfigChanged(current.getId(), "Break Glass",
                        "enrollment reminder skipped (" + prop.getBreakGlassEnrollSkips() + "/3)");
            }
        }
        req.getSession().setAttribute("rotogate.totp.reminderDoneThisSession", Boolean.TRUE);
        String returnTo = (String) req.getSession().getAttribute("rotogate.totp.returnTo");
        req.getSession().removeAttribute("rotogate.totp.returnTo");
        String redirect = (returnTo != null && !returnTo.isEmpty()) ? returnTo : req.getContextPath() + "/";
        rsp.getWriter().write("{\"success\":true,\"redirect\":\"" + redirect.replace("\"", "\\\"") + "\"}");
    }

    public int getCurrentUserEnrollSkips() {
        User current = User.current();
        if (current == null) return 0;
        RotogateUserProperty prop = current.getProperty(RotogateUserProperty.class);
        return prop != null ? prop.getBreakGlassEnrollSkips() : 0;
    }

    public boolean isBreakGlassEnrollmentForced() { return getCurrentUserEnrollSkips() >= 3; }
    public int getBreakGlassSkipsAfterThis() { return Math.max(0, 2 - getCurrentUserEnrollSkips()); }

    // ── SOS Mode (lockout recovery) ────────────────────────────────────────────

    /** Back-compat alias - SOS recovery codes now live on the unified Emergency Access page. */
    /** Generate a fresh set of SOS recovery codes for the current admin; returns the raw codes once. */
    @POST
    public void doSosGenerateCodes(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        rsp.setContentType("application/json;charset=UTF-8");
        User current = User.current();
        if (current == null) {
            rsp.getWriter().write("{\"success\":false,\"error\":\"Not authenticated.\"}");
            return;
        }
        RotogateRecoveryCodesProperty prop = current.getProperty(RotogateRecoveryCodesProperty.class);
        boolean regenerate = prop != null && prop.isEnrolled();
        if (prop == null) {
            prop = new RotogateRecoveryCodesProperty();
            current.addProperty(prop);
        }
        java.util.List<String> codes = prop.regenerate();
        current.save();
        RotogateAuditLog.get().logConfigChanged(current.getId(), "SOS Recovery Codes",
                regenerate ? "recovery codes regenerated" : "recovery codes generated");

        net.sf.json.JSONArray arr = new net.sf.json.JSONArray();
        for (String c : codes) arr.add(c);
        net.sf.json.JSONObject out = new net.sf.json.JSONObject();
        out.put("success", true);
        out.put("codes", arr);
        rsp.getWriter().write(out.toString());
    }

    /** Deactivate an armed SOS window (from the site-wide banner or the Settings page). */
    @POST
    public void doSosDeactivate(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        User current = User.current();
        RotogateSos.deactivate(current != null ? current.getId() : "unknown");
        // The /sos/recover page deactivates via a plain (no-JS) form POST and sends fromForm=1; reply with a
        // redirect so the browser lands on Jenkins rather than a bare JSON body. The site-wide banner's fetch
        // omits the marker and still gets JSON.
        if (req.getParameter("fromForm") != null) {
            rsp.sendRedirect(req.getContextPath() + "/");
            return;
        }
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write("{\"success\":true}");
    }

    /** True once the current admin has generated SOS recovery codes. */
    public boolean isSosEnrolled() {
        User current = User.current();
        if (current == null) return false;
        RotogateRecoveryCodesProperty prop = current.getProperty(RotogateRecoveryCodesProperty.class);
        return prop != null && prop.isEnrolled();
    }

    /** Unused SOS recovery codes remaining for the current admin. */
    public int getSosUnusedCount() {
        User current = User.current();
        if (current == null) return 0;
        RotogateRecoveryCodesProperty prop = current.getProperty(RotogateRecoveryCodesProperty.class);
        return prop != null ? prop.getUnusedCount() : 0;
    }

    public int getSosTotalCount() {
        User current = User.current();
        if (current == null) return 0;
        RotogateRecoveryCodesProperty prop = current.getProperty(RotogateRecoveryCodesProperty.class);
        return prop != null ? prop.getTotalCount() : 0;
    }

    public String getSosGeneratedAt() {
        User current = User.current();
        if (current == null) return null;
        RotogateRecoveryCodesProperty prop = current.getProperty(RotogateRecoveryCodesProperty.class);
        return prop != null ? prop.getGeneratedAt() : null;
    }

    /** Friendly date (yyyy-MM-dd, matching the Break Glass device list) of the current code set, or "". */
    public String getSosGeneratedDate() {
        String iso = getSosGeneratedAt();
        if (iso == null || iso.length() < 10) return "";
        return iso.substring(0, 10);
    }

    /** True when the SOS enrollment gate sent the admin here because they have no usable codes. */
    public boolean isSosEnrollMode() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        return req != null && req.getParameter("enroll") != null && getSosUnusedCount() == 0;
    }

    /** Enrolled but every code has been used up - strong "regenerate now" state. */
    public boolean isSosNoCodesLeft() { return isSosEnrolled() && getSosUnusedCount() == 0; }

    /** Enrolled and running low (1-2 unused) - soft "regenerate soon" nudge. */
    public boolean isSosLowOnCodes() {
        int n = getSosUnusedCount();
        return isSosEnrolled() && n > 0 && n <= SOS_LOW_THRESHOLD;
    }

    /** Below this many unused codes, warn the admin to regenerate. */
    public static final int SOS_LOW_THRESHOLD = 2;

    public boolean isSosArmed() { return RotogateSos.isArmed(); }
    public long getSosMinutesRemaining() { return RotogateSos.minutesRemaining(); }

    @POST
    public void doClearBruteForce(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String userId = req.getParameter("userId");
        if (userId != null && !userId.isEmpty()) {
            BruteForceTracker.clear(userId);
            RotogateAuditLog.get().logBruteForceCleared(Jenkins.getAuthentication2().getName(), userId);
        }
        rsp.sendRedirect("security");
    }

    @POST
    public void doRevokeSession(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER); // CSRF already enforced via @POST
        String sessionId = req.getParameter("sessionId");
        String currentSessionId = req.getSession(false) != null ? req.getSession(false).getId() : null;
        if (sessionId == null || sessionId.equals(currentSessionId)) {
            rsp.sendRedirect("sessions?error=self");
            return;
        }
        // Resolve the affected user before revoking so the audit entry names them, not the opaque id.
        String targetUser = sessionId;
        for (ActiveSessionManager.ActiveSession s : ActiveSessionManager.getAll()) {
            if (sessionId.equals(s.getSessionId())) { targetUser = s.getUserId(); break; }
        }
        ActiveSessionManager.revoke(sessionId);
        RotogateAuditLog.get().logSessionRevoked(Jenkins.getAuthentication2().getName(), targetUser);
        rsp.sendRedirect("sessions?revoked=true");
    }

    public NotificationLog getNotificationLog() {
        return NotificationLog.get();
    }

    @POST
    public void doSendTestSlack(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String url = req.getParameter("slackWebhookUrl");
        String json;
        if (url == null || url.trim().isEmpty()) {
            json = "{\"ok\":false,\"msg\":\"Webhook URL is required\"}";
        } else {
            try {
                SlackHelper.test(url.trim());
                json = "{\"ok\":true,\"msg\":\"Test message sent to Slack\"}";
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                json = "{\"ok\":false,\"msg\":\"" + escapeJson(msg) + "\"}";
            }
        }
        writeJson(rsp, json);
    }

    @POST
    public void doSendTestTeams(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String url = req.getParameter("teamsWebhookUrl");
        String json;
        if (url == null || url.trim().isEmpty()) {
            json = "{\"ok\":false,\"msg\":\"Webhook URL is required\"}";
        } else {
            try {
                TeamsHelper.test(url.trim());
                json = "{\"ok\":true,\"msg\":\"Test message sent to Teams\"}";
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                json = "{\"ok\":false,\"msg\":\"" + escapeJson(msg) + "\"}";
            }
        }
        writeJson(rsp, json);
    }

    private static void writeJson(StaplerResponse2 rsp, String json) throws Exception {
        rsp.setContentType("application/json;charset=UTF-8");
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        rsp.setContentLength(bytes.length);
        rsp.getOutputStream().write(bytes);
    }

    @POST
    public void doClearNotificationLog(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        NotificationLog.get().clear();
        RotogateAuditLog.get().logConfigChanged(currentUserId(), "Notification log", "cleared");
        rsp.sendRedirect("notificationLog?cleared=true");
    }

    @POST
    public void doSaveNotifications(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RotogateGlobalConfig config = RotogateGlobalConfig.get();
        if (config != null) {
            // Snapshot full channel state BEFORE the save
            boolean masterWasOn   = config.isNotificationsEnabled();
            boolean smtpWasOn     = config.isSmtpEnabled();
            boolean slackWasOn    = config.isSlackEnabled();
            boolean teamsWasOn    = config.isTeamsEnabled();
            boolean smtpHadConfigChanged  = masterWasOn && smtpWasOn  && config.isSmtpConfigured()              && config.isSmtpEvent("configChanged");
            boolean slackHadConfigChanged = masterWasOn && slackWasOn && !config.getSlackWebhookUrlPlain().isEmpty() && config.isSlackEvent("configChanged");
            boolean teamsHadConfigChanged = masterWasOn && teamsWasOn && !config.getTeamsWebhookUrlPlain().isEmpty() && config.isTeamsEvent("configChanged");
            // Also snapshot whether the configChanged event was subscribed (independently of enabled state)
            boolean smtpHadEvent  = config.isSmtpEvent("configChanged");
            boolean slackHadEvent = config.isSlackEvent("configChanged");
            boolean teamsHadEvent = config.isTeamsEvent("configChanged");

            // Capture raw SMTP credentials before the save - used if SMTP gets disabled in the same operation
            final String snapHost      = config.getSmtpHost();
            final int    snapPort      = config.getSmtpPort();
            final String snapUsername  = config.getSmtpUsername();
            final String snapPassword  = config.getSmtpPassword() != null ? config.getSmtpPassword().getPlainText() : "";
            final boolean snapTls      = config.isSmtpTls();
            final String snapFromAddr  = config.getSmtpFromAddress();
            final String snapFromName  = config.getSmtpFromName() != null ? config.getSmtpFromName() : "Jenkins Rotogate";
            final String snapReplyTo   = config.getSmtpReplyTo();
            final String snapRecipients = config.getNotifyEmails();
            final String snapSlackUrl   = config.getSlackWebhookUrlPlain();
            final String snapTeamsUrl   = config.getTeamsWebhookUrlPlain();
            final String snapLogoUrl    = config.getNotificationLogoUrl();

            // Each area holder binds its own fields from the form. The Notifications page owns the
            // channels/SMTP plus the brute-force and stale-warning controls shown on it; every other
            // setting lives on a holder this page never touches, so it is left untouched.
            config.getNotifications().updateFrom(req);
            config.getSecurity().updateBruteForceFrom(req);
            config.getLifecycle().updateStaleWarningFrom(req);
            config.save();

            // Fire a farewell alert on any channel that previously had configChanged enabled
            // but no longer does - using PRE-SAVE credentials so disabling SMTP/master cannot silence it.
            boolean smtpLost  = smtpHadConfigChanged  && !(config.isNotificationsEnabled() && config.isSmtpEnabled()  && config.isSmtpEvent("configChanged"));
            boolean slackLost = slackHadConfigChanged && !(config.isNotificationsEnabled() && config.isSlackEnabled() && config.isSlackEvent("configChanged"));
            boolean teamsLost = teamsHadConfigChanged && !(config.isNotificationsEnabled() && config.isTeamsEnabled() && config.isTeamsEvent("configChanged"));
            if (smtpLost || slackLost || teamsLost) {
                String changedBy = currentUserId();
                List<String> diff = new ArrayList<>();
                // Master switch
                if (masterWasOn && !config.isNotificationsEnabled())
                    diff.add("All notifications were globally disabled");
                if (smtpLost) {
                    if (!masterWasOn || config.isNotificationsEnabled()) {
                        if (smtpWasOn && !config.isSmtpEnabled())
                            diff.add("Email (SMTP) notification channel was disabled");
                        else
                            diff.add("Configuration change alerts were removed from email (SMTP)");
                    }
                }
                if (slackLost) {
                    if (!masterWasOn || config.isNotificationsEnabled()) {
                        if (slackWasOn && !config.isSlackEnabled())
                            diff.add("Slack notification channel was disabled");
                        else
                            diff.add("Configuration change alerts were removed from Slack");
                    }
                }
                if (teamsLost) {
                    if (!masterWasOn || config.isNotificationsEnabled()) {
                        if (teamsWasOn && !config.isTeamsEnabled())
                            diff.add("Microsoft Teams notification channel was disabled");
                        else
                            diff.add("Configuration change alerts were removed from Microsoft Teams");
                    }
                }
                String subject = "[Jenkins Rotogate] Configuration change alerts have been disabled by " + changedBy;
                StringBuilder body = new StringBuilder();
                body.append("Notification Settings Change\n");
                body.append("============================\n\n");
                body.append("Changed by: ").append(changedBy).append("\n");
                body.append("When:       ").append(java.time.Instant.now()).append("\n\n");
                body.append("This is the final configuration change alert for the affected channel(s).\n");
                body.append("The following changes have disabled configuration change notifications:\n\n");
                for (String line : diff) body.append("  - ").append(line).append("\n");
                body.append("\nNo further alerts of this type will be delivered until notifications are re-enabled.\n");
                body.append(mgmtCta("Review Settings", "notifications"));
                body.append("\n---\nJenkins Rotogate Plugin");
                final String msg = body.toString();
                // All channels use pre-save credentials and run async
                if (smtpLost && !snapRecipients.isEmpty()) {
                    Thread t = new Thread(() -> SmtpHelper.sendNow(snapHost, snapPort, snapUsername, snapPassword,
                            snapTls, snapFromAddr, snapFromName, snapReplyTo, snapRecipients, subject, msg, snapLogoUrl));
                    t.setDaemon(true); t.setName("rotogate-farewell-smtp"); t.start();
                }
                if (slackLost && !snapSlackUrl.isEmpty()) {
                    final String slackPayload = SlackHelper.buildPayload(subject, msg);
                    Thread t = new Thread(() -> { try { SlackHelper.postJson(snapSlackUrl, slackPayload); } catch (Exception e) { LOGGER.warning("Farewell Slack alert failed: " + e.getMessage()); } });
                    t.setDaemon(true); t.setName("rotogate-farewell-slack"); t.start();
                }
                if (teamsLost && !snapTeamsUrl.isEmpty()) {
                    final String teamsPayload = TeamsHelper.buildPayload(subject, msg);
                    Thread t = new Thread(() -> { try { TeamsHelper.postJson(snapTeamsUrl, teamsPayload); } catch (Exception e) { LOGGER.warning("Farewell Teams alert failed: " + e.getMessage()); } });
                    t.setDaemon(true); t.setName("rotogate-farewell-teams"); t.start();
                }
            }
            // Alert when config-change notifications are re-enabled on any channel
            boolean smtpGained  = !smtpHadConfigChanged  && config.isNotificationsEnabled() && config.isSmtpEnabled()  && config.isSmtpConfigured()              && config.isSmtpEvent("configChanged");
            boolean slackGained = !slackHadConfigChanged && config.isNotificationsEnabled() && config.isSlackEnabled() && !config.getSlackWebhookUrlPlain().isEmpty() && config.isSlackEvent("configChanged");
            boolean teamsGained = !teamsHadConfigChanged && config.isNotificationsEnabled() && config.isTeamsEnabled() && !config.getTeamsWebhookUrlPlain().isEmpty() && config.isTeamsEvent("configChanged");
            if (smtpGained || slackGained || teamsGained) {
                String changedBy = currentUserId();
                List<String> diff = new ArrayList<>();
                if (!masterWasOn && config.isNotificationsEnabled())
                    diff.add("All notifications were globally enabled");
                if (smtpGained) {
                    if (!smtpWasOn) diff.add("Email (SMTP) notification channel was enabled");
                    else if (!smtpHadEvent) diff.add("Configuration change alerts were added to email (SMTP)");
                }
                if (slackGained) {
                    if (!slackWasOn) diff.add("Slack notification channel was enabled");
                    else if (!slackHadEvent) diff.add("Configuration change alerts were added to Slack");
                }
                if (teamsGained) {
                    if (!teamsWasOn) diff.add("Microsoft Teams notification channel was enabled");
                    else if (!teamsHadEvent) diff.add("Configuration change alerts were added to Microsoft Teams");
                }
                String subject = "[Jenkins Rotogate] Configuration change alerts have been enabled by " + changedBy;
                StringBuilder body = new StringBuilder();
                body.append("Notification Settings Change\n");
                body.append("============================\n\n");
                body.append("Changed by: ").append(changedBy).append("\n");
                body.append("When:       ").append(java.time.Instant.now()).append("\n\n");
                body.append("Configuration change alerts have been enabled on one or more notification channels.\n");
                body.append("All future configuration changes will be reported accordingly.\n\n");
                body.append("The following changes were applied:\n\n");
                for (String line : diff) body.append("  + ").append(line).append("\n");
                body.append(mgmtCta("Review Settings", "notifications"));
                body.append("\n---\nJenkins Rotogate Plugin");
                // Use new config - channels are now live; respect master toggle
                if (config.isNotificationsEnabled()) {
                    if (smtpGained)  SmtpHelper.send(config, subject, body.toString());
                    if (slackGained) SlackHelper.send(config, subject, body.toString());
                    if (teamsGained) TeamsHelper.send(config, subject, body.toString());
                }
            }
        }
        RotogateAuditLog.get().logConfigChanged(currentUserId(), "Notifications",
                "notification channels / brute-force settings saved");
        rsp.sendRedirect("notifications?saved=true");
    }

    @POST
    public void doSaveProtectedUsers(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String[] selected = req.getParameterValues("protectedUsers");
        RotogateGlobalConfig config = RotogateGlobalConfig.get();
        if (config != null) {
            List<String> oldProtected = new ArrayList<>(config.getProtectedUsers());

            // Change ONLY the protected-users list. Do NOT route through configure(json):
            // that reparses every field and would reset all other settings to defaults.
            List<String> newList = (selected != null) ? new ArrayList<>(java.util.Arrays.asList(selected)) : new ArrayList<>();
            config.setProtectedUsers(newList);

            List<String> newProtected = new ArrayList<>(config.getProtectedUsers());
            List<String> added   = new ArrayList<>(newProtected);
            added.removeAll(oldProtected);
            List<String> removed = new ArrayList<>(oldProtected);
            removed.removeAll(newProtected);

            NotificationService.sendProtectedListChanged(config, currentUserId(), added, removed);
            if (!added.isEmpty() || !removed.isEmpty()) {
                StringBuilder d = new StringBuilder();
                if (!added.isEmpty())   d.append("added ").append(added);
                if (!removed.isEmpty()) d.append(d.length() > 0 ? "; " : "").append("removed ").append(removed);
                RotogateAuditLog.get().logConfigChanged(currentUserId(), "Protected users", d.toString());
            }
        }
        rsp.sendRedirect("protectedUsers");
    }

    @POST
    public void doSaveSettings(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RotogateGlobalConfig config = RotogateGlobalConfig.get();
        if (config != null) {
            // snapshot before
            int    oldStale          = config.getStaleThresholdDays();
            int    oldActive         = config.getActiveThresholdDays();
            boolean oldCleanupEnabled = config.isCleanupEnabled();
            boolean oldDryRun        = config.isCleanupDryRun();
            String oldCron           = config.getCleanupCron();
            int    oldMaxDel         = config.getCleanupMaxDeletions();
            int    oldBft            = config.getBruteForceThreshold();
            int    oldAuditRet       = config.getAuditRetentionDays();
            boolean oldSosEnabled = config.isSosEnabled();
            int    oldRememberSecs = config.getRememberMeSeconds();

            // Each area holder binds its own fields from the form. The Settings page owns the
            // thresholds / cleanup / access-review (lifecycle), the SOS switch + "Keep me signed in"
            // duration (security) and the login branding; every other setting lives on a holder this
            // page never touches.
            config.getLifecycle().updateSettingsFrom(req);
            config.getSecurity().updateSosFrom(req);
            config.getSecurity().updateRememberMeFrom(req);
            config.getBranding().updateFrom(req);
            config.save();

            // diff and notify
            List<String> diff = new ArrayList<>();
            if (config.getStaleThresholdDays()  != oldStale)         diff.add("staleThresholdDays: "  + oldStale   + " → " + config.getStaleThresholdDays());
            if (config.getActiveThresholdDays() != oldActive)        diff.add("activeThresholdDays: " + oldActive  + " → " + config.getActiveThresholdDays());
            if (config.isCleanupEnabled()       != oldCleanupEnabled) diff.add("cleanupEnabled: "     + oldCleanupEnabled + " → " + config.isCleanupEnabled());
            if (config.isCleanupDryRun()        != oldDryRun)        diff.add("cleanupDryRun: "       + oldDryRun  + " → " + config.isCleanupDryRun());
            if (!config.getCleanupCron().equals(oldCron))            diff.add("cleanupCron: "         + oldCron    + " → " + config.getCleanupCron());
            if (config.getCleanupMaxDeletions() != oldMaxDel)        diff.add("cleanupMaxDeletions: " + oldMaxDel  + " → " + config.getCleanupMaxDeletions());
            if (config.getBruteForceThreshold() != oldBft)           diff.add("bruteForceThreshold: " + oldBft     + " → " + config.getBruteForceThreshold());
            if (config.getAuditRetentionDays()  != oldAuditRet)      diff.add("auditRetentionDays: "  + oldAuditRet + " → " + config.getAuditRetentionDays());
            if (config.isSosEnabled() != oldSosEnabled) diff.add("sosEnabled: " + oldSosEnabled + " → " + config.isSosEnabled());
            if (config.getRememberMeSeconds() != oldRememberSecs) diff.add("rememberMe: " + oldRememberSecs + "s → " + config.getRememberMeSeconds() + "s");
            if (!diff.isEmpty()) {
                NotificationService.sendConfigChanged(config, currentUserId(), java.time.Instant.now().toString(), diff);
                RotogateAuditLog.get().logConfigChanged(currentUserId(), "Settings", String.join("; ", diff));
            }
        }
        rsp.sendRedirect("settings?saved=true");
    }

    /**
     * The descriptor that backs the Notifications test-email button. Exposed so notifications.jelly
     * can give {@code f:validateButton} a descriptor context; the test logic lives on
     * {@link RotogateGlobalConfig#doSendTestEmail} (the descriptor that owns the SMTP settings).
     */
    public RotogateGlobalConfig getSmtpTestDescriptor() {
        return RotogateGlobalConfig.get();
    }

    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doPreviewEmail(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        String eventType = param(req, "eventType", "configChanged");
        String logoOverride     = param(req, "logoUrl",     "");
        String footerNoteOverride = req.getParameter("footerNote"); // null = not sent, "" = cleared
        String html = buildPreviewHtml(cfg, eventType, logoOverride, footerNoteOverride);
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        rsp.setContentType("text/html;charset=UTF-8");
        rsp.setContentLength(bytes.length);
        rsp.getOutputStream().write(bytes);
    }

    private static String buildPreviewHtml(RotogateGlobalConfig cfg, String eventType,
                                            String logoOverride, String footerNoteOverride) {
        String now = java.time.Instant.now().toString();
        String html;
        switch (eventType) {
            case "bruteForce":
                html = SmtpHelper.buildBruteForceHtml(cfg, "john.doe@corp.com", 8); break;
            case "userDeleted":
                html = SmtpHelper.buildUserDeletedHtml(cfg, "alice.smith", "admin"); break;
            case "cleanup": {
                RotogateGlobalConfig.CleanupRunRecord rec = new RotogateGlobalConfig.CleanupRunRecord(
                    now, false, 142, 3, 5,
                    java.util.Arrays.asList("inactive.user1", "old.contractor", "ex.employee99"));
                html = SmtpHelper.buildCleanupReportHtml(cfg, rec); break;
            }
            case "adminGranted":
                html = SmtpHelper.buildAdminGrantedHtml(cfg,
                    java.util.Arrays.asList("bob.jones", "carol.dev"), "admin"); break;
            case "staleWarning":
                html = SmtpHelper.buildStaleWarningHtml(cfg,
                    java.util.Arrays.asList("dave.legacy", "eve.contractor", "frank.temp"), 7, 90); break;
            case "protectedListChanged":
                html = SmtpHelper.buildProtectedListChangedHtml(cfg, "admin",
                    java.util.Arrays.asList("alice.smith"),
                    java.util.Arrays.asList("old.vendor")); break;
            case "accessReview": {
                RotogateAssignment a1 = new RotogateAssignment("alice.smith", "USER", "developer",
                        "platform/payments-api", "JOB",
                        java.util.Collections.emptyList(),
                        java.time.Instant.now().minus(95, java.time.temporal.ChronoUnit.DAYS).toString(), "admin");
                RotogateAssignment a2 = new RotogateAssignment("bob.jones", "USER", "admin",
                        "", "GLOBAL",
                        java.util.Collections.emptyList(),
                        java.time.Instant.now().minus(120, java.time.temporal.ChronoUnit.DAYS).toString(), "admin");
                RotogateAssignment a3 = new RotogateAssignment("contractors", "GROUP", "read-only",
                        "platform", "FOLDER",
                        java.util.Collections.emptyList(),
                        java.time.Instant.now().minus(200, java.time.temporal.ChronoUnit.DAYS).toString(), "admin");
                html = SmtpHelper.buildAccessReviewHtml(cfg,
                        java.util.Arrays.asList(a1, a2, a3),
                        cfg != null ? cfg.getAccessReviewThresholdDays() : 90);
                break;
            }
            case "graphApiFailure":
                html = SmtpHelper.buildGraphApiFailedHtml(cfg, "john.doe@corp.com",
                    "403 Forbidden: Insufficient privileges to complete the operation."); break;
            case "smtpTest": {
                String host = cfg != null ? cfg.getSmtpHost()        : "mail.corp.com";
                int port    = cfg != null ? cfg.getSmtpPort()        : 465;
                String from = cfg != null ? cfg.getSmtpFromAddress() : "jenkins@corp.com";
                String to   = cfg != null ? cfg.getNotifyEmails()    : "admin@corp.com";
                html = SmtpHelper.buildSmtpTestHtml(host, port, from, to); break;
            }
            case "jitRequest": {
                RotogateJitRequest jr = RotogateJitRequest.create(
                        "dave.dev", "platform/payments-api",
                        "Hotfix for prod incident INC-4821", 4, 24);
                String jitActionUrl = (jenkins.model.Jenkins.get().getRootUrl() != null
                        ? jenkins.model.Jenkins.get().getRootUrl() : "")
                        + "rotogate-jit/action?token=preview-sample";
                html = SmtpHelper.buildJitApproverRequestHtml(cfg, jr, "jane.admin", jitActionUrl);
                break;
            }
            case "jitApproved": {
                RotogateJitRequest jr = RotogateJitRequest.create(
                        "dave.dev", "platform/payments-api",
                        "Hotfix for prod incident INC-4821", 4, 24);
                jr.setApproverId("jane.admin");
                jr.setExpiresAt(java.time.Instant.now()
                        .plus(4, java.time.temporal.ChronoUnit.HOURS).toString());
                html = SmtpHelper.buildJitApprovedHtml(cfg, jr);
                break;
            }
            case "jitDenied": {
                RotogateJitRequest jr = RotogateJitRequest.create(
                        "dave.dev", "platform/payments-api",
                        "Hotfix for prod incident INC-4821", 4, 24);
                jr.setApproverId("jane.admin");
                jr.setApproverComment("Use the standard change-request process for payments-api.");
                html = SmtpHelper.buildJitDeniedHtml(cfg, jr);
                break;
            }
            default: // configChanged
                html = SmtpHelper.buildConfigChangedHtml(cfg, "admin", now,
                    java.util.Arrays.asList(
                        "smtpHost: old-mail.corp.com \u2192 mail.corp.com",
                        "smtpPort: 25 \u2192 465",
                        "smtpTls: false \u2192 true",
                        "staleThresholdDays: 60 \u2192 90"));
        }
        if (!logoOverride.isEmpty()) {
            String savedLogo = (cfg != null && cfg.getNotificationLogoUrl() != null
                                && !cfg.getNotificationLogoUrl().trim().isEmpty())
                ? cfg.getNotificationLogoUrl().trim()
                : SmtpHelper.defaultLogoUrl();
            // Escape the caller-supplied logo URL - it lands inside a single-quoted src
            // attribute in an HTML document served from the Jenkins origin, so an un-escaped
            // value (e.g. "x' onerror='…") would be reflected XSS running as the viewing admin.
            String safeLogo = logoOverride
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("'", "&#39;").replace("\"", "&quot;");
            html = html.replace("src='" + savedLogo + "'", "src='" + safeLogo + "'");
        }
        if (footerNoteOverride != null) {
            // Splice the live footer note between the separator line and the fine-print div.
            String sep      = "<div style='height:1px;background:#e8edf3;margin:12px 0;'></div>";
            String finePrint = "<div style='font-size:10.5px;color:#b0bac7;line-height:1.7;text-align:center;'>";
            int sepIdx = html.indexOf(sep);
            int fpIdx  = html.indexOf(finePrint);
            if (sepIdx >= 0 && fpIdx > sepIdx) {
                String escaped = footerNoteOverride.trim()
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                String noteHtml = escaped.isEmpty() ? ""
                    : "<div style='font-size:11px;color:#64748b;line-height:1.6;"
                      + "text-align:center;margin-bottom:8px;'>" + escaped + "</div>";
                html = html.substring(0, sepIdx + sep.length()) + noteHtml + html.substring(fpIdx);
            }
        }
        return html;
    }

    private static String param(StaplerRequest2 req, String name, String fallback) {
        String v = req.getParameter(name);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : fallback;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String mgmtCta(String label, String page) {
        try {
            String r = Jenkins.get().getRootUrl();
            if (r == null || r.isEmpty()) return "";
            if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
            return "\nCTA: " + label + " | " + r + "/manage/rotogate-management/" + page;
        } catch (Exception e) { return ""; }
    }

    private static String currentUserId() {
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "unknown";
    }

    // -------------------------------------------------------------------------
    // Settings helpers
    // -------------------------------------------------------------------------

    public RotogateGlobalConfig getRotogateGlobalConfig() {
        return RotogateGlobalConfig.get();
    }

    public boolean isLoginLogoUploaded() {
        java.io.File dir = new java.io.File(Jenkins.get().getRootDir(), "rotogate-branding");
        java.io.File[] files = dir.listFiles(f -> f.getName().startsWith("login-logo."));
        return files != null && files.length > 0;
    }

    public String getLoginLogoPreviewUrl() {
        java.io.File dir = new java.io.File(Jenkins.get().getRootDir(), "rotogate-branding");
        java.io.File[] files = dir.listFiles(f -> f.getName().startsWith("login-logo."));
        if (files == null || files.length == 0) return "";
        String root = Jenkins.get().getRootUrl();
        if (root == null) return "";
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        // Cache-bust by last-modified so a freshly uploaded logo shows immediately.
        return root + "/securityRealm/loginLogo?v=" + files[0].lastModified();
    }

    @POST
    public void doUploadLoginLogo(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        org.apache.commons.fileupload.FileItem fileItem = req.getFileItem("loginLogo");
        if (fileItem == null || fileItem.getSize() == 0) {
            writeJson(rsp, "{\"ok\":false,\"msg\":\"No file received.\"}");
            return;
        }
        String originalName = fileItem.getName();
        String ext = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) ext = originalName.substring(dot + 1).toLowerCase().trim();
        }
        java.util.Set<String> allowed = new java.util.HashSet<>(
                java.util.Arrays.asList("png", "jpg", "jpeg", "svg", "gif", "webp", "ico"));
        if (!allowed.contains(ext)) {
            writeJson(rsp, "{\"ok\":false,\"msg\":\"Unsupported file type. Use PNG, JPG, SVG, GIF, or WebP.\"}");
            return;
        }
        if (fileItem.getSize() > 2 * 1024 * 1024) {
            writeJson(rsp, "{\"ok\":false,\"msg\":\"File too large. Maximum 2 MB.\"}");
            return;
        }
        java.io.File brandingDir = new java.io.File(Jenkins.get().getRootDir(), "rotogate-branding");
        if (!brandingDir.mkdirs() && !brandingDir.isDirectory()) {
            LOGGER.log(Level.WARNING, "Failed to create branding directory {0}", brandingDir);
        }
        java.io.File[] existing = brandingDir.listFiles(f -> f.getName().startsWith("login-logo."));
        if (existing != null) for (java.io.File f : existing) {
            if (!f.delete() && f.exists()) LOGGER.log(Level.FINE, "Failed to delete old logo {0}", f);
        }
        java.io.File dest = new java.io.File(brandingDir, "login-logo." + ext);
        try { fileItem.write(dest); } catch (Exception e) {
            writeJson(rsp, "{\"ok\":false,\"msg\":\"Failed to save file: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }
        RotogateAuditLog.get().logConfigChanged(Jenkins.getAuthentication2().getName(),
                "Login branding", "login logo uploaded (" + ext + ")");
        writeJson(rsp, "{\"ok\":true,\"msg\":\"Logo uploaded.\"}");
    }

    @POST
    public void doRemoveLoginLogo(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        java.io.File dir = new java.io.File(Jenkins.get().getRootDir(), "rotogate-branding");
        java.io.File[] files = dir.listFiles(f -> f.getName().startsWith("login-logo."));
        if (files != null) for (java.io.File f : files) {
            if (!f.delete() && f.exists()) LOGGER.log(Level.FINE, "Failed to delete logo {0}", f);
        }
        RotogateAuditLog.get().logConfigChanged(Jenkins.getAuthentication2().getName(),
                "Login branding", "login logo removed");
        writeJson(rsp, "{\"ok\":true}");
    }

    public EntraOAuthConfig getEntraConfig() {
        jenkins.model.Jenkins j = jenkins.model.Jenkins.get();
        if (j.getSecurityRealm() instanceof RotogateSecurityRealm) {
            return ((RotogateSecurityRealm) j.getSecurityRealm()).getEntraConfig();
        }
        return null;
    }

    public String getEntraRedirectUri() {
        String root = jenkins.model.Jenkins.get().getRootUrl();
        if (root == null) return "(Jenkins root URL not configured)";
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "/rotogate/finishLogin";
    }

    // -------------------------------------------------------------------------
    // Internal Jenkins users that must never appear in plugin user lists
    // -------------------------------------------------------------------------

    // Well-known Jenkins internal/virtual users that must never appear in plugin user lists.
    // SYSTEM  - Jenkins itself (runs internal tasks, scheduled jobs)
    // anonymous - unauthenticated visitors
    private static final java.util.Set<String> INTERNAL_USERS = new java.util.HashSet<>(
            java.util.Arrays.asList("SYSTEM", "anonymous"));

    private static boolean isInternalUser(User u) {
        return INTERNAL_USERS.contains(u.getId());
    }

    /** Returns true if the user has global Administer access via any source (Rotogate or Jenkins-native). */
    public boolean isUserGlobalAdmin(String sid) {
        if (sid == null || sid.isEmpty()) return false;
        try {
            User u = User.getById(sid, false);
            if (u == null) return false;
            Authentication auth = u.impersonate2();
            try (ACLContext ignored = ACL.as2(auth)) {
                return Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            }
        } catch (RuntimeException ignored) {}
        return false;
    }


    // -------------------------------------------------------------------------
    // Overview stats (used by index.jelly)
    // -------------------------------------------------------------------------

    public int getActiveSessionCount()  { return ActiveSessionManager.getAll().size(); }

    // Break Glass live state - reads from current HTTP session
    public boolean isBreakGlassActive() {
        try {
            org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
            if (req == null) return false;
            jakarta.servlet.http.HttpSession sess = req.getSession(false);
            if (sess == null) return false;
            // Stored as epoch-millis Long by doBreakGlassActivate (and read as Long by the page
            // decorator) - reading it as String threw ClassCastException, which the catch swallowed,
            // so this status always reported inactive even during an active override.
            Long expiry = (Long) sess.getAttribute("rotogate.breakGlass.expiry");
            return expiry != null && System.currentTimeMillis() < expiry;
        } catch (Exception e) { return false; }
    }

    public String getBreakGlassActivatedBy() {
        try {
            org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
            if (req == null) return null;
            jakarta.servlet.http.HttpSession sess = req.getSession(false);
            return sess != null ? (String) sess.getAttribute("rotogate.breakGlass.user") : null;
        } catch (Exception e) { return null; }
    }

    public String getBreakGlassExpiresAt() {
        try {
            org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
            if (req == null) return null;
            jakarta.servlet.http.HttpSession sess = req.getSession(false);
            Long expiry = sess != null ? (Long) sess.getAttribute("rotogate.breakGlass.expiry") : null;
            return expiry != null ? java.time.Instant.ofEpochMilli(expiry).toString() : null;
        } catch (Exception e) { return null; }
    }

    // 7-day login failure trend for chart
    public String getLoginFailureTrendJson() {
        RotogateAuditLog log = RotogateAuditLog.get();
        int[] counts = (log != null) ? log.getFailureCountsByDay(7) : new int[7];
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < counts.length; i++) { if (i > 0) sb.append(","); sb.append(counts[i]); }
        return sb.append("]").toString();
    }

    // Recent audit events for overview feed
    public List<java.util.Map<String, String>> getRecentAuditEntries() {
        RotogateAuditLog log = RotogateAuditLog.get();
        return log != null ? log.readRecent(8) : java.util.Collections.emptyList();
    }

    // Count of admins who have at least one Break Glass TOTP device enrolled
    public int getBreakGlassEnrolledAdminCount() {
        int count = 0;
        for (User user : User.getAll()) {
            RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
            if (prop != null && prop.isBreakGlassTotpEnrolled()) count++;
        }
        return count;
    }

    // Users flagged for deletion
    public int getPendingReviewCount() {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        if (cfg == null || !cfg.isAccessReviewEnabled()) return 0;
        RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
        if (ac == null) return 0;
        int threshold = cfg.getAccessReviewThresholdDays();
        int count = 0;
        for (RotogateAssignment a : ac.getAssignments()) {
            if (a.isReviewDue(threshold)) count++;
        }
        return count;
    }

    public List<PendingReviewItem> getPendingReviewItems() {
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        if (cfg == null || !cfg.isAccessReviewEnabled()) return Collections.emptyList();
        RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
        if (ac == null) return Collections.emptyList();
        int threshold = cfg.getAccessReviewThresholdDays();
        List<PendingReviewItem> items = new ArrayList<>();
        for (RotogateAssignment a : ac.getAssignments()) {
            if (!a.isReviewDue(threshold)) continue;
            String displayName = resolveDisplayName(a.getUserId(),
                    "GROUP".equalsIgnoreCase(a.getAuthType()) ? AuthorizationType.GROUP : AuthorizationType.USER);
            String baseline = (a.getReviewedAt() != null && !a.getReviewedAt().isBlank())
                    ? a.getReviewedAt()
                    : (a.getGrantedAt() != null && !a.getGrantedAt().isBlank() ? a.getGrantedAt() : null);
            long daysAgo = -1; // -1 = unknown grant date
            if (baseline != null) {
                try { daysAgo = java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.parse(baseline), java.time.Instant.now()); } catch (Exception ignored) {}
            }
            String scope = a.getScope();
            String displayScope = (scope == null || scope.isBlank()) ? "Global" : scope;
            items.add(new PendingReviewItem(a.getUserId(), displayName, a.getAuthType(), a.getRoleId(),
                    scope, displayScope, a.getScopeType(), (int) daysAgo,
                    a.getReviewedAt() != null && !a.getReviewedAt().isBlank()));
        }
        items.sort((x, y) -> Integer.compare(y.getDaysAgo(), x.getDaysAgo()));
        return items;
    }

    // ── JIT: getters ────────────────────────────────────────────────────────

    public int getPendingJitCount() {
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        return store == null ? 0 : store.getPendingCount();
    }

    public List<RotogateJitRequest> getPendingJitRequests() {
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        return store == null ? Collections.emptyList() : store.getPendingRequests();
    }

    public List<RotogateJitRequest> getRecentJitRequests() {
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        return store == null ? Collections.emptyList() : store.getRecentRequests(50);
    }

    // ── Provisioning portal: admin queue ──────────────────────────────────────

    public List<ProvisioningRequest> getPendingProvisioningRequests() {
        ProvisioningRequestConfig s = ProvisioningRequestConfig.get();
        return s == null ? Collections.emptyList() : s.getPendingRequests();
    }

    public List<ProvisioningRequest> getRecentProvisioningRequests() {
        ProvisioningRequestConfig s = ProvisioningRequestConfig.get();
        return s == null ? Collections.emptyList() : s.getRecentRequests(50);
    }

    public int getPendingProvisioningCount() {
        ProvisioningRequestConfig s = ProvisioningRequestConfig.get();
        return s == null ? 0 : s.getPendingCount();
    }

    /** True when the native-intake circuit breaker is currently tripped. */
    public boolean isProvisioningBreakerTripped() { return ProvisioningBreaker.isTripped(); }

    /** Count of provisioning requests in a given status (for the queue's summary tiles). */
    public long getProvisioningCountByStatus(String status) {
        ProvisioningRequestConfig s = ProvisioningRequestConfig.get();
        if (s == null || status == null) return 0;
        return s.getAllRequests().stream().filter(r -> status.equals(r.getStatus())).count();
    }

    /** One-time read of the temp password stashed by the last native approval, for the flash. */
    public String getProvisioningTempPassword() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return null;
        Object pwd = req.getSession().getAttribute("rotogate.provTmpPwd");
        if (pwd == null) return null;
        req.getSession().removeAttribute("rotogate.provTmpPwd");
        return pwd.toString();
    }

    /** Approve a pending request - create the account (mirroring doAddUser) and mark it approved. */
    @POST
    public void doApproveProvisioning(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String requestId = req.getParameter("requestId");
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        ProvisioningRequest r = store != null ? store.findById(requestId) : null;
        if (r == null || !r.isPending()) { rsp.sendRedirect("provisioning?error=notFound"); return; }

        String admin = Jenkins.getAuthentication2().getName();

        // Never clobber an existing account - reject the (now redundant) request instead.
        if (User.getById(r.getUserId(), false) != null) {
            store.markRejected(requestId, admin, "An account with this id already exists");
            rsp.sendRedirect("provisioning?error=exists"); return;
        }

        String tmpPwd = createAccountFromRequest(r);
        store.markApproved(requestId, admin, null);

        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logUserCreated(admin, r.getUserId());
        LOGGER.log(Level.INFO, "Provisioning: approved {0} request for {1} ({2})",
                new Object[]{ r.getRequestType(), r.getUserId(), r.durationLabel() });

        // Optional approve-with-a-role: if the admin picked a role, grant it globally. The account
        // already has global Read (grantGlobalRead in createAccountFromRequest); this layers the
        // role's permissions on top. Blank/NONE keeps today's sign-in-only behaviour. Scoped grants
        // stay on the user-detail page.
        String grantRole = req.getParameter("grantRole");
        String grantedRole = null;
        if (grantRole != null && !grantRole.isBlank() && !"NONE".equalsIgnoreCase(grantRole)) {
            RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
            RotogateRoleConfig.RoleDefinition roleDef = roleConfig != null ? roleConfig.findRole(grantRole) : null;
            if (roleDef != null && !roleDef.getPermissionIds().isEmpty()) {
                grantGlobalRole(r.getUserId(), AuthorizationType.USER, new HashSet<>(roleDef.getPermissionIds()));
                grantedRole = roleDef.getName();
                if (audit != null) audit.logGrant(admin, r.getUserId(), grantedRole, "", null);
                LOGGER.log(Level.INFO, "Provisioning: granted role {0} (global) to {1}",
                        new Object[]{ grantedRole, r.getUserId() });
            }
        }

        // Notify the requester of the decision. Native accounts get a one-time activation link
        // (so we never email a password); Entra accounts just sign in with Microsoft.
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        if (r.isNative()) {
            User u = User.getById(r.getUserId(), false);
            String link = (u != null) ? issueActivationLink(u, cfg) : null;
            if (link != null) {
                NotificationService.sendProvisioningApprovedNative(cfg, r, link,
                        cfg != null ? cfg.getActivationLinkTtlHours() : 72);
            }
        } else {
            NotificationService.sendProvisioningApprovedEntra(cfg, r);
        }

        // Stash the one-time temp password for display (NATIVE only) as an email-failure fallback -
        // the admin can convey it manually if delivery fails.
        if (tmpPwd != null) {
            req.getSession().setAttribute("rotogate.provTmpPwd", tmpPwd);
        }
        rsp.sendRedirect("provisioning?approved=" + enc(r.getUserId())
                + (grantedRole != null ? "&role=" + enc(grantedRole) : ""));
    }

    /** Reject a pending request with an optional reason. */
    @POST
    public void doRejectProvisioning(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String requestId = req.getParameter("requestId");
        String comment   = req.getParameter("comment");
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        ProvisioningRequest r = (store != null) ? store.findById(requestId) : null;
        if (store == null || !store.markRejected(requestId, Jenkins.getAuthentication2().getName(), comment)) {
            rsp.sendRedirect("provisioning?error=notFound"); return;
        }
        if (r != null) NotificationService.sendProvisioningRejected(RotogateGlobalConfig.get(), r, comment);
        RotogateAuditLog.get().logProvisioningRejected(Jenkins.getAuthentication2().getName(),
                r != null ? r.getUserId() : requestId);
        rsp.sendRedirect("provisioning?rejected=true");
    }

    /** True when a native provisioned account still owes its first password set (link not yet used). */
    public boolean isAwaitingActivation(String userId) {
        return userId != null && User.getById(userId, false) != null
                && RotogateSecurityRealm.isForcePasswordChange(userId);
    }

    /** Re-issue + re-email the one-time activation link for a native account that hasn't activated yet. */
    @POST
    public void doResendActivation(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String requestId = req.getParameter("requestId");
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        ProvisioningRequest r = (store != null) ? store.findById(requestId) : null;
        if (r == null || !r.isNative()) { rsp.sendRedirect("provisioning?error=notFound"); return; }
        User u = User.getById(r.getUserId(), false);
        // Only meaningful while the account still owes its first password set.
        if (u == null || !RotogateSecurityRealm.isForcePasswordChange(r.getUserId())) {
            rsp.sendRedirect("provisioning?error=activated"); return;
        }
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        String link = issueActivationLink(u, cfg);
        if (link != null) {
            NotificationService.sendProvisioningApprovedNative(cfg, r, link,
                    cfg != null ? cfg.getActivationLinkTtlHours() : 72);
        }
        RotogateAuditLog.get().logActivationResent(Jenkins.getAuthentication2().getName(), r.getUserId());
        rsp.sendRedirect("provisioning?resent=" + enc(r.getUserId()));
    }

    /** Admin control: clear the native-intake circuit breaker cooldown. */
    @POST
    public void doResetProvisioningBreaker(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ProvisioningBreaker.reset();
        RotogateAuditLog.get().logConfigChanged(Jenkins.getAuthentication2().getName(),
                "Provisioning", "intake circuit breaker reset");
        rsp.sendRedirect("provisioning?breakerReset=true");
    }

    /** Save the provisioning-portal settings from the Access Requests page. */
    @POST
    public void doSaveProvisioningSettings(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RotogateGlobalConfig c = RotogateGlobalConfig.get();
        if (c != null) {
            c.getProvisioning().updateFrom(req);
            c.save();
            RotogateAuditLog.get().logConfigChanged(currentUserId(), "Provisioning",
                    "provisioning intake settings saved");
        }
        rsp.sendRedirect("provisioning?settingsSaved=true");
    }

    private static int parseIntOr(String v, int fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    /**
     * Issue a fresh one-time activation link for a native account: generate a token, store its
     * hash + expiry on the user, and return the full click URL. Returns null if no root URL.
     */
    private String issueActivationLink(User user, RotogateGlobalConfig cfg) throws java.io.IOException {
        String root = Jenkins.get().getRootUrl();
        if (root == null || root.isEmpty()) return null;
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        int ttlHours = (cfg != null) ? cfg.getActivationLinkTtlHours() : 72;
        String raw = RotogateActivationProperty.newRawToken();
        long expiresAt = System.currentTimeMillis() + ttlHours * 3600_000L;
        user.addProperty(new RotogateActivationProperty(RotogateActivationProperty.hash(raw), expiresAt));
        return root + "/rotogate/activate?u=" + enc(user.getId()) + "&t=" + enc(raw);
    }

    /**
     * Create the Jenkins account described by an approved request, mirroring doAddUser.
     * NATIVE → generate + return a temp password (account must change it on first login);
     * ENTRA → pre-provision the user with no password. Returns the temp password, or null.
     */
    private String createAccountFromRequest(ProvisioningRequest r) throws Exception {
        String sid = r.getUserId();
        if (r.isNative()) {
            hudson.security.SecurityRealm realm = Jenkins.get().getSecurityRealm();
            if (!(realm instanceof hudson.security.HudsonPrivateSecurityRealm)) {
                throw new IllegalStateException("Native account creation requires HudsonPrivateSecurityRealm");
            }
            String tmpPwd = generateTempPassword();
            ((hudson.security.HudsonPrivateSecurityRealm) realm).createAccount(sid, tmpPwd);
            User user = User.getById(sid, false);
            if (user != null) {
                if (!r.getFullName().isEmpty()) user.setFullName(r.getFullName());
                applyEmail(user, r.getEmail());
                user.addProperty(new RotogateForcePasswordProperty(true));
                applyAccountExpiry(user, r);
                user.save();
            }
            grantGlobalRead(sid, AuthorizationType.USER);
            return tmpPwd;
        } else {
            User user = User.getOrCreateByIdOrFullName(sid);
            user.setFullName(!r.getFullName().isEmpty() ? r.getFullName() : sid);
            RotogateUserProperty prop = new RotogateUserProperty(r.getOid().isEmpty() ? null : r.getOid(), sid);
            prop.setProvisioningSource("INDIVIDUAL");
            user.addProperty(prop);
            applyEmail(user, r.getEmail());
            applyAccountExpiry(user, r);
            user.save();
            grantGlobalRead(sid, AuthorizationType.USER);
            return null;
        }
    }

    /** Attach a time-limited expiry to a provisioned account when the request had a duration. */
    private void applyAccountExpiry(User user, ProvisioningRequest r) throws java.io.IOException {
        if (r.getRequestedDurationDays() <= 0) return; // 0 = permanent
        String expiresAt = java.time.Instant.now()
                .plus(r.getRequestedDurationDays(), java.time.temporal.ChronoUnit.DAYS).toString();
        user.addProperty(new RotogateAccountExpiryProperty(expiresAt));
    }

    /** Attach a Mailer email property, same reflective approach as doAddUser. */
    private void applyEmail(User user, String email) {
        if (email == null || email.trim().isEmpty()) return;
        try {
            ClassLoader uberCl = Jenkins.get().getPluginManager().uberClassLoader;
            Class<?> mailerPropClass = Class.forName("hudson.tasks.Mailer$UserProperty", true, uberCl);
            java.lang.reflect.Constructor<?> ctor = mailerPropClass.getConstructor(String.class);
            hudson.model.UserProperty prop = (hudson.model.UserProperty) ctor.newInstance(email.trim());
            user.addProperty(prop);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not set email for provisioned user " + user.getId(), e);
        }
    }

    // ── JIT: admin actions ───────────────────────────────────────────────────

    @POST
    public void doApproveJit(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String requestId = req.getParameter("requestId");
        if (requestId == null || requestId.isBlank()) { rsp.sendRedirect("jitRequests"); return; }
        String approver = Jenkins.getAuthentication2().getName();
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        RotogateJitRequest jitReq = store != null ? store.findById(requestId) : null;
        if (jitReq == null) { rsp.sendRedirect("jitRequests"); return; }

        int durationHours = parseIntOr(req.getParameter("durationHours"), 0);
        boolean wentActive = store.approveAsApprover(requestId, approver, durationHours);
        RotogateAuditLog audit = RotogateAuditLog.get();
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        if (wentActive) {
            if (audit != null) audit.logJitApproved(approver, jitReq.getRequesterId(),
                    jitReq.getScope(), jitReq.effectiveGrantedDurationHours());
            NotificationService.sendJitApproved(cfg, jitReq);
            rsp.sendRedirect("jitRequests?approved=true");
        } else {
            rsp.sendRedirect("jitRequests?partialApproved=true");
        }
    }

    @POST
    public void doDenyJit(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String requestId = req.getParameter("requestId");
        String comment   = req.getParameter("comment");
        if (requestId == null || requestId.isBlank()) { rsp.sendRedirect("jitRequests"); return; }
        String approver = Jenkins.getAuthentication2().getName();
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        RotogateJitRequest jitReq = store != null ? store.findById(requestId) : null;
        if (jitReq == null) { rsp.sendRedirect("jitRequests"); return; }

        if (store.denyAsApprover(requestId, approver, comment)) {
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logJitDenied(approver, jitReq.getRequesterId(),
                    jitReq.getScope(), comment);
            RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
            NotificationService.sendJitDenied(cfg, jitReq);
        }
        rsp.sendRedirect("jitRequests?denied=true");
    }

    public String getCurrentUserId() {
        try { return Jenkins.getAuthentication2().getName(); } catch (Exception e) { return ""; }
    }

    @POST
    public void doRevokeActiveJit(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String requestId = req.getParameter("requestId");
        if (requestId == null || requestId.isBlank()) { rsp.sendRedirect("jitRequests"); return; }
        String revokedBy = Jenkins.getAuthentication2().getName();
        RotogateJitRequestStore store = RotogateJitRequestStore.get();
        RotogateJitRequest jitReq = store != null ? store.findById(requestId) : null;
        if (jitReq != null && store.revoke(requestId, revokedBy)) {
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logJitRevoked(revokedBy, jitReq.getRequesterId(), jitReq.getScope());
        }
        rsp.sendRedirect("jitRequests?revoked=true");
    }

    public int getPendingDeletionCount() {
        int count = 0;
        for (User user : User.getAll()) {
            RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
            if (prop != null && prop.isPendingDeletion()) count++;
        }
        return count;
    }

    // VIA_ENTRA_GROUP users whose group is no longer in Access Management (not yet flagged for deletion)
    public int getOrphanedGroupAccountCount() {
        int count = 0;
        for (User user : User.getAll()) {
            RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
            if (prop == null || !prop.isViaGroup() || prop.isPendingDeletion()) continue;
            if (prop.getActiveGroupOids().isEmpty()) count++;
        }
        return count;
    }

    // Names of notification channels that are enabled but whose last send failed
    public List<String> getFailingNotificationChannels() {
        NotificationLog log = NotificationLog.get();
        if (log == null) return java.util.Collections.emptyList();
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        if (cfg == null) return java.util.Collections.emptyList();
        List<String> failing = new ArrayList<>();
        if (cfg.isSmtpEnabled())  { var e = log.lastSmtpEntry();  if (e != null && !e.isSuccess()) failing.add("Email"); }
        if (cfg.isSlackEnabled()) { var e = log.lastSlackEntry(); if (e != null && !e.isSuccess()) failing.add("Slack"); }
        if (cfg.isTeamsEnabled()) { var e = log.lastTeamsEntry(); if (e != null && !e.isSuccess()) failing.add("Teams"); }
        return failing;
    }

    // Users with a global ADMIN role assignment but no TOTP enrolled
    public int getUnenrolledAdminCount() {
        RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
        if (ac == null) return 0;
        java.util.Set<String> adminUserIds = new java.util.HashSet<>();
        for (RotogateAssignment a : ac.getAssignments()) {
            if ("USER".equals(a.getAuthType())
                    && (a.getScope() == null || a.getScope().isEmpty())
                    && "ADMIN".equalsIgnoreCase(a.getRoleId())) {
                adminUserIds.add(a.getUserId());
            }
        }
        int count = 0;
        for (String uid : adminUserIds) {
            User user = User.getById(uid, false);
            if (user == null) continue;
            RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
            if (prop == null || !prop.isBreakGlassTotpEnrolled()) count++;
        }
        return count;
    }

    /** Admins with a global ADMIN assignment who hold no <em>usable</em> SOS recovery codes. */
    public int getSosUnprotectedAdminCount() {
        RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
        if (ac == null) return 0;
        java.util.Set<String> adminUserIds = new java.util.HashSet<>();
        for (RotogateAssignment a : ac.getAssignments()) {
            if ("USER".equals(a.getAuthType())
                    && (a.getScope() == null || a.getScope().isEmpty())
                    && "ADMIN".equalsIgnoreCase(a.getRoleId())) {
                adminUserIds.add(a.getUserId());
            }
        }
        int count = 0;
        for (String uid : adminUserIds) {
            User user = User.getById(uid, false);
            if (user == null) continue;
            RotogateRecoveryCodesProperty prop = user.getProperty(RotogateRecoveryCodesProperty.class);
            if (prop == null || prop.getUnusedCount() == 0) count++;
        }
        return count;
    }


    public List<ExpiringGrant> getExpiringGrants(int withinDays) {
        RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
        if (ac == null) return java.util.Collections.emptyList();
        java.time.Instant now  = java.time.Instant.now();
        java.time.Instant cutoff = now.plus(withinDays, ChronoUnit.DAYS);
        List<ExpiringGrant> result = new ArrayList<>();
        for (RotogateAssignment a : ac.getAssignments()) {
            if (a.getExpiresAt() == null || a.getExpiresAt().isBlank()) continue;
            try {
                java.time.Instant exp = java.time.Instant.parse(a.getExpiresAt());
                if (exp.isAfter(now) && exp.isBefore(cutoff)) {
                    long days = ChronoUnit.DAYS.between(now, exp);
                    result.add(new ExpiringGrant(a.getUserId(), a.getRoleId(), a.getScope(), a.getExpiresAt(), days));
                }
            } catch (Exception ignored) {}
        }
        result.sort((a, b) -> Long.compare(a.daysLeft, b.daysLeft));
        return result;
    }

    public int getFailedLoginsLast24h() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        int count = 0;
        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            LoginHistoryProperty hp = user.getProperty(LoginHistoryProperty.class);
            if (hp == null) continue;
            for (LoginEvent e : hp.getEvents()) {
                try {
                    if (!e.isSuccess() && Instant.parse(e.getTimestamp()).isAfter(cutoff)) count++;
                } catch (Exception ignored) {}
            }
        }
        return count;
    }

    /** The security page's selected window in hours (from {@code ?window=}, default 24), for security.jelly. */
    public String getSecurityWindowHours() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String w = (req != null) ? req.getParameter("window") : null;
        if (w != null) {
            try { return String.valueOf(Integer.parseInt(w)); } catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return "24";
    }

    /** Failed logins within the selected window - backs security.jelly without a routing method. */
    public List<FailedLoginEntry> getFailedLoginsForWindow() {
        return getFailedLogins(Integer.parseInt(getSecurityWindowHours()));
    }

    public List<FailedLoginEntry> getFailedLogins(int hours) {
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<FailedLoginEntry> result = new ArrayList<>();
        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            LoginHistoryProperty hp = user.getProperty(LoginHistoryProperty.class);
            if (hp == null) continue;
            for (LoginEvent e : hp.getEvents()) {
                try {
                    if (!e.isSuccess() && Instant.parse(e.getTimestamp()).isAfter(cutoff)) {
                        result.add(new FailedLoginEntry(user.getId(), user.getFullName(), e));
                    }
                } catch (Exception ignored) {}
            }
        }
        result.sort((a, b) -> b.event.getTimestamp().compareTo(a.event.getTimestamp()));
        return result;
    }

    public List<BruteForceEntry> getBruteForceEntries() {
        Map<String, Integer> live    = BruteForceTracker.getAllFailureCounts();
        Map<String, String>  alerted = BruteForceTracker.getAlertedUsers();
        java.util.Set<String> locked = BruteForceTracker.getLockedUsers();
        Map<String, BruteForceEntry> merged = new HashMap<>();

        for (Map.Entry<String, Integer> e : live.entrySet()) {
            String username = e.getKey();
            User user = User.getById(username, false);
            String fullName = (user != null) ? user.getFullName() : username;
            merged.put(username, new BruteForceEntry(username, fullName, e.getValue(), null));
        }
        for (Map.Entry<String, String> e : alerted.entrySet()) {
            String username = e.getKey();
            if (!merged.containsKey(username)) {
                User user = User.getById(username, false);
                String fullName = (user != null) ? user.getFullName() : username;
                merged.put(username, new BruteForceEntry(username, fullName, 0, e.getValue()));
            } else {
                BruteForceEntry existing = merged.get(username);
                merged.put(username, new BruteForceEntry(existing.userId, existing.fullName, existing.failureCount, e.getValue()));
            }
        }
        // Ensure any currently-locked user is present, and stamp lock state + remaining time.
        for (String username : locked) {
            BruteForceEntry existing = merged.get(username);
            if (existing == null) {
                User user = User.getById(username, false);
                String fullName = (user != null) ? user.getFullName() : username;
                existing = new BruteForceEntry(username, fullName, 0, null);
            }
            existing.locked = true;
            existing.lockRemainingMinutes = BruteForceTracker.lockRemainingMinutes(username);
            merged.put(username, existing);
        }
        List<BruteForceEntry> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Integer.compare(b.failureCount, a.failureCount));
        return result;
    }



    public List<RecentLoginEntry> getRecentLoginEvents() {
        List<RecentLoginEntry> all = new ArrayList<>();
        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            LoginHistoryProperty hp = user.getProperty(LoginHistoryProperty.class);
            if (hp == null) continue;
            for (LoginEvent e : hp.getEvents()) {
                all.add(new RecentLoginEntry(user.getId(), user.getFullName(), e));
            }
        }
        all.sort((a, b) -> b.event.getTimestamp().compareTo(a.event.getTimestamp()));
        return all.size() > 7 ? all.subList(0, 7) : all;
    }


    // ── Overview dashboard: login-activity chart + live metrics ─────────────────



    /** Builds the 14-day login-activity series (oldest-first) from the audit log in a single pass. */
    public LoginActivity getLoginActivity() {
        final int days = 14;
        RotogateAuditLog log = RotogateAuditLog.get();
        int[][] series = (log != null) ? log.getLoginSeriesByDay(days) : new int[days][3];
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        List<DayActivity> list = new ArrayList<>(days);
        int max = 1;
        for (int i = 0; i < days; i++) {
            java.time.LocalDate d = today.minusDays(days - 1 - i);
            int sso = series[i][0], nat = series[i][1], fail = series[i][2];
            list.add(new DayActivity(d.toString(), sso, nat, fail));
            // Scale against the taller of the (stacked) success total and the failure count.
            max = Math.max(max, Math.max(sso + nat, fail));
        }
        return new LoginActivity(list, max);
    }

    /**
     * Live metrics for the Overview page's auto-refresh. Returns the volatile KPI counts as JSON so
     * the dashboard can update in place every few seconds without a full reload. Read-only.
     */
    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doOverviewMetrics(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        net.sf.json.JSONObject out = new net.sf.json.JSONObject();
        out.put("total",       getTotalUserCount());
        out.put("active",      getActiveUserCount());
        out.put("stale",       getStaleUserCount());
        out.put("protected",   getProtectedUserCount());
        out.put("sso",         getEntraUserCount());
        out.put("native",      getLegacyUserCount());
        out.put("sessions",    getActiveSessionCount());
        out.put("failed24h",   getFailedLoginsLast24h());
        out.put("pendingJit",  getPendingJitCount());
        out.put("ts",          java.time.Instant.now().toString());
        writeJson(rsp, out);
    }

    public int getTotalUserCount()     { return (int) User.getAll().stream().filter(u -> !isInternalUser(u)).count(); }
    public int getEntraUserCount()     { return (int) User.getAll().stream().filter(u -> !isInternalUser(u) && isEntraIdentity(u.getProperty(RotogateUserProperty.class))).count(); }
    public int getLegacyUserCount()    { return getTotalUserCount() - getEntraUserCount(); }

    /**
     * True only when the property carries a real Entra identity (an object id). {@link RotogateUserProperty}
     * is also attached to <em>native</em> users purely to hold Break Glass enrollment, so its mere presence
     * must not be read as "this is an Entra user" - otherwise a native account (e.g. admin) that enrolled a
     * Break Glass device would be mislabelled "Entra · Individual".
     */
    static boolean isEntraIdentity(RotogateUserProperty p) {
        return p != null && p.hasEntraIdentity();
    }
    public int getStaleUserCount()     { return getStaleUsers(staleThresholdDays()).size(); }
    public int getProtectedUserCount() { RotogateGlobalConfig c = RotogateGlobalConfig.get(); return c == null ? 0 : c.getProtectedUsers().size(); }

    /** Returns true if this user is VIA_ENTRA_GROUP and at least one of their groups is still active in Access Management. */
    public boolean isActiveGroupUser(String userId) {
        User user = User.getById(userId, false);
        if (user == null) return false;
        RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
        if (prop == null || !prop.isViaGroup()) return false;
        RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
        if (assignmentConfig == null) return false;
        for (String oid : prop.getActiveGroupOids()) {
            if (assignmentConfig.hasGroup(oid)) return true;
        }
        return false;
    }
    public int getActiveUserCount() {
        int threshold = activeThresholdDays();
        Instant cutoff = Instant.now().minus(threshold, ChronoUnit.DAYS);
        int count = 0;
        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            RotogateUserProperty entraProp = user.getProperty(RotogateUserProperty.class);
            LastLoginProperty    loginProp  = user.getProperty(LastLoginProperty.class);
            String lastLogin = resolveLastLogin(entraProp, loginProp);
            if (lastLogin != null && Instant.parse(lastLogin).isAfter(cutoff)) count++;
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // User Status list (used by userStatus.jelly)
    // -------------------------------------------------------------------------

    public List<UserStatusInfo> getUserStatusList() {
        Map<String, LastJobInfo> lastJobMap = buildLastJobMap();
        List<UserStatusInfo> result = new ArrayList<>();

        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            RotogateUserProperty  entraProp = user.getProperty(RotogateUserProperty.class);
            LastLoginProperty     loginProp = user.getProperty(LastLoginProperty.class);
            LoginHistoryProperty  histProp  = user.getProperty(LoginHistoryProperty.class);

            boolean isEntra  = isEntraIdentity(entraProp);
            String userType  = isEntra ? "Entra" : "Native";
            String lastLogin = resolveLastLogin(entraProp, loginProp);
            LastJobInfo lastJob = lastJobMap.get(user.getId());
            String status = deriveStatus(lastLogin, lastJob);

            UserStatusInfo info = new UserStatusInfo(
                    user.getId(),
                    user.getFullName(),
                    userType,
                    lastLogin,
                    lastJob != null ? lastJob.jobName    : null,
                    lastJob != null ? lastJob.triggeredAt : null,
                    status
            );
            if (histProp != null) info.setLatestEvent(histProp.getLatestEvent());

            // Time-limited account expiry (provisioning portal duration) - surface it if present.
            RotogateAccountExpiryProperty expProp = user.getProperty(RotogateAccountExpiryProperty.class);
            if (expProp != null && expProp.getExpiresAt() != null && !expProp.getExpiresAt().isBlank()) {
                info.setAccountExpiresAt(expProp.getExpiresAt());
                info.setAccountDisabledByExpiry(expProp.isDisabled());
            }

            // Extended fields - only for real Entra identities (not break-glass-only natives).
            if (isEntra) {
                info.setProvisioningSource(entraProp.getProvisioningSource());
                info.setEntraUpn(entraProp.getEntraUpn());
                if (entraProp.isViaGroup()) {
                    RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
                    boolean foundActive = false;
                    if (assignmentConfig != null) {
                        for (String oid : entraProp.getActiveGroupOids()) {
                            if (assignmentConfig.hasGroup(oid)) {
                                info.setActiveGroupUser(true);
                                info.setGroupOid(oid);
                                RotogateGroupEntity groupEntity = assignmentConfig.findGroup(oid);
                                if (groupEntity != null) info.setGroupName(groupEntity.getEffectiveName());
                                foundActive = true;
                                break;
                            }
                        }
                    }
                    // Orphaned - fall back to last known group name/OID saved at orphan time
                    if (!foundActive) {
                        info.setGroupName(entraProp.getLastKnownGroupName());
                        info.setGroupOid(entraProp.getLastKnownGroupOid());
                    }
                }
            } else {
                info.setProvisioningSource("NATIVE");
            }

            // Stale warning - would this user be flagged by cleanup?
            Instant staleCutoff = Instant.now().minus(staleThresholdDays(), ChronoUnit.DAYS);
            boolean isStale = (lastLogin == null) || Instant.parse(lastLogin).isBefore(staleCutoff);
            info.setStaleWarning(isStale);

            // Pending deletion flag
            if (entraProp != null) info.setPendingDeletion(entraProp.isPendingDeletion());

            result.add(info);
        }

        // Sort: active first, then by last login descending
        result.sort((a, b) -> {
            int sa = statusOrder(a.getStatus());
            int sb = statusOrder(b.getStatus());
            if (sa != sb) return Integer.compare(sa, sb);
            if (a.getLastLoginAt() == null && b.getLastLoginAt() == null) return 0;
            if (a.getLastLoginAt() == null) return 1;
            if (b.getLastLoginAt() == null) return -1;
            return b.getLastLoginAt().compareTo(a.getLastLoginAt()); // newest first
        });

        return result;
    }

    // -------------------------------------------------------------------------
    // Stale Users list (used by staleUsers.jelly)
    // -------------------------------------------------------------------------

    public List<UserInfo> getStaleUsers(int thresholdDays) {
        Instant cutoff = Instant.now().minus(thresholdDays, ChronoUnit.DAYS);
        List<UserInfo> result = new ArrayList<>();

        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            RotogateUserProperty entraProp = user.getProperty(RotogateUserProperty.class);
            LastLoginProperty    loginProp = user.getProperty(LastLoginProperty.class);

            // Exclude pending deletion accounts - shown in their own section
            if (entraProp != null && entraProp.isPendingDeletion()) continue;

            String lastLogin = resolveLastLogin(entraProp, loginProp);
            boolean isStale  = (lastLogin == null) || Instant.parse(lastLogin).isBefore(cutoff);
            if (!isStale) continue;

            result.add(new UserInfo(
                    user.getId(),
                    user.getFullName(),
                    isEntraIdentity(entraProp) ? "Entra" : "Legacy",
                    lastLogin,
                    entraProp != null ? entraProp.getEntraObjectId() : null
            ));
        }

        result.sort((a, b) -> {
            if (a.getLastLoginAt() == null) return -1;
            if (b.getLastLoginAt() == null) return 1;
            return a.getLastLoginAt().compareTo(b.getLastLoginAt()); // oldest first
        });

        return result;
    }

    public List<UserInfo> getPendingDeletionUsers() {
        List<UserInfo> result = new ArrayList<>();

        for (User user : User.getAll()) {
            if (isInternalUser(user)) continue;
            RotogateUserProperty entraProp = user.getProperty(RotogateUserProperty.class);
            if (entraProp == null || !entraProp.isPendingDeletion()) continue;

            LastLoginProperty loginProp = user.getProperty(LastLoginProperty.class);
            String lastLogin = resolveLastLogin(entraProp, loginProp);

            UserInfo info = new UserInfo(
                    user.getId(),
                    user.getFullName(),
                    isEntraIdentity(entraProp) ? "Entra" : "Legacy",
                    lastLogin,
                    entraProp.getEntraObjectId()
            );

            // Determine reason: orphaned group account vs manually marked
            boolean isOrphanedGroup = entraProp.isViaGroup() && entraProp.getActiveGroupOids().isEmpty();
            info.setPendingReason(isOrphanedGroup ? "Group access revoked" : "Manually marked");
            result.add(info);
        }

        result.sort((a, b) -> {
            if (a.getLastLoginAt() == null) return -1;
            if (b.getLastLoginAt() == null) return 1;
            return a.getLastLoginAt().compareTo(b.getLastLoginAt());
        });

        return result;
    }

    // -------------------------------------------------------------------------
    // Access detail (used by userOverview.jelly)
    // -------------------------------------------------------------------------

    public AccessInfo getAccessInfo() {
        // Read the current request directly (same pattern as getUserDetailHeader/getTempPassword). The
        // jelly's implicit `request` does not bind to a StaplerRequest2 parameter after the Stapler2
        // migration, which silently returned null here → "User not found" for every user.
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return null;
        String userId = req.getParameter("userId");
        if (userId == null || userId.isEmpty()) return null;

        User user = User.getById(userId, false);
        if (user == null) return null;

        RotogateUserProperty entraProp = user.getProperty(RotogateUserProperty.class);
        boolean isEntra = isEntraIdentity(entraProp);
        String userType = isEntra ? "Entra" : "Legacy";

        // Check key permissions using ACL.as2() so the thread-local auth context is set correctly
        boolean isAdmin      = false;
        boolean canRead      = false;
        boolean canBuild     = false;
        boolean canCreate    = false;
        boolean canConfigure = false;
        try {
            Authentication auth = user.impersonate2();
            try (ACLContext ignored = ACL.as2(auth)) {
                Jenkins j = Jenkins.get();
                isAdmin      = j.hasPermission(Jenkins.ADMINISTER);
                canRead      = j.hasPermission(Jenkins.READ);
                canBuild     = j.hasPermission(Item.BUILD);
                canCreate    = j.hasPermission(Item.CREATE);
                canConfigure = j.hasPermission(Item.CONFIGURE);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not check permissions for user: " + userId, e);
        }

        // Job access - scan all jobs and check per-job permissions via impersonation
        List<JobAccessInfo> jobAccess = buildJobAccessList(userId);

        // Last login
        LastLoginProperty loginProp = user.getProperty(LastLoginProperty.class);
        String lastLogin = resolveLastLogin(entraProp, loginProp);

        // Authorization strategy name - shown in UI to help diagnose per-job permission support
        String authStrategy = Jenkins.get().getAuthorizationStrategy().getClass().getSimpleName();

        // Login history
        LoginHistoryProperty histProp = user.getProperty(LoginHistoryProperty.class);
        List<LoginEvent> loginHistory = histProp != null
                ? new ArrayList<>(histProp.getEvents()) : Collections.emptyList();

        AccessInfo accessInfo = new AccessInfo(
                userId,
                user.getFullName(),
                userType,
                isEntra ? entraProp.getEntraObjectId() : null,
                isEntra ? entraProp.getEntraUpn() : null,
                lastLogin,
                jobAccess,
                authStrategy,
                isAdmin, canRead, canBuild, canCreate, canConfigure,
                loginHistory
        );

        // Provisioning source - only meaningful for real Entra identities.
        if (isEntra) accessInfo.setProvisioningSource(entraProp.getProvisioningSource());

        // Time-limited account expiry (provisioning portal duration) - surface if present.
        RotogateAccountExpiryProperty detailExp = user.getProperty(RotogateAccountExpiryProperty.class);
        if (detailExp != null && detailExp.getExpiresAt() != null && !detailExp.getExpiresAt().isBlank()) {
            accessInfo.setAccountExpiresAt(detailExp.getExpiresAt());
            accessInfo.setAccountDisabledByExpiry(detailExp.isDisabled());
        }

        // Populate group info for VIA_ENTRA_GROUP users
        if (isEntra && entraProp.isViaGroup()) {
            String gName = null, gOid = null;
            RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
            for (String oid : entraProp.getActiveGroupOids()) {
                if (ac != null && ac.hasGroup(oid)) {
                    RotogateGroupEntity ge = ac.findGroup(oid);
                    gName = ge != null ? ge.getEffectiveName() : null;
                    gOid  = oid;
                    break;
                }
            }
            if (gOid == null) {
                gName = entraProp.getLastKnownGroupName();
                gOid  = entraProp.getLastKnownGroupOid();
            }
            accessInfo.setGroupName(gName);
            accessInfo.setGroupOid(gOid);
        }

        // Rotogate grants - all assignments for this user (direct + via groups)
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        RotogateRoleConfig roleConfig   = RotogateRoleConfig.get();
        List<GrantDisplay> rotogateGrants = new ArrayList<>();
        if (config != null) {
            for (RotogateAssignment a : config.getAssignmentsForUser(userId, "USER")) {
                rotogateGrants.add(toGrantDisplay(a, "USER", userId, user.getFullName(), roleConfig));
            }
            if (entraProp != null) {
                for (String gOid : entraProp.getActiveGroupOids()) {
                    String gName = gOid;
                    RotogateGroupEntity ge = config.findGroup(gOid);
                    if (ge != null) gName = ge.getEffectiveName();
                    for (RotogateAssignment a : config.getAssignmentsForUser(gOid, "GROUP")) {
                        rotogateGrants.add(toGrantDisplay(a, "GROUP", gOid, gName, roleConfig));
                    }
                }
            }
        }
        accessInfo.setRotogateGrants(rotogateGrants);

        return accessInfo;
    }

    private static GrantDisplay toGrantDisplay(RotogateAssignment a, String principalType,
                                                String principalId, String principalName,
                                                RotogateRoleConfig roleConfig) {
        String roleId   = a.getRoleId();
        String roleName = roleId;
        if (!"CUSTOM".equalsIgnoreCase(roleId) && roleConfig != null) {
            RotogateRoleConfig.RoleDefinition role = roleConfig.findRole(roleId);
            if (role != null) roleName = role.getName();
        }
        String expiresDisplay = a.getExpiresAt() != null ? formatDate(a.getExpiresAt()) : null;
        return new GrantDisplay(principalType, principalId, principalName,
                                roleId, roleName, a.getScope(), a.getScopeType(),
                                expiresDisplay, a.isExpired());
    }

    // -------------------------------------------------------------------------
    // Access Management (writable permission management)
    // -------------------------------------------------------------------------

    public boolean isRotogateActive() {
        return Jenkins.get().getAuthorizationStrategy() instanceof RotogateAuthorizationStrategy;
    }

    public List<String> getAllJobNames() {
        List<String> names = new ArrayList<>();
        try {
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                names.add(job.getFullName());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to list jobs", e);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Returns folders + jobs as a JSON array of {path, type} objects for the scope picker. */
    public String getAllScopeNamesJson() {
        List<String[]> items = new ArrayList<>();
        try {
            for (AbstractFolder<?> folder : Jenkins.get().getAllItems(AbstractFolder.class)) {
                items.add(new String[]{folder.getFullName(), "folder"});
            }
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                items.add(new String[]{job.getFullName(), "job"});
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to list scope items", e);
        }
        items.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            String path = items.get(i)[0].replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"path\":\"").append(path).append("\",\"type\":\"").append(items.get(i)[1]).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    public List<AccessManagementUserInfo> getAccessManagementUserList() {
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (!(strat instanceof GlobalMatrixAuthorizationStrategy)) return Collections.emptyList();
        GlobalMatrixAuthorizationStrategy matrix = (GlobalMatrixAuthorizationStrategy) strat;

        Map<String, Set<String>> permsByKey = new LinkedHashMap<>();
        Map<String, AuthorizationType> typeByKey = new LinkedHashMap<>();

        for (Map.Entry<Permission, Set<PermissionEntry>> e : matrix.getGrantedPermissionEntries().entrySet()) {
            for (PermissionEntry pe : e.getValue()) {
                String key = pe.getType().name() + ":" + pe.getSid();
                permsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(e.getKey().getId());
                typeByKey.put(key, pe.getType());
            }
        }

        List<AccessManagementUserInfo> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : permsByKey.entrySet()) {
            String key = entry.getKey();
            AuthorizationType atype = typeByKey.get(key);
            String sid = key.substring(key.indexOf(':') + 1);
            String dn = resolveDisplayName(sid, atype);
            RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
            String roleName = roleConfig != null ? roleConfig.matchRole(entry.getValue()) : null;
            if (roleName == null) roleName = "Custom";
            String lastLogin = null;
            String provisioningSource = "NATIVE";
            String acctExpiresAt = null;
            boolean acctDisabledByExpiry = false;
            if (atype == AuthorizationType.USER) {
                User u = User.getById(sid, false);
                if (u != null) {
                    RotogateUserProperty ep = u.getProperty(RotogateUserProperty.class);
                    LastLoginProperty lp = u.getProperty(LastLoginProperty.class);
                    lastLogin = resolveLastLogin(ep, lp);
                    if (isEntraIdentity(ep)) provisioningSource = ep.getProvisioningSource();
                    RotogateAccountExpiryProperty xp = u.getProperty(RotogateAccountExpiryProperty.class);
                    if (xp != null && xp.getExpiresAt() != null && !xp.getExpiresAt().isBlank()) {
                        acctExpiresAt = xp.getExpiresAt();
                        acctDisabledByExpiry = xp.isDisabled();
                    }
                }
            }
            AccessManagementUserInfo userInfo = new AccessManagementUserInfo(sid, dn, atype, roleName, entry.getValue(), lastLogin);
            userInfo.setProvisioningSource(provisioningSource);
            userInfo.setAccountExpiresAt(acctExpiresAt);
            userInfo.setAccountDisabledByExpiry(acctDisabledByExpiry);
            // Check if any scoped Rotogate assignment for this principal is overdue for review
            RotogateGlobalConfig reviewCfg = RotogateGlobalConfig.get();
            if (reviewCfg != null && reviewCfg.isAccessReviewEnabled()) {
                RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
                if (ac != null) {
                    int threshold = reviewCfg.getAccessReviewThresholdDays();
                    String authTypeStr = atype == AuthorizationType.GROUP ? "GROUP" : "USER";
                    boolean due = ac.getAssignmentsForUser(sid, authTypeStr).stream()
                            .anyMatch(a -> a.isReviewDue(threshold));
                    userInfo.setHasReviewDue(due);
                }
            }
            result.add(userInfo);
        }
        // Also include GROUP entities from RotogateAssignmentConfig that may not have matrix entries
        // (e.g. if grantGlobalRead failed during initial add, or strategy was not active yet)
        RotogateAssignmentConfig aConfig = RotogateAssignmentConfig.get();
        if (aConfig != null) {
            for (RotogateGroupEntity groupEntity : aConfig.getGroups()) {
                String gOid = groupEntity.getGroupOid();
                boolean alreadyPresent = result.stream()
                        .anyMatch(r -> r.getType() == AuthorizationType.GROUP && r.getSid().equals(gOid));
                if (!alreadyPresent) {
                    result.add(new AccessManagementUserInfo(gOid, groupEntity.getEffectiveName(),
                            AuthorizationType.GROUP, "—", Collections.emptySet(), null));
                }
            }
        }

        result.sort((a, b) -> a.getSid().compareToIgnoreCase(b.getSid()));
        return result;
    }

    /**
     * The permissions a role/grant can be built from, grouped exactly as Jenkins groups them.
     *
     * We iterate Jenkins' own {@link PermissionGroup}s rather than curating a hand-written list,
     * so every permission appears under its real group (e.g. {@code Item.ExtendedRead} under "Job",
     * {@code LockableResourcesManager.Steal} under "Lockable Resources") instead of being lumped
     * into one "other" bucket, and each permission carries its own name, description (shown as a
     * tooltip) and "implied by" relationship (so the UI can disable a checkbox that a stronger
     * permission already grants). The admin-equivalent "dangerous" permissions (RunScripts,
     * ConfigureUpdateCenter, UploadPlugins) are never offered.
     */
    public List<PermissionGroupInfo> getAvailablePermissions() {
        List<PermissionGroupInfo> result = new ArrayList<>();
        for (PermissionGroup group : PermissionGroup.getAll()) {
            // The internal abstract base permissions (owner == Permission.class) are not user-facing.
            if (group.owner == hudson.security.Permission.class) {
                continue;
            }
            String groupTitle = group.title != null ? group.title.toString() : group.getId();
            List<PermissionInfo> perms = new ArrayList<>();
            for (Permission p : group.getPermissions()) {
                if (!p.enabled) {
                    continue;
                }
                if (RotogateAuthorizationStrategy.isDangerousPermission(p.getId())) {
                    continue; // never offer admin-equivalent permissions
                }
                perms.add(PermissionInfo.of(groupTitle, p));
            }
            if (!perms.isEmpty()) {
                // Permission groups have no description in Jenkins, so there is no subtitle.
                result.add(new PermissionGroupInfo(groupTitle, "", perms));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // POST actions
    // -------------------------------------------------------------------------

    @POST
    public void doRunCleanupNow(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RotogateGlobalConfig config = RotogateGlobalConfig.get();
        if (config != null) {
            StaleUserCleanupWork.runCleanup(config);
        }
        rsp.sendRedirect("staleUsers?ran=true");
    }

    @POST
    public void doDeleteUser(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String userId = req.getParameter("userId");
        String from   = req.getParameter("from");
        String back   = ("userStatus".equals(from)) ? "userStatus" : "staleUsers";
        if (userId != null && !userId.isEmpty()) {
            RotogateGlobalConfig config = RotogateGlobalConfig.get();
            if (isActiveGroupUser(userId)) {
                LOGGER.warning("Deletion blocked - user is managed via active Entra group: " + userId);
                rsp.sendRedirect(back + "?error=activeGroup");
                return;
            }
            if (config != null && config.isProtected(userId)) {
                LOGGER.warning("Deletion blocked - user is protected: " + userId);
                rsp.sendRedirect(back + "?error=protected");
                return;
            }
            User user = User.getById(userId, false);
            if (user != null) {
                String deletedBy = currentUserId();
                LOGGER.info("Manual user deletion by " + deletedBy + ": " + userId + " (from " + back + ")");
                user.delete();
                NotificationService.sendUserDeleted(config, userId, deletedBy);
                RotogateAuditLog audit = RotogateAuditLog.get();
                if (audit != null) audit.logUserDeleted(deletedBy, userId);
            }
            // Always wipe matrix entries - user object may already be gone but entries linger
            purgeMatrixEntries(userId);
        }
        rsp.sendRedirect(back + "?deleted=true");
    }

    @POST
    public void doMarkForDeletion(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String userId = req.getParameter("userId");
        String from   = req.getParameter("from");
        if (userId != null && !userId.isEmpty()) {
            User user = User.getById(userId, false);
            if (user != null) {
                RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
                if (prop != null) {
                    prop.setPendingDeletion(true);
                    user.addProperty(prop);
                    user.save();
                    LOGGER.info("User marked for deletion by " + currentUserId() + ": " + userId);
                    RotogateAuditLog.get().logUserMarkedForDeletion(currentUserId(), userId);
                }
            }
        }
        String back = ("userStatus".equals(from)) ? "userStatus" : "staleUsers";
        rsp.sendRedirect(back + "?marked=true");
    }

    @POST
    public void doUnmarkForDeletion(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String userId = req.getParameter("userId");
        String from   = req.getParameter("from");
        if (userId != null && !userId.isEmpty()) {
            User user = User.getById(userId, false);
            if (user != null) {
                RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
                if (prop != null) {
                    prop.setPendingDeletion(false);
                    user.addProperty(prop);
                    user.save();
                    LOGGER.info("Pending deletion cleared by " + currentUserId() + ": " + userId);
                    RotogateAuditLog.get().logUserUnmarkedForDeletion(currentUserId(), userId);
                }
            }
        }
        String back = ("userStatus".equals(from)) ? "userStatus" : "staleUsers";
        rsp.sendRedirect(back + "?unmarked=true");
    }

    /**
     * Revoke all GLOBAL permission entries for a sid (rebuilds the global strategy without them).
     * Used by the account-expiry worker to disable an account - losing global Read effectively
     * locks the user out. Static so background work can call it without a console instance.
     */
    static void purgeGlobalMatrix(String userId) {
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (!(strat instanceof RotogateAuthorizationStrategy)) return;
        RotogateAuthorizationStrategy current = (RotogateAuthorizationStrategy) strat;
        RotogateAuthorizationStrategy rebuilt = new RotogateAuthorizationStrategy();
        for (Map.Entry<Permission, Set<PermissionEntry>> e : current.getGrantedPermissionEntries().entrySet()) {
            for (PermissionEntry pe : e.getValue()) {
                if (!pe.getSid().equals(userId)) {
                    rebuilt.add(e.getKey(), pe);
                }
            }
        }
        Jenkins.get().setAuthorizationStrategy(rebuilt);
        try { Jenkins.get().save(); } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to save Jenkins config after purging global matrix for " + userId, ex);
        }
    }

    private void purgeMatrixEntries(String userId) {
        // 1. Global matrix - remove both USER and GROUP entries (belt-and-suspenders)
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (strat instanceof RotogateAuthorizationStrategy) {
            RotogateAuthorizationStrategy current = (RotogateAuthorizationStrategy) strat;
            RotogateAuthorizationStrategy rebuilt = new RotogateAuthorizationStrategy();
            for (Map.Entry<Permission, Set<PermissionEntry>> e : current.getGrantedPermissionEntries().entrySet()) {
                for (PermissionEntry pe : e.getValue()) {
                    if (!pe.getSid().equals(userId)) {
                        rebuilt.add(e.getKey(), pe);
                    }
                }
            }
            Jenkins.get().setAuthorizationStrategy(rebuilt);
            try { Jenkins.get().save(); } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to save Jenkins config after purging global matrix for " + userId, ex);
            }
        }

        // 2. Job-level matrices
        try {
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                hudson.security.AuthorizationMatrixProperty prop =
                        job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
                if (prop == null) continue;
                boolean affected = prop.getGrantedPermissionEntries().values().stream()
                        .anyMatch(set -> set.stream().anyMatch(pe -> pe.getSid().equals(userId)));
                if (!affected) continue;
                mutateGrantedPermissions(prop, userId, null, null, Collections.emptySet());
                job.save();
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to purge job-level matrix entries for " + userId, ex);
        }

        // 3. Folder-level matrices
        try {
            for (AbstractFolder<?> folder : Jenkins.get().getAllItems(AbstractFolder.class)) {
                com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                        folder.getProperties().get(
                                com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
                if (fp == null) continue;
                boolean affected = fp.getGrantedPermissionEntries().values().stream()
                        .anyMatch(set -> set.stream().anyMatch(pe -> pe.getSid().equals(userId)));
                if (!affected) continue;
                mutateGrantedPermissions(fp, userId, null, null, Collections.emptySet());
                folder.save();
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to purge folder-level matrix entries for " + userId, ex);
        }
    }

    @POST
    public void doSetUserRole(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid      = req.getParameter("sid");
        String type     = req.getParameter("type");
        String roleName = req.getParameter("role");

        if (sid == null || sid.trim().isEmpty()) {
            rsp.sendRedirect("accessManagement?error=missingSid"); return;
        }

        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (!(strat instanceof RotogateAuthorizationStrategy)) {
            rsp.sendRedirect("accessManagement?error=notRotogate"); return;
        }
        RotogateAuthorizationStrategy current = (RotogateAuthorizationStrategy) strat;

        Set<String> newPermIds;
        RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
        RotogateRoleConfig.RoleDefinition roleDef = roleConfig != null ? roleConfig.findRole(roleName) : null;
        if (roleDef != null) {
            newPermIds = new HashSet<>(roleDef.getPermissionIds());
        } else if ("CUSTOM".equalsIgnoreCase(roleName)) {
            String[] custom = req.getParameterValues("customPermissions");
            newPermIds = custom != null ? new HashSet<>(Arrays.asList(custom)) : Collections.emptySet();
        } else {
            rsp.sendRedirect("accessManagement?error=invalidRole"); return;
        }

        if (newPermIds.isEmpty()) {
            rsp.sendRedirect("accessManagement?error=noPermissions"); return;
        }

        AuthorizationType targetType = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;
        sid = sid.trim();

        // Rebuild: keep all other entries, replace this user's entries.
        // Preserve Hudson.Read for this user - login access comes from account creation, not role.
        Permission hudsonRead = Permission.fromId("hudson.model.Hudson.Read");
        RotogateAuthorizationStrategy rebuilt = new RotogateAuthorizationStrategy();
        for (Map.Entry<Permission, Set<PermissionEntry>> e : current.getGrantedPermissionEntries().entrySet()) {
            for (PermissionEntry pe : e.getValue()) {
                boolean isThisUser = pe.getSid().equals(sid) && pe.getType() == targetType;
                boolean isHudsonRead = e.getKey().equals(hudsonRead);
                if (!isThisUser || isHudsonRead) {
                    rebuilt.add(e.getKey(), pe);
                }
            }
        }
        PermissionEntry entry = targetType == AuthorizationType.GROUP
                ? PermissionEntry.group(sid) : PermissionEntry.user(sid);
        for (String permId : newPermIds) {
            Permission p = Permission.fromId(permId);
            if (p != null) rebuilt.add(p, entry);
        }

        Set<PermissionEntry> adminSet = rebuilt.getGrantedPermissionEntries().get(Jenkins.ADMINISTER);
        if (adminSet == null || adminSet.isEmpty()) {
            rsp.sendRedirect("accessManagement?error=lastAdmin"); return;
        }

        Jenkins.get().setAuthorizationStrategy(rebuilt);
        Jenkins.get().save();
        RotogateAuditLog.get().logPermissionGranted(
                Jenkins.getAuthentication2().getName(), sid, "global",
                (targetType == AuthorizationType.GROUP ? "group " : "")
                        + "global role → " + roleName);
        rsp.sendRedirect("accessManagement?saved=true&sid=" + java.net.URLEncoder.encode(sid, "UTF-8"));
    }

    @POST
    public void doRemoveUserAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid  = req.getParameter("sid");
        String type = req.getParameter("type");

        if (sid == null) { rsp.sendRedirect("accessManagement?error=missing"); return; }

        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (!(strat instanceof RotogateAuthorizationStrategy)) {
            rsp.sendRedirect("accessManagement?error=notRotogate"); return;
        }
        RotogateAuthorizationStrategy current = (RotogateAuthorizationStrategy) strat;
        AuthorizationType targetType = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        RotogateAuthorizationStrategy rebuilt = new RotogateAuthorizationStrategy();
        for (Map.Entry<Permission, Set<PermissionEntry>> e : current.getGrantedPermissionEntries().entrySet()) {
            for (PermissionEntry pe : e.getValue()) {
                if (!(pe.getSid().equals(sid) && pe.getType() == targetType)) {
                    rebuilt.add(e.getKey(), pe);
                }
            }
        }

        Set<PermissionEntry> adminSet = rebuilt.getGrantedPermissionEntries().get(Jenkins.ADMINISTER);
        if (adminSet == null || adminSet.isEmpty()) {
            rsp.sendRedirect("accessManagement?error=lastAdmin"); return;
        }

        Jenkins.get().setAuthorizationStrategy(rebuilt);
        Jenkins.get().save();
        RotogateAuditLog.get().logPermissionRevoked(
                Jenkins.getAuthentication2().getName(), sid, "global",
                (targetType == AuthorizationType.GROUP ? "group " : "")
                        + "all global permissions");
        rsp.sendRedirect("accessManagement?removed=true");
    }

    /** Returns JSON array of scoped permissions for a user across all jobs. */
    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doScopedAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid  = req.getParameter("sid");
        String type = req.getParameter("type");
        if (sid == null) { writeJson(rsp, "[]"); return; }

        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        try {
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                hudson.security.AuthorizationMatrixProperty prop =
                        job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
                if (prop == null) continue;
                List<String> granted = new ArrayList<>();
                for (Map.Entry<Permission, Set<PermissionEntry>> e : prop.getGrantedPermissionEntries().entrySet()) {
                    for (PermissionEntry pe : e.getValue()) {
                        if (pe.getSid().equals(sid) && pe.getType() == atype) {
                            String pId = e.getKey().getId();
                            int dot = pId.lastIndexOf('.');
                            granted.add(dot >= 0 ? pId.substring(dot + 1) : pId);
                        }
                    }
                }
                if (!granted.isEmpty()) {
                    if (!first) json.append(",");
                    json.append("{\"jobName\":\"").append(escapeJson(job.getFullName())).append("\"")
                        .append(",\"permissions\":[");
                    for (int i = 0; i < granted.size(); i++) {
                        if (i > 0) json.append(",");
                        json.append("\"").append(escapeJson(granted.get(i))).append("\"");
                    }
                    json.append("]}");
                    first = false;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error scanning scoped permissions", e);
        }
        writeJson(rsp, json.append("]").toString());
    }

    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doAllScopedAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        try {
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                hudson.security.AuthorizationMatrixProperty prop =
                        job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
                if (prop == null) continue;
                Map<String, List<String>> bySid = new LinkedHashMap<>();
                for (Map.Entry<Permission, Set<PermissionEntry>> e : prop.getGrantedPermissionEntries().entrySet()) {
                    String pShort = e.getKey().getId();
                    int dot = pShort.lastIndexOf('.');
                    if (dot >= 0) pShort = pShort.substring(dot + 1);
                    for (PermissionEntry pe : e.getValue()) {
                        String key = pe.getType().name() + "\0" + pe.getSid();
                        bySid.computeIfAbsent(key, k -> new ArrayList<>()).add(pShort);
                    }
                }
                for (Map.Entry<String, List<String>> entry : bySid.entrySet()) {
                    String[] parts = entry.getKey().split("\0", 2);
                    if (!first) json.append(",");
                    json.append("{\"sid\":\"").append(escapeJson(parts.length > 1 ? parts[1] : parts[0])).append("\"")
                        .append(",\"type\":\"").append(parts[0]).append("\"")
                        .append(",\"jobName\":\"").append(escapeJson(job.getFullName())).append("\"")
                        .append(",\"permissions\":[");
                    List<String> perms = entry.getValue();
                    for (int i = 0; i < perms.size(); i++) {
                        if (i > 0) json.append(",");
                        json.append("\"").append(escapeJson(perms.get(i))).append("\"");
                    }
                    json.append("]}");
                    first = false;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error scanning all scoped permissions", e);
        }
        writeJson(rsp, json.append("]").toString());
    }

    @POST
    public void doGrantScopedAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid       = req.getParameter("sid");
        String type      = req.getParameter("type");
        String jobName   = req.getParameter("jobName");
        String scopedRole = req.getParameter("scopedRole");

        if (sid == null || jobName == null) {
            rsp.sendRedirect("accessManagement?error=missing"); return;
        }

        Job<?, ?> job = Jenkins.get().getItemByFullName(jobName, Job.class);
        if (job == null) {
            rsp.sendRedirect("accessManagement?error=jobNotFound&sid=" +
                    java.net.URLEncoder.encode(sid, "UTF-8")); return;
        }

        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;
        PermissionEntry entry = atype == AuthorizationType.GROUP
                ? PermissionEntry.group(sid) : PermissionEntry.user(sid);

        // Determine permissions from config-based role, then legacy scoped role names
        List<String> permIds;
        RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
        RotogateRoleConfig.RoleDefinition roleDef = roleConfig != null ? roleConfig.findRole(scopedRole) : null;
        if (roleDef != null) {
            permIds = new ArrayList<>(roleDef.getPermissionIds());
        } else {
            permIds = new ArrayList<>();
            permIds.add("hudson.model.Item.Read");
            if ("BUILD".equals(scopedRole) || "CONFIGURE".equals(scopedRole)) {
                permIds.add("hudson.model.Item.Build");
                permIds.add("hudson.model.Item.Cancel");
            }
            if ("CONFIGURE".equals(scopedRole)) {
                permIds.add("hudson.model.Item.Configure");
                permIds.add("hudson.model.Item.Delete");
            }
        }

        // Grant on the target job
        hudson.security.AuthorizationMatrixProperty prop =
                job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
        if (prop == null) {
            prop = new hudson.security.AuthorizationMatrixProperty(new java.util.HashMap<>());
        }
        for (String permId : permIds) {
            Permission p = Permission.fromId(permId);
            if (p != null) prop.add(p, entry);
        }
        job.addProperty(prop);
        job.save();

        // Auto-grant Item.Read on all parent jobs/folders (best-effort)
        hudson.model.ItemGroup<?> parent = job.getParent();
        while (parent instanceof Job) {
            Job<?, ?> parentJob = (Job<?, ?>) parent;
            try {
                hudson.security.AuthorizationMatrixProperty fp =
                        parentJob.getProperty(hudson.security.AuthorizationMatrixProperty.class);
                if (fp == null) fp = new hudson.security.AuthorizationMatrixProperty(new java.util.HashMap<>());
                Permission readPerm = Permission.fromId("hudson.model.Item.Read");
                if (readPerm != null) fp.add(readPerm, entry);
                parentJob.addProperty(fp);
                parentJob.save();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Best-effort parent Item.Read grant failed for " + parentJob.getFullName(), e);
            }
            parent = ((hudson.model.AbstractItem) parentJob).getParent();
        }

        RotogateAuditLog.get().logPermissionGranted(
                Jenkins.getAuthentication2().getName(), sid, jobName,
                (atype == AuthorizationType.GROUP ? "group " : "") + "scoped role → " + scopedRole);
        rsp.sendRedirect("accessManagement?saved=true&sid=" +
                java.net.URLEncoder.encode(sid, "UTF-8"));
    }

    @POST
    public void doRevokeScopedAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid     = req.getParameter("sid");
        String type    = req.getParameter("type");
        String jobName = req.getParameter("jobName");

        if (sid == null || jobName == null) {
            rsp.sendRedirect("accessManagement?error=missing"); return;
        }

        Job<?, ?> job = Jenkins.get().getItemByFullName(jobName, Job.class);
        if (job == null) {
            rsp.sendRedirect("accessManagement?saved=true&sid=" +
                    java.net.URLEncoder.encode(sid, "UTF-8")); return;
        }

        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        hudson.security.AuthorizationMatrixProperty prop =
                job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
        if (prop != null) {
            // Build filtered map excluding this user's entries
            java.util.Map<Permission, Set<PermissionEntry>> filteredMap = new java.util.HashMap<>();
            for (Map.Entry<Permission, Set<PermissionEntry>> e : prop.getGrantedPermissionEntries().entrySet()) {
                for (PermissionEntry pe : e.getValue()) {
                    if (!(pe.getSid().equals(sid) && pe.getType() == atype)) {
                        filteredMap.computeIfAbsent(e.getKey(), k -> new java.util.HashSet<>()).add(pe);
                    }
                }
            }
            hudson.security.AuthorizationMatrixProperty rebuilt =
                    new hudson.security.AuthorizationMatrixProperty(filteredMap, prop.getInheritanceStrategy());
            job.addProperty(rebuilt);
            job.save();
        }

        RotogateAuditLog.get().logPermissionRevoked(
                Jenkins.getAuthentication2().getName(), sid, jobName,
                (atype == AuthorizationType.GROUP ? "group " : "") + "scoped permissions");
        rsp.sendRedirect("accessManagement?saved=true&sid=" +
                java.net.URLEncoder.encode(sid, "UTF-8"));
    }

    // -------------------------------------------------------------------------
    // Role management (CRUD for RotogateRoleConfig)
    // -------------------------------------------------------------------------

    public List<RotogateRoleConfig.RoleDefinition> getRoles() {
        RotogateRoleConfig cfg = RotogateRoleConfig.get();
        return cfg != null ? cfg.getRoles() : Collections.emptyList();
    }

    @POST
    public void doSaveRole(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String name        = req.getParameter("roleName");
        String description = req.getParameter("roleDescription");
        String[] permIds   = req.getParameterValues("rolePermissions");
        if (name == null || name.trim().isEmpty()) {
            rsp.sendRedirect("accessManagement?error=missingRoleName"); return;
        }
        RotogateRoleConfig cfg = RotogateRoleConfig.get();
        if (cfg != null) {
            List<String> perms = permIds != null ? Arrays.asList(permIds) : Collections.emptyList();
            boolean isNew = cfg.findRole(name.trim()) == null;
            cfg.upsertRole(name.trim(), description != null ? description.trim() : "", perms);
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) {
                if (isNew) audit.logRoleCreated(Jenkins.getAuthentication2().getName(), name.trim());
                else audit.logRoleEdited(Jenkins.getAuthentication2().getName(), name.trim());
            }
        }
        rsp.sendRedirect("accessManagement?roleSaved=true");
    }

    @POST
    public void doDeleteRole(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String name = req.getParameter("roleName");
        if (name != null) {
            RotogateRoleConfig cfg = RotogateRoleConfig.get();
            if (cfg != null) cfg.deleteRole(name);
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logRoleDeleted(Jenkins.getAuthentication2().getName(), name);
        }
        rsp.sendRedirect("accessManagement?roleDeleted=true");
    }

    // -------------------------------------------------------------------------
    // User-centric assignment view (used by userDetail.jelly)
    // -------------------------------------------------------------------------

    public List<UserAssignmentInfo> getUserAssignments() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return Collections.emptyList();
        String sid  = req.getParameter("sid");
        String type = req.getParameter("type");
        if (sid == null) return Collections.emptyList();

        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;
        RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
        List<UserAssignmentInfo> result = new ArrayList<>();

        // Global assignment
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (strat instanceof GlobalMatrixAuthorizationStrategy) {
            Set<String> perms = collectDirectPermsForUser(
                    ((GlobalMatrixAuthorizationStrategy) strat).getGrantedPermissionEntries(), sid, atype);
            if (!perms.isEmpty()) {
                String roleName = roleConfig != null ? roleConfig.matchRole(perms) : null;
                result.add(new UserAssignmentInfo("", "Jenkins (Global)", "global",
                        roleName != null ? roleName : "Custom", new ArrayList<>(perms),
                        null, null));
            }
        }

        // Item-level assignments from assignment store
        RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
        if (assignmentConfig != null) {
            String authTypeStr = atype == AuthorizationType.GROUP ? "GROUP" : "USER";
            RotogateGlobalConfig reviewCfg = RotogateGlobalConfig.get();
            int reviewThreshold = (reviewCfg != null && reviewCfg.isAccessReviewEnabled())
                    ? reviewCfg.getAccessReviewThresholdDays() : -1;
            for (RotogateAssignment a : assignmentConfig.getAssignmentsForUser(sid, authTypeStr)) {
                if (a.getScope().isEmpty()) continue; // global handled above
                String itemType = "FOLDER".equals(a.getScopeType()) ? "folder" : "job";
                List<String> perms = "CUSTOM".equalsIgnoreCase(a.getRoleId())
                        ? a.getCustomPermissions()
                        : (roleConfig != null && roleConfig.findRole(a.getRoleId()) != null
                                ? roleConfig.findRole(a.getRoleId()).getPermissionIds()
                                : Collections.emptyList());
                List<String> customPerms = "CUSTOM".equalsIgnoreCase(a.getRoleId())
                        ? a.getCustomPermissions() : Collections.emptyList();
                UserAssignmentInfo info = new UserAssignmentInfo(a.getScope(), a.getScope(), itemType,
                        a.getRoleId(), new ArrayList<>(perms),
                        a.getExpiresAt(), new ArrayList<>(customPerms));
                if (reviewThreshold > 0) info.setReviewDue(a.isReviewDue(reviewThreshold));
                info.setAccessType(a.getAccessType());
                info.setApproverGroup(a.getApproverGroup());
                info.setMaxDurationHours(a.getMaxDurationHours());
                info.setApprovalTimeoutHours(a.getApprovalTimeoutHours());
                result.add(info);
            }
        }

        return result;
    }

    /** Grant a config-defined role to a user at a given scope (global or specific item path). */
    @POST
    public void doGrantAssignment(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid      = req.getParameter("sid");
        String type     = req.getParameter("type");
        String roleName = req.getParameter("role");
        String scope    = req.getParameter("scope"); // "" = global, "item/path" = scoped

        if (sid == null || sid.trim().isEmpty()) {
            rsp.sendRedirect("userDetail?sid=&type=&error=missingSid"); return;
        }
        sid = sid.trim();
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        Set<String> permIds;
        if ("CUSTOM".equalsIgnoreCase(roleName)) {
            String[] custom = req.getParameterValues("customPermissions");
            permIds = custom != null && custom.length > 0
                    ? new HashSet<>(Arrays.asList(custom)) : Collections.emptySet();
        } else {
            RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
            RotogateRoleConfig.RoleDefinition roleDef = roleConfig != null ? roleConfig.findRole(roleName) : null;
            if (roleDef == null) {
                rsp.sendRedirect(detailUrl(sid, atype, "error=invalidRole")); return;
            }
            permIds = new HashSet<>(roleDef.getPermissionIds());
        }
        if (permIds.isEmpty()) {
            rsp.sendRedirect(detailUrl(sid, atype, "error=noPermissions")); return;
        }

        String expiresAt = toInstantString(req.getParameter("expiresAt"));

        if (scope == null || scope.isEmpty()) {
            applyRootPermissions(sid, atype, permIds, rsp, false);
        } else {
            Item item = Jenkins.get().getItemByFullName(scope);
            if (item == null) {
                rsp.sendRedirect(detailUrl(sid, atype, "error=notFound")); return;
            }
            String scopeType = (item instanceof com.cloudbees.hudson.plugins.folder.AbstractFolder) ? "FOLDER" : "JOB";
            String authTypeStr = atype == AuthorizationType.GROUP ? "GROUP" : "USER";
            String grantedAt = java.time.Instant.now().toString();
            String grantedBy = Jenkins.getAuthentication2().getName();
            List<String> customPerms = "CUSTOM".equalsIgnoreCase(roleName)
                    ? new java.util.ArrayList<>(permIds) : null;
            RotogateAssignment assignment = new RotogateAssignment(
                    sid, authTypeStr, roleName, scope, scopeType, customPerms, grantedAt, grantedBy);
            assignment.setExpiresAt(expiresAt);
            applyJitFieldsFromRequest(req, assignment);
            if (assignment.isJit() && assignment.getApprovers().size() < 2) {
                rsp.sendRedirect(detailUrl(sid, atype, "error=jitMinApprovers")); return;
            }
            RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
            if (config != null) config.addAssignment(assignment);
            // Ensure user can log in - Hudson.Read at global is required for any access
            grantGlobalRead(sid, atype);
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logGrant(Jenkins.getAuthentication2().getName(), sid, roleName, scope, expiresAt);
            rsp.sendRedirect(detailUrl(sid, atype, "saved=true"));
        }
    }

    /** Typeahead endpoint - returns up to 10 users + groups whose id/name contains the query. */
    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doSuggestApprovers(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String q = req.getParameter("q");
        if (q == null) q = "";
        String lq = q.toLowerCase().trim();

        java.util.List<String> results = new java.util.ArrayList<>();

        // Groups first - from Rotogate group assignments (unique group IDs)
        RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
        if (assignmentConfig != null) {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (RotogateAssignment a : assignmentConfig.getAssignments()) {
                if (!"GROUP".equalsIgnoreCase(a.getAuthType())) continue;
                String gid = a.getUserId();
                if (gid == null || gid.isBlank() || seen.contains(gid)) continue;
                seen.add(gid);
                if (!lq.isEmpty() && !gid.toLowerCase().contains(lq)) continue;
                results.add("{\"id\":\"" + jsonEsc(gid) + "\",\"type\":\"group\"}");
                if (results.size() >= 5) break;
            }
        }

        // Users - remaining slots up to 10 total
        int remaining = 10 - results.size();
        for (User user : User.getAll()) {
            if (remaining <= 0) break;
            if (isInternalUser(user)) continue;
            String id      = user.getId();
            String full    = user.getFullName();
            String display = (full != null && !full.equals(id)) ? full : null;
            String combined = (id + " " + (display != null ? display : "")).toLowerCase();
            if (!lq.isEmpty() && !combined.contains(lq)) continue;
            StringBuilder entry = new StringBuilder("{\"id\":\"").append(jsonEsc(id)).append("\",\"type\":\"user\"");
            if (display != null) entry.append(",\"displayName\":\"").append(jsonEsc(display)).append("\"");
            entry.append("}");
            results.add(entry.toString());
            remaining--;
        }

        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write("[" + String.join(",", results) + "]");
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static void applyJitFieldsFromRequest(StaplerRequest2 req, RotogateAssignment assignment) {
        String accessType = req.getParameter("accessType");
        if ("JIT".equalsIgnoreCase(accessType)) {
            assignment.setAccessType("JIT");
            String approversParam = req.getParameter("approvers");
            if (approversParam != null && !approversParam.isBlank()) {
                java.util.List<String> approvers = java.util.Arrays.stream(approversParam.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
                assignment.setApprovers(approvers);
            }
            try { assignment.setMaxDurationHours(Integer.parseInt(req.getParameter("maxDurationHours"))); }
            catch (Exception e) { LOGGER.log(Level.FINE, "Ignoring invalid maxDurationHours parameter", e); }
            try { assignment.setApprovalTimeoutHours(Integer.parseInt(req.getParameter("approvalTimeoutHours"))); }
            catch (Exception e) { LOGGER.log(Level.FINE, "Ignoring invalid approvalTimeoutHours parameter", e); }
        } else {
            assignment.setAccessType("STANDING");
        }
    }

    /** Edit (replace) an existing assignment - scope stays the same, role/permissions/expiry may change. */
    @POST
    public void doEditAssignment(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid      = req.getParameter("sid");
        String type     = req.getParameter("type");
        String scope    = req.getParameter("scope");
        String roleName = req.getParameter("role");
        String expiresAt = toInstantString(req.getParameter("expiresAt"));

        if (sid == null || sid.trim().isEmpty()) {
            rsp.sendRedirect("userDetail?sid=&type=&error=missingSid"); return;
        }
        sid = sid.trim();
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;
        String authTypeStr = atype == AuthorizationType.GROUP ? "GROUP" : "USER";

        Set<String> permIds;
        if ("CUSTOM".equalsIgnoreCase(roleName)) {
            String[] custom = req.getParameterValues("customPermissions");
            permIds = custom != null && custom.length > 0
                    ? new HashSet<>(Arrays.asList(custom)) : Collections.emptySet();
        } else {
            RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
            RotogateRoleConfig.RoleDefinition roleDef = roleConfig != null ? roleConfig.findRole(roleName) : null;
            if (roleDef == null) {
                rsp.sendRedirect(detailUrl(sid, atype, "error=invalidRole")); return;
            }
            permIds = new HashSet<>(roleDef.getPermissionIds());
        }
        if (permIds.isEmpty()) {
            rsp.sendRedirect(detailUrl(sid, atype, "error=noPermissions")); return;
        }

        String scopeType;
        if (scope == null || scope.isEmpty()) {
            scopeType = "GLOBAL";
        } else {
            Item item = Jenkins.get().getItemByFullName(scope);
            scopeType = (item instanceof com.cloudbees.hudson.plugins.folder.AbstractFolder) ? "FOLDER" : "JOB";
        }

        List<String> customPerms = "CUSTOM".equalsIgnoreCase(roleName)
                ? new ArrayList<>(permIds) : null;

        RotogateAssignment updated = new RotogateAssignment(
                sid, authTypeStr, roleName,
                scope != null ? scope : "", scopeType,
                customPerms,
                java.time.Instant.now().toString(),
                Jenkins.getAuthentication2().getName());
        updated.setExpiresAt(expiresAt);
        applyJitFieldsFromRequest(req, updated);
        if (updated.isJit() && updated.getApprovers().size() < 2) {
            rsp.sendRedirect(detailUrl(sid, atype, "error=jitMinApprovers")); return;
        }

        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        String oldRole = null;
        if (config != null) {
            RotogateAssignment existing = config.getAssignmentsForUser(sid, authTypeStr).stream()
                    .filter(a -> a.getScope().equals(scope != null ? scope : ""))
                    .findFirst().orElse(null);
            oldRole = existing != null ? existing.getRoleId() : null;
            config.updateAssignment(sid, authTypeStr, scope != null ? scope : "", updated);
        }
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logEdit(Jenkins.getAuthentication2().getName(), sid, scope, oldRole != null ? oldRole : "?", roleName, expiresAt);
        rsp.sendRedirect(detailUrl(sid, atype, "saved=true"));
    }

    /** Revoke a specific access assignment (global or scoped to an item path). */
    @POST
    public void doRevokeAssignment(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid   = req.getParameter("sid");
        String type  = req.getParameter("type");
        String scope = req.getParameter("scope");

        if (sid == null) {
            rsp.sendRedirect("userDetail?sid=&type=&error=missing"); return;
        }
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        if (scope == null || scope.isEmpty()) {
            applyRootPermissions(sid, atype, Collections.emptySet(), rsp, true);
        } else {
            String authTypeStr = atype == AuthorizationType.GROUP ? "GROUP" : "USER";
            RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
            String revokedRole = null;
            if (config != null) {
                String normalizedScope = scope;
                revokedRole = config.getAssignmentsForUser(sid, authTypeStr).stream()
                        .filter(a -> a.getScope().equals(normalizedScope))
                        .findFirst().map(RotogateAssignment::getRoleId).orElse(null);
                config.removeAssignment(sid, authTypeStr, scope);
            }
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logRevoke(Jenkins.getAuthentication2().getName(), sid, scope, revokedRole);
            rsp.sendRedirect(detailUrl(sid, atype, "saved=true"));
        }
    }

    @POST
    public void doConfirmAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid      = req.getParameter("sid");
        String type     = req.getParameter("type");
        String scope    = req.getParameter("scope");
        String returnTo = req.getParameter("returnTo");
        if (sid == null || sid.isBlank()) {
            rsp.sendRedirect("accessManagement"); return;
        }
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;
        String authTypeStr = atype == AuthorizationType.GROUP ? "GROUP" : "USER";

        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        String confirmedRole = null;
        if (config != null) {
            String normalizedScope = scope != null ? scope : "";
            confirmedRole = config.getAssignmentsForUser(sid, authTypeStr).stream()
                    .filter(a -> a.getScope().equals(normalizedScope))
                    .findFirst().map(RotogateAssignment::getRoleId).orElse(null);
            config.confirmReview(sid, authTypeStr, normalizedScope);
        }
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logReviewConfirmed(Jenkins.getAuthentication2().getName(), sid, confirmedRole, scope);
        if ("accessReview".equals(returnTo)) {
            rsp.sendRedirect("accessReview?confirmed=true");
        } else {
            rsp.sendRedirect(detailUrl(sid, atype, "reviewed=true"));
        }
    }

    @POST
    public void doConfirmAllAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RotogateGlobalConfig cfg = RotogateGlobalConfig.get();
        if (cfg == null || !cfg.isAccessReviewEnabled()) { rsp.sendRedirect("accessReview"); return; }
        RotogateAssignmentConfig ac = RotogateAssignmentConfig.get();
        if (ac == null) { rsp.sendRedirect("accessReview"); return; }
        int threshold = cfg.getAccessReviewThresholdDays();
        String by = Jenkins.getAuthentication2().getName();
        RotogateAuditLog audit = RotogateAuditLog.get();
        for (RotogateAssignment a : ac.getAssignments()) {
            if (!a.isReviewDue(threshold)) continue;
            ac.confirmReview(a.getUserId(), a.getAuthType(), a.getScope());
            if (audit != null) audit.logReviewConfirmed(by, a.getUserId(), a.getRoleId(), a.getScope());
        }
        rsp.sendRedirect("accessReview?confirmedAll=true");
    }

    @POST
    public void doRevokeFromReview(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid   = req.getParameter("sid");
        String type  = req.getParameter("type");
        String scope = req.getParameter("scope");
        if (sid == null || sid.isBlank()) {
            rsp.sendRedirect("accessReview"); return;
        }
        String authTypeStr = "GROUP".equalsIgnoreCase(type) ? "GROUP" : "USER";
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        String revokedRole = null;
        if (config != null) {
            String normalizedScope = scope != null ? scope : "";
            revokedRole = config.getAssignmentsForUser(sid, authTypeStr).stream()
                    .filter(a -> a.getScope().equals(normalizedScope))
                    .findFirst().map(RotogateAssignment::getRoleId).orElse(null);
            config.removeAssignment(sid, authTypeStr, normalizedScope);
        }
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logRevoke(Jenkins.getAuthentication2().getName(), sid,
                scope != null ? scope : "", revokedRole);
        rsp.sendRedirect("accessReview?revoked=true");
    }

    // -------------------------------------------------------------------------
    // Group assignment helpers
    // -------------------------------------------------------------------------

    public List<RotogateAssignment> getGroupAssignmentsForOid(String groupOid) {
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        if (config == null) return Collections.emptyList();
        return config.getAssignmentsForUser(groupOid, "GROUP");
    }

    /** Returns group assignments as the same rich view model used by userDetail.jelly. */
    public List<UserAssignmentInfo> getGroupDetailAssignments() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return Collections.emptyList();
        String oid = req.getParameter("oid");
        if (oid == null) return Collections.emptyList();

        RotogateRoleConfig roleConfig = RotogateRoleConfig.get();
        List<UserAssignmentInfo> result = new ArrayList<>();

        // Global assignment from matrix
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (strat instanceof GlobalMatrixAuthorizationStrategy) {
            Set<String> perms = collectDirectPermsForUser(
                    ((GlobalMatrixAuthorizationStrategy) strat).getGrantedPermissionEntries(),
                    oid, AuthorizationType.GROUP);
            if (!perms.isEmpty()) {
                String roleName = roleConfig != null ? roleConfig.matchRole(perms) : null;
                result.add(new UserAssignmentInfo("", "Jenkins (Global)", "global",
                        roleName != null ? roleName : "Custom", new ArrayList<>(perms), null, null));
            }
        }

        // Item-level assignments
        RotogateAssignmentConfig aConfig = RotogateAssignmentConfig.get();
        if (aConfig != null) {
            RotogateGlobalConfig reviewCfg = RotogateGlobalConfig.get();
            int reviewThreshold = (reviewCfg != null && reviewCfg.isAccessReviewEnabled())
                    ? reviewCfg.getAccessReviewThresholdDays() : -1;
            for (RotogateAssignment a : aConfig.getAssignmentsForUser(oid, "GROUP")) {
                if (a.getScope().isEmpty()) continue;
                String itemType = "FOLDER".equals(a.getScopeType()) ? "folder" : "job";
                List<String> perms = "CUSTOM".equalsIgnoreCase(a.getRoleId())
                        ? a.getCustomPermissions()
                        : (roleConfig != null && roleConfig.findRole(a.getRoleId()) != null
                                ? roleConfig.findRole(a.getRoleId()).getPermissionIds()
                                : Collections.emptyList());
                List<String> customPerms = "CUSTOM".equalsIgnoreCase(a.getRoleId())
                        ? a.getCustomPermissions() : Collections.emptyList();
                UserAssignmentInfo info = new UserAssignmentInfo(a.getScope(), a.getScope(), itemType,
                        a.getRoleId(), new ArrayList<>(perms), a.getExpiresAt(), new ArrayList<>(customPerms));
                if (reviewThreshold > 0) info.setReviewDue(a.isReviewDue(reviewThreshold));
                result.add(info);
            }
        }
        return result;
    }

    @POST
    public void doGrantGroupAssignment(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String groupOid   = req.getParameter("sid");
        String roleId     = req.getParameter("roleId");
        String scopeType  = req.getParameter("scopeType");
        String scope      = req.getParameter("scope");
        if (scope == null) scope = "";
        scope = scope.trim();
        if ("GLOBAL".equalsIgnoreCase(scopeType)) scope = "";

        if (groupOid == null || groupOid.trim().isEmpty()) {
            rsp.sendRedirect("accessManagement?error=missing"); return;
        }
        if (roleId == null || roleId.trim().isEmpty()) {
            rsp.sendRedirect("groupDetail?oid=" + enc(groupOid) + "&error=invalidRole"); return;
        }
        String now = java.time.Instant.now().toString();
        String by  = Jenkins.getAuthentication2().getName();
        RotogateAssignment assignment = new RotogateAssignment(
                groupOid, "GROUP", roleId, scope, scopeType != null ? scopeType : "GLOBAL",
                Collections.emptyList(), now, by);
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        if (config != null) config.addAssignment(assignment);
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logGrant(by, groupOid, roleId, scope, null);
        rsp.sendRedirect("groupDetail?oid=" + enc(groupOid) + "&saved=true");
    }

    @POST
    public void doRevokeGroupAssignment(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String groupOid = req.getParameter("sid");
        String scope    = req.getParameter("scope");
        if (scope == null) scope = "";
        if (groupOid == null || groupOid.trim().isEmpty()) {
            rsp.sendRedirect("accessManagement?error=missing"); return;
        }
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        if (config != null) config.removeAssignment(groupOid, "GROUP", scope);
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logRevoke(Jenkins.getAuthentication2().getName(), groupOid, scope, null);
        rsp.sendRedirect("groupDetail?oid=" + enc(groupOid) + "&saved=true");
    }

    // -------------------------------------------------------------------------
    // Add User flow
    // -------------------------------------------------------------------------

    /** AJAX: check whether a native Jenkins account exists for the given username. */
    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doCheckNativeUser(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String username = req.getParameter("username");
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.addHeader("Cache-Control", "no-cache");
        java.io.PrintWriter w = rsp.getWriter();
        if (username == null || username.trim().isEmpty()) {
            w.write("{\"exists\":false}"); return;
        }
        User user = User.getById(username.trim(), false);
        if (user != null) {
            String dn = user.getDisplayName();
            w.write("{\"exists\":true,\"displayName\":\""
                    + dn.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        } else {
            w.write("{\"exists\":false}");
        }
    }

    /** POST: add a user (native create or Entra pre-provision) then redirect to their detail page. */
    @POST
    public void doAddUser(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String userType           = req.getParameter("userType"); // NATIVE, ENTRA, or GROUP
        String sid                = req.getParameter("sid");
        String action             = req.getParameter("action");   // "existing" or "create"
        String fullName           = req.getParameter("fullName");
        String email              = req.getParameter("email");
        boolean forcePasswordChange = "true".equals(req.getParameter("forcePasswordChange"));

        // For GROUP type the OID comes from a dedicated field to avoid conflicts with the sid field
        if ("GROUP".equalsIgnoreCase(userType)) {
            String groupOidParam = req.getParameter("groupOid");
            if (groupOidParam != null && !groupOidParam.trim().isEmpty()) {
                sid = groupOidParam.trim();
            }
        }

        if (sid == null || sid.trim().isEmpty()) {
            rsp.sendRedirect("accessManagement?error=missingSid"); return;
        }
        sid = sid.trim();

        if ("NATIVE".equalsIgnoreCase(userType) && "create".equalsIgnoreCase(action)) {
            if (!(Jenkins.get().getSecurityRealm() instanceof hudson.security.HudsonPrivateSecurityRealm)) {
                rsp.sendRedirect("accessManagement?error=notNativeRealm"); return;
            }
            hudson.security.HudsonPrivateSecurityRealm realm =
                    (hudson.security.HudsonPrivateSecurityRealm) Jenkins.get().getSecurityRealm();
            String tmpPwd = generateTempPassword();
            realm.createAccount(sid, tmpPwd);
            User user = User.getById(sid, false);
            if (user != null) {
                if (fullName != null && !fullName.trim().isEmpty()) {
                    user.setFullName(fullName.trim());
                }
                if (email != null && !email.trim().isEmpty()) {
                    try {
                        ClassLoader uberCl = Jenkins.get().getPluginManager().uberClassLoader;
                        Class<?> mailerPropClass = Class.forName("hudson.tasks.Mailer$UserProperty", true, uberCl);
                        java.lang.reflect.Constructor<?> ctor = mailerPropClass.getConstructor(String.class);
                        hudson.model.UserProperty prop = (hudson.model.UserProperty) ctor.newInstance(email.trim());
                        user.addProperty(prop);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Could not set email for user " + sid, e);
                    }
                }
                user.save();
            }
            // Auto-grant Hudson.Read so user can log in and change their password
            grantGlobalRead(sid, AuthorizationType.USER);
            // Set force-password-change flag if requested
            if (forcePasswordChange && user != null) {
                user.addProperty(new RotogateForcePasswordProperty(true));
                user.save();
            }
            // Store temp password in session - read once by userDetail, then cleared
            req.getSession().setAttribute("rotogate.tmpPwd", tmpPwd);
            req.getSession().setAttribute("rotogate.tmpPwdSid", sid);
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logUserCreated(Jenkins.getAuthentication2().getName(), sid);
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&newUser=true&mustChange=" + forcePasswordChange);
        } else if ("ENTRA".equalsIgnoreCase(userType)) {
            // Pre-provision Entra user: create the Jenkins User object + placeholder property + Hudson.Read,
            // mirroring the native flow so the SSO gate can confirm they were admin-added.
            User entraUser = User.getOrCreateByIdOrFullName(sid);
            entraUser.setFullName(sid);
            RotogateUserProperty placeholder = new RotogateUserProperty(null, sid);
            placeholder.setProvisioningSource("INDIVIDUAL");
            try {
                entraUser.addProperty(placeholder);
                entraUser.save();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not save RotogateUserProperty for Entra pre-provision: " + sid, e);
            }
            grantGlobalRead(sid, AuthorizationType.USER);
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logUserCreated(Jenkins.getAuthentication2().getName(), sid);
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&preProvisioned=true");
        } else if ("GROUP".equalsIgnoreCase(userType)) {
            // sid is the group OID here
            String groupOid   = sid;
            String groupLabel = req.getParameter("groupLabel");
            if (groupLabel != null) groupLabel = groupLabel.trim();
            RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
            if (assignmentConfig == null) {
                rsp.sendRedirect("accessManagement?error=configError"); return;
            }
            if (assignmentConfig.hasGroup(groupOid)) {
                rsp.sendRedirect("accessManagement?error=groupAlreadyExists"); return;
            }
            String now = java.time.Instant.now().toString();
            String addedBy = Jenkins.getAuthentication2().getName();
            RotogateGroupEntity groupEntity = new RotogateGroupEntity(groupOid, groupLabel, now, addedBy);
            assignmentConfig.addGroup(groupEntity);
            // Grant Hudson.Read at GROUP level so members can log in
            grantGlobalRead(groupOid, AuthorizationType.GROUP);
            RotogateAuditLog audit = RotogateAuditLog.get();
            if (audit != null) audit.logUserCreated(addedBy, "[GROUP] " + groupOid);
            rsp.sendRedirect("groupDetail?oid=" + enc(groupOid) + "&added=true");
        } else {
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER");
        }
    }

    /**
     * Admin: reset a native (local-password) account's password. Generates a one-time temporary
     * password, forces a change at next login, invalidates the user's existing sessions, and shows
     * the new password once via the same modal as account creation. Never for SSO/Entra users (no
     * local password) - the admin never learns a password the user will keep.
     */
    @POST
    public void doResetPassword(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid = req.getParameter("sid");
        if (sid == null || sid.trim().isEmpty()) { rsp.sendRedirect("userStatus?error=missingSid"); return; }
        sid = sid.trim();
        if (!(Jenkins.get().getSecurityRealm() instanceof hudson.security.HudsonPrivateSecurityRealm)) {
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&error=notNativeRealm"); return;
        }
        User user = User.getById(sid, false);
        // Only native accounts (ones that carry a local password hash) can have a password reset here.
        if (user == null || user.getProperty(hudson.security.HudsonPrivateSecurityRealm.Details.class) == null) {
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&error=notNative"); return;
        }
        String tmpPwd = generateTempPassword();
        try {
            // fromPlainPassword is package-private in Jenkins core - same reflection the self-service
            // password-change flow uses (RotogateRootAction.doSubmitPasswordChange).
            java.lang.reflect.Method m = hudson.security.HudsonPrivateSecurityRealm.Details.class
                    .getDeclaredMethod("fromPlainPassword", String.class);
            m.setAccessible(true);
            hudson.security.HudsonPrivateSecurityRealm.Details details =
                    (hudson.security.HudsonPrivateSecurityRealm.Details) m.invoke(null, tmpPwd);
            user.addProperty(details);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Rotogate: admin password reset failed for " + sid, e);
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&error=resetFailed"); return;
        }
        // Force the user to set their own password at next login - the admin-set temp password is transient.
        user.addProperty(new RotogateForcePasswordProperty(true));
        user.save();
        // Invalidate the target's existing sessions so a stolen/open session can't outlive the reset.
        revokeSessionsFor(sid);
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logPasswordReset(Jenkins.getAuthentication2().getName(), sid);
        // Show the temp password exactly once, reusing the userDetail session-backed modal.
        req.getSession().setAttribute("rotogate.tmpPwd", tmpPwd);
        req.getSession().setAttribute("rotogate.tmpPwdSid", sid);
        rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&pwReset=true&mustChange=true");
    }

    /**
     * Admin: edit a user's display name and e-mail without leaving the Rotogate console. Mirrors the
     * same fields {@link #doAddUser} sets at creation (full name + Mailer e-mail property).
     */
    @POST
    public void doEditUser(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid      = req.getParameter("sid");
        String fullName = req.getParameter("fullName");
        String email    = req.getParameter("email");
        if (sid == null || sid.trim().isEmpty()) { rsp.sendRedirect("userStatus?error=missingSid"); return; }
        sid = sid.trim();
        User user = User.getById(sid, false);
        if (user == null) { rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&error=notFound"); return; }
        if (fullName != null && !fullName.trim().isEmpty()) {
            user.setFullName(fullName.trim());
        }
        if (email != null) {
            setUserEmail(user, email.trim());
        }
        user.save();
        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logConfigChanged(Jenkins.getAuthentication2().getName(), "user_details",
                "Updated profile for " + sid);
        rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=USER&detailsSaved=true");
    }

    /** Revoke every active session belonging to {@code sid} (snapshot first to avoid mutation during iteration). */
    private static void revokeSessionsFor(String sid) {
        try {
            java.util.List<String> toRevoke = new ArrayList<>();
            for (ActiveSessionManager.ActiveSession s : ActiveSessionManager.getAll()) {
                if (sid.equals(s.getUserId())) toRevoke.add(s.getSessionId());
            }
            for (String id : toRevoke) ActiveSessionManager.revoke(id);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Rotogate: could not revoke sessions for " + sid, e);
        }
    }

    /** Attach/replace the Mailer e-mail property - same reflective approach as {@link #doAddUser}. */
    private static void setUserEmail(User user, String email) {
        try {
            ClassLoader uberCl = Jenkins.get().getPluginManager().uberClassLoader;
            Class<?> mailerPropClass = Class.forName("hudson.tasks.Mailer$UserProperty", true, uberCl);
            java.lang.reflect.Constructor<?> ctor = mailerPropClass.getConstructor(String.class);
            hudson.model.UserProperty prop = (hudson.model.UserProperty) ctor.newInstance(email);
            user.addProperty(prop);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not set email for user " + user.getId(), e);
        }
    }

    /** True when {@code sid} is a native (local-password) account that supports reset/edit here. */
    public boolean isNativePasswordAccount(String sid) {
        if (sid == null || sid.isEmpty()) return false;
        if (!(Jenkins.get().getSecurityRealm() instanceof hudson.security.HudsonPrivateSecurityRealm)) return false;
        User user = User.getById(sid, false);
        return user != null && user.getProperty(hudson.security.HudsonPrivateSecurityRealm.Details.class) != null;
    }

    /** Current e-mail for the userDetail "Edit details" form (Mailer property), or "" when none/unknown. */
    @SuppressWarnings("unchecked")
    public String getUserEmail() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return "";
        String sid = req.getParameter("sid");
        if (sid == null) return "";
        User user = User.getById(sid, false);
        if (user == null) return "";
        try {
            ClassLoader uberCl = Jenkins.get().getPluginManager().uberClassLoader;
            Class<?> mailerPropClass = Class.forName("hudson.tasks.Mailer$UserProperty", true, uberCl);
            hudson.model.UserProperty prop = user.getProperty((Class<? extends hudson.model.UserProperty>) mailerPropClass);
            if (prop != null) {
                Object addr = mailerPropClass.getMethod("getAddress").invoke(prop);
                if (addr != null) return addr.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException ignore) { /* no mailer plugin or no address: fall through */ }
        return "";
    }

    /** Removes a GROUP entity from Access Management and revokes sessions for affected members. */
    @POST
    public void doRemoveGroup(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String groupOid = req.getParameter("oid");
        if (groupOid == null || groupOid.trim().isEmpty()) {
            rsp.sendRedirect("accessManagement?error=missing"); return;
        }
        groupOid = groupOid.trim();
        RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
        if (assignmentConfig == null) {
            rsp.sendRedirect("accessManagement?error=configError"); return;
        }

        // Find all VIA_ENTRA_GROUP users whose activeGroupOids contains this OID
        // Revoke sessions for those who have no remaining active groups after removal
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
            for (hudson.model.User user : hudson.model.User.getAll()) {
                RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
                if (prop == null || !prop.isViaGroup()) continue;
                if (!prop.getActiveGroupOids().contains(groupOid)) continue;

                List<String> remaining = new ArrayList<>(prop.getActiveGroupOids());
                remaining.remove(groupOid);

                if (remaining.isEmpty()) {
                    // No other groups - revoke all active sessions for this user immediately
                    String uid = user.getId();
                    ActiveSessionManager.getAll().stream()
                            .filter(s -> uid.equals(s.getUserId()))
                            .forEach(s -> ActiveSessionManager.revoke(s.getSessionId()));
                    LOGGER.log(Level.INFO, "Session revoked for {0} - group {1} removed",
                            new Object[]{uid, groupOid});
                }

                // Update activeGroupOids
                try {
                    RotogateUserProperty updated = new RotogateUserProperty(
                            prop.getEntraObjectId(), prop.getEntraUpn());
                    updated.setProvisioningSource("VIA_ENTRA_GROUP");
                    updated.setActiveGroupOids(remaining);
                    updated.setLastLoginAt(prop.getLastLoginAt());
                    updated.setGroupsLastSynced(prop.getGroupsLastSynced());
                    updated.setCachedGroups(new ArrayList<>(prop.getCachedGroups()));
                    user.addProperty(updated);
                    user.save();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Could not update activeGroupOids for " + user.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Error processing group removal sessions", e);
        }

        // Remove all GROUP assignments for this OID
        final String finalGroupOid = groupOid;
        List<RotogateAssignment> toRemove = assignmentConfig.getAssignments().stream()
                .filter(a -> "GROUP".equalsIgnoreCase(a.getAuthType()) && finalGroupOid.equals(a.getUserId()))
                .toList();
        for (RotogateAssignment a : toRemove) {
            assignmentConfig.removeAssignment(finalGroupOid, "GROUP", a.getScope());
        }

        // Remove Hudson.Read grant for this group from global matrix by rebuilding without that entry
        try {
            hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
            if (strat instanceof RotogateAuthorizationStrategy) {
                RotogateAuthorizationStrategy current = (RotogateAuthorizationStrategy) strat;
                RotogateAuthorizationStrategy rebuilt = new RotogateAuthorizationStrategy();
                for (Map.Entry<Permission, Set<PermissionEntry>> e : current.getGrantedPermissionEntries().entrySet()) {
                    for (PermissionEntry pe : e.getValue()) {
                        if (!pe.getSid().equals(groupOid)) {
                            rebuilt.add(e.getKey(), pe);
                        }
                    }
                }
                Jenkins.get().setAuthorizationStrategy(rebuilt);
                Jenkins.get().save();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not remove Hudson.Read for group " + groupOid, e);
        }

        // Remove the group entity
        assignmentConfig.removeGroup(groupOid);

        RotogateAuditLog audit = RotogateAuditLog.get();
        if (audit != null) audit.logUserCreated(Jenkins.getAuthentication2().getName(), "[GROUP REMOVED] " + groupOid);

        rsp.sendRedirect("accessManagement?groupRemoved=true");
    }

    private void grantGlobalRead(String sid, AuthorizationType atype) {
        try {
            hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
            if (!(strat instanceof RotogateAuthorizationStrategy)) return;
            RotogateAuthorizationStrategy rgStrat = (RotogateAuthorizationStrategy) strat;
            Permission readPerm = Permission.fromId("hudson.model.Hudson.Read");
            if (readPerm == null) return;
            PermissionEntry entry = new PermissionEntry(atype, sid);
            rgStrat.add(readPerm, entry);
            Jenkins.get().save();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not auto-grant Hudson.Read to " + sid, e);
        }
    }

    /**
     * Additively grant a set of permission ids at global scope (used by approve-with-a-role for a
     * freshly created account). Mirrors {@link #grantGlobalRead} - additive, so no existing-entry
     * rebuild is needed for a brand-new user.
     */
    private void grantGlobalRole(String sid, AuthorizationType atype, Set<String> permIds) {
        if (permIds == null || permIds.isEmpty()) return;
        try {
            hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
            if (!(strat instanceof RotogateAuthorizationStrategy)) return;
            RotogateAuthorizationStrategy rgStrat = (RotogateAuthorizationStrategy) strat;
            PermissionEntry entry = new PermissionEntry(atype, sid);
            for (String pid : permIds) {
                if (RotogateAuthorizationStrategy.isDangerousPermission(pid)) continue; // never grant admin-equivalent permissions
                Permission p = Permission.fromId(pid);
                if (p != null) rgStrat.add(p, entry);
            }
            Jenkins.get().save();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not grant global role to " + sid, e);
        }
    }

    /** Called from userDetail.jelly - reads the one-time temp password from session. */
    public String getTempPassword() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return null;
        Object pwd = req.getSession().getAttribute("rotogate.tmpPwd");
        Object psid = req.getSession().getAttribute("rotogate.tmpPwdSid");
        String currentSid = req.getParameter("sid");
        if (pwd != null && psid != null && psid.toString().equals(currentSid)) {
            req.getSession().removeAttribute("rotogate.tmpPwd");
            req.getSession().removeAttribute("rotogate.tmpPwdSid");
            return pwd.toString();
        }
        return null;
    }

    private static String generateTempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%";
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the best available last-login timestamp for a user.
     * Prefers RotogateUserProperty.lastLoginAt for Entra users,
     * falls back to LastLoginProperty for all users.
     */
    private static String resolveLastLogin(RotogateUserProperty entraProp,
                                           LastLoginProperty loginProp) {
        return RotogateUserProperty.resolveLastLogin(entraProp, loginProp);
    }

    private static String resolveDisplayName(String sid, AuthorizationType type) {
        if (type == AuthorizationType.USER) {
            User u = User.getById(sid, false);
            if (u != null && u.getFullName() != null && !u.getFullName().equals(sid)) {
                return u.getFullName();
            }
        } else if (type == AuthorizationType.GROUP) {
            RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
            if (config != null) {
                RotogateGroupEntity group = config.findGroup(sid);
                if (group != null) return group.getEffectiveName();
            }
        }
        return sid;
    }

    /**
     * Scans recent builds (last 90 days) across all jobs to find the
     * most recent build triggered by each user.
     * Capped to avoid performance issues on large instances.
     */
    private static Map<String, LastJobInfo> buildLastJobMap() {
        Map<String, LastJobInfo> result = new HashMap<>();
        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);

        try {
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                for (Run<?, ?> run : job.getBuilds()) {
                    // Builds are ordered newest first - stop when we pass the cutoff
                    if (run.getTime().toInstant().isBefore(cutoff)) break;
                    hudson.model.Cause.UserIdCause cause =
                            run.getCause(hudson.model.Cause.UserIdCause.class);
                    if (cause != null && cause.getUserId() != null) {
                        String uid = cause.getUserId();
                        if (!result.containsKey(uid)) {
                            result.put(uid, new LastJobInfo(
                                    job.getFullName(),
                                    run.getTime().toInstant().toString()
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error scanning build history for last job map", e);
        }

        return result;
    }

    private static final int JOB_ACCESS_CAP = 200;

    private static List<JobAccessInfo> buildJobAccessList(String userId) {
        List<JobAccessInfo> result = new ArrayList<>();
        User user = User.getById(userId, false);
        if (user == null) return result;

        Authentication auth;
        try {
            auth = user.impersonate2();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not impersonate user for job access scan: " + userId, e);
            return result;
        }

        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        Set<String> userGroupOids = new HashSet<>();
        RotogateUserProperty ep = user.getProperty(RotogateUserProperty.class);
        if (ep != null) userGroupOids.addAll(ep.getActiveGroupOids());

        List<Job> allJobs = Jenkins.get().getAllItems(Job.class);

        try (ACLContext ignored = ACL.as2(auth)) {
            for (Job job : allJobs) {
                if (result.size() >= JOB_ACCESS_CAP) break;
                boolean read      = job.hasPermission(Item.READ);
                boolean build     = job.hasPermission(Item.BUILD);
                boolean configure = job.hasPermission(Item.CONFIGURE);
                boolean delete    = job.hasPermission(Item.DELETE);
                boolean workspace = job.hasPermission(Item.WORKSPACE);
                if (read || build || configure) {
                    String source = detectSource(job, userId, userGroupOids, auth, config);
                    result.add(new JobAccessInfo(job.getFullName(), read, build, configure, delete, workspace, source));
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error scanning job access for user: " + userId, e);
        }

        result.sort((a, b) -> a.getJobName().compareToIgnoreCase(b.getJobName()));
        return result;
    }

    @SuppressWarnings("rawtypes")
    private static String detectSource(Job job, String userId,
                                        Set<String> userGroupOids,
                                        Authentication auth,
                                        RotogateAssignmentConfig config) {
        String jobFullName = job.getFullName();
        if (config != null) {
            for (RotogateAssignment a : config.getAssignmentsForUser(userId, "USER")) {
                if (!a.isExpired() && rotogateCovers(a, jobFullName)) return "Rotogate";
            }
            for (String gOid : userGroupOids) {
                for (RotogateAssignment a : config.getAssignmentsForUser(gOid, "GROUP")) {
                    if (!a.isExpired() && rotogateCovers(a, jobFullName)) return "Rotogate";
                }
            }
        }
        // Check job itself (already have the object - no lookup needed)
        if (hasMatrixGrant(job, auth)) return "Direct";
        // Check folder ancestors - use SYSTEM context to avoid recursive hasPermission2
        try {
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null) {
                String path = jobFullName;
                int sep = path.lastIndexOf('/');
                while (sep > 0) {
                    path = path.substring(0, sep);
                    final String p = path;
                    hudson.model.AbstractItem folder;
                    try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                        folder = j.getItemByFullName(p, hudson.model.AbstractItem.class);
                    }
                    if (folder != null && hasMatrixGrant(folder, auth)) return "Direct";
                    sep = path.lastIndexOf('/');
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Error walking folder ancestors to detect grant source", e);
        }
        return "Global";
    }

    private static boolean rotogateCovers(RotogateAssignment a, String jobFullName) {
        String scope = a.getScope();
        if (scope.isEmpty()) return true;
        if ("FOLDER".equals(a.getScopeType())) {
            return jobFullName.equals(scope) || jobFullName.startsWith(scope + "/");
        }
        return jobFullName.equals(scope);
    }

    private static boolean hasMatrixGrant(hudson.model.AbstractItem item, Authentication auth) {
        if (!(item instanceof hudson.model.Job)) return false;
        hudson.model.Job<?,?> job = (hudson.model.Job<?,?>) item;
        for (hudson.model.JobProperty<?> prop : job.getAllProperties()) {
            if (prop instanceof hudson.security.AuthorizationMatrixProperty) {
                hudson.security.SidACL sidAcl =
                        ((hudson.security.AuthorizationMatrixProperty) prop).getACL();
                return sidAcl != null && sidAcl.hasPermission2(auth, Item.READ);
            }
        }
        return false;
    }

    private static String deriveStatus(String lastLogin, LastJobInfo lastJob) {
        Instant activeCutoff = Instant.now().minus(activeThresholdDays(), ChronoUnit.DAYS);

        if (lastLogin != null && Instant.parse(lastLogin).isAfter(activeCutoff)) {
            return "Active";
        }
        if (lastJob != null && Instant.parse(lastJob.triggeredAt).isAfter(activeCutoff)) {
            return "Active via Jobs";
        }
        if (lastLogin == null && lastJob == null) {
            return "Never Active";
        }
        return "Inactive";
    }

    private static int statusOrder(String status) {
        switch (status) {
            case "Active":         return 0;
            case "Active via Jobs": return 1;
            case "Inactive":       return 2;
            case "Never Active":   return 3;
            default:               return 4;
        }
    }

    // -------------------------------------------------------------------------
    // Data holders
    // -------------------------------------------------------------------------


    static String relativeTime(String isoStr) {
        if (isoStr == null) return null;
        try {
            long diffSec = java.time.Duration.between(Instant.parse(isoStr), Instant.now()).getSeconds();
            if (diffSec < 60)    return "just now";
            if (diffSec < 3600)  return (diffSec / 60) + "m";
            if (diffSec < 86400) return (diffSec / 3600) + "h";
            long days = diffSec / 86400;
            if (days < 30)  return days + "d";
            if (days < 365) return (days / 30) + "mo";
            return (days / 365) + "y";
        } catch (Exception e) {
            return isoStr;
        }
    }

    static String formatDate(String isoStr) {
        if (isoStr == null) return null;
        try {
            java.time.ZonedDateTime zdt = Instant.parse(isoStr)
                    .atZone(java.time.ZoneId.systemDefault());
            return String.format("%d %s %d, %02d:%02d",
                    zdt.getDayOfMonth(),
                    zdt.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH),
                    zdt.getYear(),
                    zdt.getHour(),
                    zdt.getMinute());
        } catch (Exception e) {
            return isoStr;
        }
    }






    public enum AccessRole {
        ADMIN(
            "hudson.model.Hudson.Administer"
        ),
        DEVELOPER(
            "hudson.model.Hudson.Read",
            "hudson.model.Item.Build",
            "hudson.model.Item.Cancel",
            "hudson.model.Item.Configure",
            "hudson.model.Item.Create",
            "hudson.model.Item.Delete",
            "hudson.model.Item.Move",
            "hudson.model.Item.Read",
            "hudson.model.Item.WipeOut",
            "hudson.model.View.Configure",
            "hudson.model.View.Create",
            "hudson.model.View.Delete",
            "hudson.model.View.Read",
            "hudson.model.Run.Delete",
            "hudson.model.Run.Update"
        ),
        VIEWER(
            "hudson.model.Hudson.Read",
            "hudson.model.Item.Read",
            "hudson.model.View.Read"
        ),
        CUSTOM();

        public final Set<String> permissionIds;

        AccessRole(String... ids) {
            this.permissionIds = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ids)));
        }

        public static AccessRole infer(Set<String> userPerms) {
            Set<String> s = new HashSet<>(userPerms);
            if (s.equals(ADMIN.permissionIds))     return ADMIN;
            if (s.equals(DEVELOPER.permissionIds)) return DEVELOPER;
            if (s.equals(VIEWER.permissionIds))    return VIEWER;
            return CUSTOM;
        }
    }




    // =========================================================================
    // User-centric hierarchy view
    // =========================================================================

    // lgtm false positive: Read-only GET page/AJAX render, already guarded by checkPermission(ADMINISTER); no state mutation, so CSRF protection does not apply.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doCheckAccess(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid = req.getParameter("sid");
        if (sid != null) {
            User u = User.getById(sid, false);
            if (u != null) {
                RotogateUserProperty ep = u.getProperty(RotogateUserProperty.class);
                if (ep != null && ep.isPendingDeletion()) {
                    rsp.sendRedirect(req.getContextPath() + "/manage/rotogate-management/userStatus?info=pendingDeletion");
                    return;
                }
            }
        }
        req.getView(this, "userDetail.jelly").forward(req, rsp);
    }

    /** Returns all GROUP entities for display in Access Management. */
    public List<RotogateGroupEntity> getGroupList() {
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        if (config == null) return Collections.emptyList();
        return config.getGroups();
    }

    /** Returns users provisioned via a specific group OID. */
    public List<UserStatusInfo> getGroupMembers(String groupOid) {
        List<UserStatusInfo> members = new ArrayList<>();
        try (hudson.security.ACLContext ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
            for (hudson.model.User user : hudson.model.User.getAll()) {
                RotogateUserProperty prop = user.getProperty(RotogateUserProperty.class);
                if (prop != null && prop.isViaGroup() && prop.getActiveGroupOids().contains(groupOid)) {
                    String lastLogin = prop.getLastLoginAt();
                    members.add(new UserStatusInfo(user.getId(), user.getFullName(), "Entra", lastLogin, null, null, "active"));
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error fetching group members for " + groupOid, e);
        }
        return members;
    }

    /** Returns the RotogateGroupEntity for a given OID - used by groupDetail.jelly. */
    public RotogateGroupEntity getGroupEntity() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return null;
        String oid = req.getParameter("oid");
        if (oid == null) return null;
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        return config != null ? config.findGroup(oid) : null;
    }

    /** Returns the user hierarchy as a flat list of TreeRow objects (with depth + inherited info). */
    public List<TreeRow> getUserHierarchyFlat() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return Collections.emptyList();
        String sid = req.getParameter("sid");
        String type = req.getParameter("type");
        if (sid == null) return Collections.emptyList();
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        TreeNode root = buildHierarchy(sid, atype);
        List<TreeRow> rows = new ArrayList<>();
        flattenTree(root, 0, null, null, rows);
        return rows;
    }

    /** Returns minimal info about the user being inspected (for the userDetail header). */
    public AccessManagementUserInfo getUserDetailHeader() {
        org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (req == null) return null;
        String sid = req.getParameter("sid");
        String type = req.getParameter("type");
        if (sid == null) return null;
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        // Reuse the access-management list and filter
        for (AccessManagementUserInfo info : getAccessManagementUserList()) {
            if (info.getSid().equals(sid) && info.getType() == atype) return info;
        }
        // Build a minimal placeholder if not found in the list (e.g. user being added)
        String display = resolveDisplayName(sid, atype);
        String ps = "NATIVE";
        String phExpiresAt = null;
        boolean phDisabled = false;
        if (atype == AuthorizationType.USER) {
            User u2 = User.getById(sid, false);
            if (u2 != null) {
                RotogateUserProperty ep2 = u2.getProperty(RotogateUserProperty.class);
                if (isEntraIdentity(ep2)) ps = ep2.getProvisioningSource();
                RotogateAccountExpiryProperty xp2 = u2.getProperty(RotogateAccountExpiryProperty.class);
                if (xp2 != null && xp2.getExpiresAt() != null && !xp2.getExpiresAt().isBlank()) {
                    phExpiresAt = xp2.getExpiresAt();
                    phDisabled = xp2.isDisabled();
                }
            }
        }
        AccessManagementUserInfo placeholder = new AccessManagementUserInfo(sid, display, atype, "NONE",
                Collections.emptySet(), null);
        placeholder.setProvisioningSource(ps);
        placeholder.setAccountExpiresAt(phExpiresAt);
        placeholder.setAccountDisabledByExpiry(phDisabled);
        return placeholder;
    }

    private TreeNode buildHierarchy(String sid, AuthorizationType atype) {
        TreeNode root = new TreeNode("", "Jenkins (root)", "root");

        // Direct grants at root come from the RotogateAuthorizationStrategy (global)
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (strat instanceof GlobalMatrixAuthorizationStrategy) {
            Set<String> rootPerms = collectDirectPermsForUser(
                    ((GlobalMatrixAuthorizationStrategy) strat).getGrantedPermissionEntries(), sid, atype);
            root.directPerms = new ArrayList<>(rootPerms);
            root.directRole = inferRoleAtRoot(rootPerms);
        }

        // Recurse top-level items
        for (Item item : Jenkins.get().getItems()) {
            TreeNode child = buildItemNode(item, sid, atype);
            if (child != null) root.children.add(child);
        }

        // Compute effective role with simple inheritance: directRole or parent.effectiveRole
        computeEffectiveRoles(root, null);
        return root;
    }

    private TreeNode buildItemNode(Item item, String sid, AuthorizationType atype) {
        boolean isGroup = item instanceof ItemGroup;
        boolean isJob   = item instanceof Job;
        String type = isGroup ? "folder" : (isJob ? "job" : "other");
        TreeNode node = new TreeNode(item.getFullName(), item.getName(), type);

        Set<String> direct = new HashSet<>();
        if (isJob) {
            hudson.security.AuthorizationMatrixProperty prop =
                    ((Job<?, ?>) item).getProperty(hudson.security.AuthorizationMatrixProperty.class);
            if (prop != null) {
                direct.addAll(collectDirectPermsForUser(prop.getGrantedPermissionEntries(), sid, atype));
            }
        }
        if (item instanceof AbstractFolder) {
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                    ((AbstractFolder<?>) item).getProperties().get(
                            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
            if (fp != null) {
                direct.addAll(collectDirectPermsForUser(fp.getGrantedPermissionEntries(), sid, atype));
            }
        }
        node.directPerms = new ArrayList<>(direct);
        node.directRole = inferRoleAtItem(direct);

        if (isGroup) {
            for (Object child : ((ItemGroup<?>) item).getItems()) {
                TreeNode cn = buildItemNode((Item) child, sid, atype);
                if (cn != null) node.children.add(cn);
            }
        }
        return node;
    }

    private static Set<String> collectDirectPermsForUser(
            Map<Permission, Set<PermissionEntry>> entries, String sid, AuthorizationType atype) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<Permission, Set<PermissionEntry>> e : entries.entrySet()) {
            for (PermissionEntry pe : e.getValue()) {
                if (pe.getSid().equals(sid) && pe.getType() == atype) {
                    out.add(e.getKey().getId());
                }
            }
        }
        return out;
    }

    private static String inferRoleAtRoot(Set<String> perms) {
        if (perms == null || perms.isEmpty()) return null;
        AccessRole r = AccessRole.infer(perms);
        return r.name();
    }

    private static String inferRoleAtItem(Set<String> perms) {
        if (perms == null || perms.isEmpty()) return null;
        if (perms.equals(ScopedRole.CONFIGURE.permissionIds)) return "CONFIGURE";
        if (perms.equals(ScopedRole.BUILD.permissionIds))     return "BUILD";
        if (perms.equals(ScopedRole.READ.permissionIds))      return "READ";
        return "CUSTOM";
    }

    private void computeEffectiveRoles(TreeNode node, String parentEffective) {
        node.effectiveRole = node.directRole != null ? node.directRole : parentEffective;
        for (TreeNode child : node.children) {
            computeEffectiveRoles(child, node.effectiveRole);
        }
    }

    private void flattenTree(TreeNode node, int depth, String parentEffective,
                             String parentEffectivePath, List<TreeRow> out) {
        TreeRow row = new TreeRow();
        row.node = node;
        row.depth = depth;
        row.inheritedRole = (node.directRole == null) ? parentEffective : null;
        row.inheritedFromPath = (node.directRole == null) ? parentEffectivePath : null;
        out.add(row);
        String childInheritRole = node.directRole != null ? node.directRole : parentEffective;
        String childInheritPath = node.directRole != null ? node.path : parentEffectivePath;
        for (TreeNode child : node.children) {
            flattenTree(child, depth + 1, childInheritRole, childInheritPath, out);
        }
    }

    // ─── POST: Set role at any node (root, folder, job) ─────────────────────

    @POST
    public void doSetNodePermission(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid  = req.getParameter("sid");
        String type = req.getParameter("type");
        String path = req.getParameter("path");      // "" for root
        String role = req.getParameter("role");      // ADMIN/DEVELOPER/VIEWER/CUSTOM at root, or CONFIGURE/BUILD/READ at items

        if (sid == null || sid.trim().isEmpty() || role == null) {
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&error=missing");
            return;
        }
        sid = sid.trim();
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        // Resolve target permission set
        Set<String> permIds;
        if (path == null || path.isEmpty()) {
            // Root: AccessRole bucket
            try {
                permIds = AccessRole.valueOf(role.toUpperCase()).permissionIds;
            } catch (IllegalArgumentException ex) {
                rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&error=invalidRole");
                return;
            }
            if (permIds.isEmpty()) {
                rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&error=noPermissions");
                return;
            }
            applyRootPermissions(sid, atype, permIds, rsp, false);
        } else {
            // Item: ScopedRole bucket
            try {
                permIds = ScopedRole.valueOf(role.toUpperCase()).permissionIds;
            } catch (IllegalArgumentException ex) {
                rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&error=invalidRole");
                return;
            }
            Item item = Jenkins.get().getItemByFullName(path);
            if (item == null) {
                rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&error=notFound");
                return;
            }
            applyItemPermissions(item, sid, atype, permIds);
            autoGrantParentRead(item, sid, atype);
            RotogateAuditLog.get().logPermissionGranted(
                    Jenkins.getAuthentication2().getName(), sid, path, "item role → " + role);
        }
        rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&saved=true");
    }

    @POST
    public void doRemoveNodePermission(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String sid  = req.getParameter("sid");
        String type = req.getParameter("type");
        String path = req.getParameter("path");

        if (sid == null) {
            rsp.sendRedirect("userDetail?sid=&type=" + enc(type) + "&error=missing");
            return;
        }
        AuthorizationType atype = "GROUP".equalsIgnoreCase(type)
                ? AuthorizationType.GROUP : AuthorizationType.USER;

        if (path == null || path.isEmpty()) {
            // Remove all global (root) grants
            applyRootPermissions(sid, atype, Collections.emptySet(), rsp, true);
        } else {
            Item item = Jenkins.get().getItemByFullName(path);
            if (item != null) {
                applyItemPermissions(item, sid, atype, Collections.emptySet());
                cleanupParentRead(item, sid, atype);
                RotogateAuditLog.get().logPermissionRevoked(
                        Jenkins.getAuthentication2().getName(), sid, path, "item permissions");
            }
            rsp.sendRedirect("userDetail?sid=" + enc(sid) + "&type=" + enc(type) + "&saved=true");
        }
    }

    /** Rebuild the global RotogateAuthorizationStrategy with this user's permissions replaced. */
    private void applyRootPermissions(String sid, AuthorizationType atype, Set<String> permIds,
                                      StaplerResponse2 rsp, boolean removing) throws Exception {
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (!(strat instanceof RotogateAuthorizationStrategy)) {
            rsp.sendRedirect(detailUrl(sid, atype, "error=notRotogate"));
            return;
        }
        RotogateAuthorizationStrategy current = (RotogateAuthorizationStrategy) strat;
        RotogateAuthorizationStrategy rebuilt = new RotogateAuthorizationStrategy();
        for (Map.Entry<Permission, Set<PermissionEntry>> e : current.getGrantedPermissionEntries().entrySet()) {
            for (PermissionEntry pe : e.getValue()) {
                if (!(pe.getSid().equals(sid) && pe.getType() == atype)) {
                    rebuilt.add(e.getKey(), pe);
                }
            }
        }
        if (!permIds.isEmpty()) {
            PermissionEntry entry = atype == AuthorizationType.GROUP
                    ? PermissionEntry.group(sid) : PermissionEntry.user(sid);
            for (String pid : permIds) {
                if (RotogateAuthorizationStrategy.isDangerousPermission(pid)) continue; // never grant admin-equivalent permissions
                Permission p = Permission.fromId(pid);
                if (p != null) rebuilt.add(p, entry);
            }
        }
        // Global Hudson.Read is the login prerequisite; the folder/job ACLs then decide what the user
        // actually sees. A root-scope role that omits Read (e.g. a job-only role) would otherwise wipe
        // the Read granted at account creation and lock the user out, so re-assert it on every grant.
        if (!removing && !permIds.isEmpty()) {
            Permission read = Permission.fromId("hudson.model.Hudson.Read");
            if (read != null) {
                PermissionEntry readEntry = atype == AuthorizationType.GROUP
                        ? PermissionEntry.group(sid) : PermissionEntry.user(sid);
                rebuilt.add(read, readEntry);
            }
        }
        Set<PermissionEntry> adminSet = rebuilt.getGrantedPermissionEntries().get(Jenkins.ADMINISTER);
        if (adminSet == null || adminSet.isEmpty()) {
            rsp.sendRedirect(detailUrl(sid, atype, "error=lastAdmin"));
            return;
        }
        Jenkins.get().setAuthorizationStrategy(rebuilt);
        Jenkins.get().save();
        String actor = Jenkins.getAuthentication2().getName();
        if (removing) {
            RotogateAuditLog.get().logPermissionRevoked(actor, sid, "global (root)", "all root permissions");
        } else {
            RotogateAuditLog.get().logPermissionGranted(actor, sid, "global (root)",
                    "root permissions (" + permIds.size() + ")");
        }
        rsp.sendRedirect(detailUrl(sid, atype, "saved=true"));
    }

    private String detailUrl(String sid, AuthorizationType atype, String extra) {
        if (atype == AuthorizationType.GROUP) {
            return "groupDetail?oid=" + enc(sid) + (extra != null ? "&" + extra : "");
        }
        return "userDetail?sid=" + enc(sid) + "&type=USER" + (extra != null ? "&" + extra : "");
    }

    /**
     * Apply permissions on a Job or Folder. permIds.isEmpty() means remove all grants for this user.
     * Global-scope permissions (Hudson.Read, View.Read, etc.) are routed to the global strategy;
     * only item-scoped permissions are written into the job/folder property.
     */
    @SuppressWarnings("unchecked")
    private void applyItemPermissions(Item item, String sid, AuthorizationType atype, Set<String> permIds) throws Exception {
        PermissionEntry entry = atype == AuthorizationType.GROUP
                ? PermissionEntry.group(sid) : PermissionEntry.user(sid);

        // Only item/run-scoped permissions belong in a job/folder property.
        // Global-scope permissions (Hudson.Read, View.Read) are silently ignored here -
        // login access is managed separately via Add User / global grants.
        Set<String> itemPermIds = new HashSet<>();
        for (String pid : permIds) {
            Permission p = Permission.fromId(pid);
            if (p != null && p.isContainedBy(hudson.security.PermissionScope.ITEM)) {
                itemPermIds.add(pid);
            }
        }
        // For revoke (permIds empty), effectivePerms stays empty → mutate removes all for this user.
        Set<String> effectivePermIds = permIds.isEmpty() ? Collections.emptySet() : itemPermIds;

        if (item instanceof Job) {
            Job<?, ?> job = (Job<?, ?>) item;
            hudson.security.AuthorizationMatrixProperty prop =
                    job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
            if (prop == null) {
                if (effectivePermIds.isEmpty()) return;
                Map<Permission, Set<PermissionEntry>> map = new HashMap<>();
                for (String pid : effectivePermIds) {
                    Permission p = Permission.fromId(pid);
                    if (p != null) map.computeIfAbsent(p, k -> new HashSet<>()).add(entry);
                }
                prop = new hudson.security.AuthorizationMatrixProperty(map,
                        new org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy());
                job.addProperty(prop);
            } else {
                mutateGrantedPermissions(prop, sid, atype, entry, effectivePermIds);
            }
            job.save();
            return;
        }

        if (item instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) item;
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                    folder.getProperties().get(
                            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
            if (fp == null) {
                if (effectivePermIds.isEmpty()) return;
                fp = new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                        new java.util.HashMap<>());
                folder.addProperty(fp);
            }
            mutateGrantedPermissions(fp, sid, atype, entry, effectivePermIds);
            folder.save();
        }
    }

    /** Ensures a single global-scope permission exists in the global strategy (idempotent). */
    private void applyGlobalPermission(String sid, AuthorizationType atype, PermissionEntry entry, Permission perm) {
        hudson.security.AuthorizationStrategy strat = Jenkins.get().getAuthorizationStrategy();
        if (!(strat instanceof RotogateAuthorizationStrategy)) return;
        RotogateAuthorizationStrategy strategy = (RotogateAuthorizationStrategy) strat;
        strategy.add(perm, entry);
        try { Jenkins.get().save(); } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save global strategy after adding " + perm.getId() + " for " + sid, e);
        }
    }

    /**
     * Mutates the grantedPermissions map of an AuthorizationMatrixProperty in-place via reflection.
     * This avoids the addProperty/removeProperty unreliability on WorkflowJob and folders.
     * If atype is null, removes entries for sid regardless of type (used for purge).
     * entry may be null when permIds is empty (remove-only operation).
     */
    @SuppressWarnings("unchecked")
    private void mutateGrantedPermissions(Object prop, String sid, AuthorizationType atype,
                                           PermissionEntry entry, Set<String> permIds) throws Exception {
        java.lang.reflect.Field gpField = null;
        Class<?> cls = prop.getClass();
        while (cls != null && gpField == null) {
            try { gpField = cls.getDeclaredField("grantedPermissions"); }
            catch (NoSuchFieldException e) { cls = cls.getSuperclass(); }
        }
        if (gpField == null)
            throw new Exception("grantedPermissions field not found on " + prop.getClass().getName());
        gpField.setAccessible(true);
        Map<Permission, Set<PermissionEntry>> gp =
                (Map<Permission, Set<PermissionEntry>>) gpField.get(prop);
        for (Set<PermissionEntry> pes : gp.values()) {
            pes.removeIf(pe -> pe.getSid().equals(sid) && (atype == null || pe.getType() == atype));
        }
        gp.entrySet().removeIf(e -> e.getValue().isEmpty());
        for (String pid : permIds) {
            Permission p = Permission.fromId(pid);
            if (p != null) gp.computeIfAbsent(p, k -> new HashSet<>()).add(entry);
        }
    }

    /** Auto-grant Item.Read on parent folders so the user can navigate to the granted item. */
    private void autoGrantParentRead(Item item, String sid, AuthorizationType atype) throws Exception {
        Set<String> readOnly = new HashSet<>();
        readOnly.add("hudson.model.Item.Read");
        ItemGroup<?> parent = item.getParent();
        while (parent instanceof Item) {
            Item parentItem = (Item) parent;
            try {
                applyItemPermissions(parentItem, sid, atype, readOnly);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Best-effort parent Item.Read grant failed for " + parentItem.getFullName(), e);
            }
            if (parentItem instanceof AbstractFolder) {
                setNonInheriting((AbstractFolder<?>) parentItem);
            }
            parent = parentItem.getParent();
        }
    }

    /** Sets NonInheritingStrategy on a folder if not already set - idempotent, never reverts. */
    private void setNonInheriting(AbstractFolder<?> folder) {
        try {
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                    folder.getProperties().get(
                            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
            if (fp == null) return;
            if (fp.getInheritanceStrategy() instanceof org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy) return;
            fp.setInheritanceStrategy(new org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy());
            folder.save();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to set NonInheriting on folder " + folder.getFullName(), e);
        }
    }

    /** Walks up the parent chain after a revoke and removes auto-granted Item.Read from folders
     *  that no longer have any descendant grants for this user. */
    private void cleanupParentRead(Item revokedItem, String sid, AuthorizationType atype) {
        ItemGroup<?> parent = revokedItem.getParent();
        while (parent instanceof AbstractFolder) {
            AbstractFolder<?> folder = (AbstractFolder<?>) parent;
            boolean hasDescendants = false;
            for (Job<?, ?> job : folder.getAllItems(Job.class)) {
                hudson.security.AuthorizationMatrixProperty p =
                        job.getProperty(hudson.security.AuthorizationMatrixProperty.class);
                if (p != null && hasUserGrant(p.getGrantedPermissionEntries(), sid, atype)) {
                    hasDescendants = true; break;
                }
            }
            if (!hasDescendants) {
                for (AbstractFolder<?> sub : folder.getAllItems(AbstractFolder.class)) {
                    com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                            sub.getProperties().get(
                                    com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
                    if (fp != null && hasUserGrant(fp.getGrantedPermissionEntries(), sid, atype)) {
                        hasDescendants = true; break;
                    }
                }
            }
            if (!hasDescendants) {
                com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                        folder.getProperties().get(
                                com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
                if (fp != null) {
                    try {
                        mutateGrantedPermissions(fp, sid, atype, null, Collections.emptySet());
                        folder.save();
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to cleanup parent read on " + folder.getFullName(), e);
                    }
                }
                parent = folder.getParent();
            } else {
                break;
            }
        }
    }

    private boolean hasUserGrant(Map<Permission, Set<PermissionEntry>> entries, String sid, AuthorizationType atype) {
        for (Set<PermissionEntry> pes : entries.values()) {
            for (PermissionEntry pe : pes) {
                if (pe.getSid().equals(sid) && pe.getType() == atype) return true;
            }
        }
        return false;
    }

    private static String enc(String s) {
        try { return s == null ? "" : java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
    private static String type(AuthorizationType atype) {
        return atype == AuthorizationType.GROUP ? "GROUP" : "USER";
    }

    /** Converts an HTML date input value (YYYY-MM-DD) to an ISO-8601 instant string, or null if blank. */
    private static String toInstantString(String dateParam) {
        if (dateParam == null || dateParam.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(dateParam.trim())
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .plusDays(1)          // expires at end of the selected day (midnight of next day UTC)
                    .toInstant()
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Inner classes for hierarchy view ────────────────────────────────────




    public enum ScopedRole {
        READ(
            "hudson.model.Item.Read"
        ),
        BUILD(
            "hudson.model.Item.Read",
            "hudson.model.Item.Build",
            "hudson.model.Item.Cancel"
        ),
        CONFIGURE(
            "hudson.model.Item.Read",
            "hudson.model.Item.Build",
            "hudson.model.Item.Cancel",
            "hudson.model.Item.Configure",
            "hudson.model.Item.Delete"
        );

        public final Set<String> permissionIds;
        ScopedRole(String... ids) {
            this.permissionIds = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ids)));
        }
    }

}
