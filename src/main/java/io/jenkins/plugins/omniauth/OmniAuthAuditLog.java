package io.jenkins.plugins.omniauth;

import hudson.Extension;
import jenkins.model.Jenkins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class OmniAuthAuditLog {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthAuditLog.class.getName());
    // Runaway guard: even with "keep forever" retention, never retain more than this many month-files.
    private static final int HARD_MAX_MONTHS = 120;
    private static final int DEFAULT_RETENTION_DAYS = 365;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    public static OmniAuthAuditLog get() {
        return Jenkins.get().getExtensionList(OmniAuthAuditLog.class).get(0);
    }

    // -------------------------------------------------------------------------
    // Convenience log methods
    // -------------------------------------------------------------------------

    public void logGrant(String by, String user, String role, String scope, String expiresAt) {
        Map<String, String> e = event("grant");
        e.put("by", by); e.put("user", user); e.put("role", role); e.put("scope", scopeLabel(scope));
        if (expiresAt != null && !expiresAt.isBlank()) e.put("expiresAt", expiresAt);
        write(e);
    }

    public void logRevoke(String by, String user, String scope, String role) {
        Map<String, String> e = event("revoke");
        e.put("by", by); e.put("user", user); e.put("scope", scopeLabel(scope));
        if (role != null && !role.isBlank()) e.put("role", role);
        write(e);
    }

    public void logEdit(String by, String user, String scope, String oldRole, String newRole, String expiresAt) {
        Map<String, String> e = event("edit");
        e.put("by", by); e.put("user", user); e.put("scope", scopeLabel(scope));
        e.put("oldRole", oldRole); e.put("newRole", newRole);
        if (expiresAt != null && !expiresAt.isBlank()) e.put("expiresAt", expiresAt);
        write(e);
    }

    public void logExpired(String user, String scope) {
        Map<String, String> e = event("expired");
        e.put("user", user); e.put("scope", scopeLabel(scope));
        write(e);
    }

    public void logRoleCreated(String by, String name) {
        Map<String, String> e = event("role_created");
        e.put("by", by); e.put("role", name);
        write(e);
    }

    public void logRoleEdited(String by, String name) {
        Map<String, String> e = event("role_edited");
        e.put("by", by); e.put("role", name);
        write(e);
    }

    public void logRoleDeleted(String by, String name) {
        Map<String, String> e = event("role_deleted");
        e.put("by", by); e.put("role", name);
        write(e);
    }

    public void logLoginSuccess(String user, String authType, String ip) {
        Map<String, String> e = event("login_success");
        e.put("user", user); e.put("authType", authType); e.put("ip", normalizeIp(ip));
        write(e);
    }

    public void logLoginFailure(String username, String ip) {
        Map<String, String> e = event("login_failure");
        e.put("user", username); e.put("ip", normalizeIp(ip));
        write(e);
    }

    public void logUserCreated(String by, String user) {
        Map<String, String> e = event("user_created");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logUserDeleted(String by, String user) {
        Map<String, String> e = event("user_deleted");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logBreakGlassActivate(String by, String reason) {
        Map<String, String> e = event("break_glass_activate");
        e.put("by", by); e.put("reason", reason);
        write(e);
    }

    public void logBreakGlassFailed(String by, String reason) {
        Map<String, String> e = event("break_glass_failed");
        e.put("by", by); e.put("reason", reason);
        write(e);
    }

    public void logBreakGlassDeactivate(String by) {
        Map<String, String> e = event("break_glass_deactivate");
        e.put("by", by);
        write(e);
    }

    public void logBreakGlassEnrolled(String by) {
        Map<String, String> e = event("break_glass_enrolled");
        e.put("by", by);
        write(e);
    }

    public void logBreakGlassUnenrolled(String by) {
        Map<String, String> e = event("break_glass_unenrolled");
        e.put("by", by);
        write(e);
    }

    public void logJitRequested(String requesterId, String scope, String reason, int durationHours) {
        Map<String, String> e = event("jit_requested");
        e.put("user", requesterId); e.put("scope", scopeLabel(scope));
        e.put("reason", reason != null ? reason : ""); e.put("durationHours", String.valueOf(durationHours));
        write(e);
    }

    public void logJitApproved(String approverId, String requesterId, String scope, int durationHours) {
        Map<String, String> e = event("jit_approved");
        e.put("by", approverId); e.put("user", requesterId);
        e.put("scope", scopeLabel(scope)); e.put("durationHours", String.valueOf(durationHours));
        write(e);
    }

    public void logJitDenied(String approverId, String requesterId, String scope, String comment) {
        Map<String, String> e = event("jit_denied");
        e.put("by", approverId); e.put("user", requesterId); e.put("scope", scopeLabel(scope));
        if (comment != null && !comment.isBlank()) e.put("reason", comment);
        write(e);
    }

    public void logJitExpired(String requesterId, String scope) {
        Map<String, String> e = event("jit_expired");
        e.put("user", requesterId); e.put("scope", scopeLabel(scope));
        write(e);
    }

    public void logJitRevoked(String by, String requesterId, String scope) {
        Map<String, String> e = event("jit_revoked");
        e.put("by", by); e.put("user", requesterId); e.put("scope", scopeLabel(scope));
        write(e);
    }

    public void logJitCancelled(String requesterId, String scope) {
        Map<String, String> e = event("jit_cancelled");
        e.put("user", requesterId); e.put("scope", scopeLabel(scope));
        write(e);
    }

    public void logReviewConfirmed(String by, String user, String role, String scope) {
        Map<String, String> e = event("review_confirmed");
        e.put("by", by); e.put("user", user);
        if (role != null && !role.isBlank()) e.put("role", role);
        e.put("scope", scopeLabel(scope));
        write(e);
    }

    // --- Matrix / permission mutations (parallel to the assignment grant/revoke path) ---

    public void logPermissionGranted(String by, String user, String scope, String detail) {
        Map<String, String> e = event("permission_granted");
        e.put("by", by); e.put("user", user); e.put("scope", scopeLabel(scope));
        if (detail != null && !detail.isBlank()) e.put("detail", detail);
        write(e);
    }

    public void logPermissionRevoked(String by, String user, String scope, String detail) {
        Map<String, String> e = event("permission_revoked");
        e.put("by", by); e.put("user", user); e.put("scope", scopeLabel(scope));
        if (detail != null && !detail.isBlank()) e.put("detail", detail);
        write(e);
    }

    // --- Security-response actions ---

    public void logSessionRevoked(String by, String user) {
        Map<String, String> e = event("session_revoked");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logBruteForceCleared(String by, String user) {
        Map<String, String> e = event("bruteforce_cleared");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logAuditPurged(String by, int count) {
        Map<String, String> e = event("audit_purged");
        e.put("by", by); e.put("count", String.valueOf(count));
        write(e);
    }

    // --- Configuration changes ---

    public void logConfigChanged(String by, String area, String detail) {
        Map<String, String> e = event("config_changed");
        e.put("by", by); e.put("area", area);
        if (detail != null && !detail.isBlank()) e.put("detail", detail);
        write(e);
    }

    public void logConfigExported(String by) {
        Map<String, String> e = event("config_exported");
        e.put("by", by);
        write(e);
    }

    public void logConfigImported(String by, String detail) {
        Map<String, String> e = event("config_imported");
        e.put("by", by);
        if (detail != null && !detail.isBlank()) e.put("detail", detail);
        write(e);
    }

    public void logConfigReverted(String by, String detail) {
        Map<String, String> e = event("config_reverted");
        e.put("by", by);
        if (detail != null && !detail.isBlank()) e.put("detail", detail);
        write(e);
    }

    // --- User lifecycle (pending deletion) ---

    public void logUserMarkedForDeletion(String by, String user) {
        Map<String, String> e = event("user_marked_deletion");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logUserUnmarkedForDeletion(String by, String user) {
        Map<String, String> e = event("user_unmarked_deletion");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logPasswordChanged(String user) {
        Map<String, String> e = event("password_changed");
        e.put("user", user);
        write(e);
    }

    // --- Provisioning lifecycle ---

    public void logProvisioningRequested(String user, String authType) {
        Map<String, String> e = event("provisioning_requested");
        e.put("user", user);
        if (authType != null && !authType.isBlank()) e.put("authType", authType);
        write(e);
    }

    public void logProvisioningRejected(String by, String user) {
        Map<String, String> e = event("provisioning_rejected");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    public void logActivationResent(String by, String user) {
        Map<String, String> e = event("activation_resent");
        e.put("by", by); e.put("user", user);
        write(e);
    }

    // --- JIT request that timed out before approval (distinct from an active grant expiring) ---

    public void logJitTimedOut(String requesterId, String scope) {
        Map<String, String> e = event("jit_timed_out");
        e.put("user", requesterId); e.put("scope", scopeLabel(scope));
        write(e);
    }

    // -------------------------------------------------------------------------
    // Read for UI — returns last maxLines events across current + previous month
    // -------------------------------------------------------------------------

    public int[] getFailureCountsByDay(int days) {
        return countsByDay("login_failure", days);
    }

    /** Daily counts of successful logins over the last {@code days} days, oldest-first. */
    public int[] getLoginCountsByDay(int days) {
        return countsByDay("login_success", days);
    }

    /**
     * Per-day login series over the last {@code days} days, oldest-first. Each row is
     * {@code [ssoSuccess, nativeSuccess, failure]} — successes split by the recorded authType
     * ("Entra" ⇒ SSO, anything else ⇒ native), so the dashboard can show the SSO/native mix.
     */
    public int[][] getLoginSeriesByDay(int days) {
        int[][] out = new int[days][3];
        try {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            for (Map<String, String> e : readRecent(5000)) {
                String action = e.get("action");
                boolean success = "login_success".equals(action);
                boolean failure = "login_failure".equals(action);
                if (!success && !failure) continue;
                String ts = e.get("ts");
                if (ts == null) continue;
                try {
                    java.time.LocalDate d = java.time.Instant.parse(ts)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate();
                    long ago = java.time.temporal.ChronoUnit.DAYS.between(d, today);
                    if (ago < 0 || ago >= days) continue;
                    int idx = (int) (days - 1 - ago);
                    if (failure) {
                        out[idx][2]++;
                    } else if ("Entra".equalsIgnoreCase(e.get("authType"))) {
                        out[idx][0]++;
                    } else {
                        out[idx][1]++;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Buckets events of one action into per-day counts (index 0 = oldest, last = today), UTC. */
    private int[] countsByDay(String action, int days) {
        int[] counts = new int[days];
        try {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            List<Map<String, String>> entries = readRecent(5000);
            for (Map<String, String> entry : entries) {
                if (!action.equals(entry.get("action"))) continue;
                String ts = entry.get("ts");
                if (ts == null) continue;
                try {
                    java.time.LocalDate entryDate = java.time.Instant.parse(ts)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate();
                    long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(entryDate, today);
                    if (daysAgo >= 0 && daysAgo < days) counts[(int)(days - 1 - daysAgo)]++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return counts;
    }

    public synchronized List<Map<String, String>> readRecent(int maxLines) {
        File logsDir = logsDir();
        if (logsDir == null) return Collections.emptyList();

        File[] files = logsDir.listFiles((d, n) -> n.startsWith("omniauth-audit-") && n.endsWith(".json"));
        if (files == null) return Collections.emptyList();
        List<File> sorted = new ArrayList<>();
        for (File f : files) sorted.add(f);
        sorted.sort((a, b) -> b.getName().compareTo(a.getName())); // newest month first

        // Accumulate the newest maxLines events, newest-first. Walk months newest→oldest and, within
        // each month's file, lines newest→oldest, appending until the cap is reached.
        List<Map<String, String>> result = new ArrayList<>();
        for (File f : sorted) {
            if (result.size() >= maxLines) break;
            List<String> fileLines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) fileLines.add(line);
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to read audit log: " + f.getName(), ex);
                continue;
            }
            Collections.reverse(fileLines); // newest line first within the file
            for (String line : fileLines) {
                if (result.size() >= maxLines) break;
                Map<String, String> parsed = parseLine(line);
                if (parsed != null) result.add(parsed);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Map<String, String> event(String action) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("ts", java.time.Instant.now().toString());
        e.put("action", action);
        try {
            org.kohsuke.stapler.StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
            if (req != null) {
                String remote = req.getRemoteAddr();
                String forwarded = req.getHeader("X-Forwarded-For");
                // Only trust X-Forwarded-For when the direct connection is from a reverse proxy
                // (loopback / private range). Otherwise the header is client-controlled and an
                // authenticated user could forge the IP recorded against their own audit events.
                String raw = (forwarded != null && !forwarded.isBlank() && isTrustedProxyPeer(remote))
                        ? forwarded.split(",")[0].trim()
                        : remote;
                String ip = normalizeIp(raw);
                if (ip != null && !ip.isBlank()) e.put("ip", ip);
            }
        } catch (Exception ignored) {}
        return e;
    }

    public synchronized int purgeAll() {
        File dir = logsDir();
        if (dir == null) return 0;
        File[] files = dir.listFiles((d, n) -> n.startsWith("omniauth-audit-") && n.endsWith(".json"));
        if (files == null) return 0;
        int deleted = 0;
        for (File f : files) {
            if (f.delete()) deleted++;
        }
        return deleted;
    }

    private String scopeLabel(String scope) {
        return (scope == null || scope.isBlank()) ? "global" : scope;
    }

    private String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        String s = ip.replaceAll("[\\[\\]]", ""); // strip IPv6 brackets
        if (s.equals("::1") || s.equals("0:0:0:0:0:0:0:1") || s.equals("127.0.0.1")) return "localhost";
        return s;
    }

    /**
     * True when the direct peer looks like a trusted reverse proxy (loopback or a private /
     * link-local range), in which case its X-Forwarded-For header can be believed. A public
     * peer address is treated as untrusted, so its XFF header is ignored. Parses literal IPs
     * only (no DNS).
     */
    /**
     * True when {@code addr} (the direct TCP peer) is a loopback/private/link-local address, i.e.
     * a reverse proxy we can trust to have set X-Forwarded-For. Package-private so the login
     * capture paths (LoginContextFilter / LastLoginListener) apply the same gate as the audit log,
     * rather than trusting a client-supplied XFF header unconditionally.
     */
    static boolean isTrustedProxyPeer(String addr) {
        if (addr == null || addr.isBlank()) return false;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(addr.replaceAll("[\\[\\]]", ""));
            if (a.isLoopbackAddress() || a.isAnyLocalAddress()
                    || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
                return true;
            }
            // IPv6 unique-local (fc00::/7) — isSiteLocalAddress only covers the deprecated
            // fec0::/10, so match the modern ULA range used by real dual-stack deployments.
            byte[] b = a.getAddress();
            return b.length == 16 && (b[0] & 0xFE) == 0xFC;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void write(Map<String, String> fields) {
        File logFile = currentLogFile();
        if (logFile == null) return;
        try {
            logFile.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                pw.println(toJson(fields));
            }
            cleanupOldFiles();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to write audit log entry", e);
        }
    }

    private File currentLogFile() {
        File dir = logsDir();
        if (dir == null) return null;
        String month = YearMonth.now().format(MONTH_FMT);
        return new File(dir, "omniauth-audit-" + month + ".json");
    }

    private File logsDir() {
        try {
            return new File(Jenkins.get().getRootDir(), "logs");
        } catch (Exception e) {
            return null;
        }
    }

    /** Configured retention in days (0 = keep forever); falls back to the default if config is unavailable. */
    private int configuredRetentionDays() {
        try {
            OmniAuthGlobalConfig c = OmniAuthGlobalConfig.get();
            return c != null ? c.getAuditRetentionDays() : DEFAULT_RETENTION_DAYS;
        } catch (Exception e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /**
     * Cheap write-path cleanup: deletes whole month-files entirely older than the retention window
     * (month-granular, filename-based — no file reads). The boundary month is kept; its stale lines
     * are trimmed precisely by {@link #pruneOlderThan} on the daily retention worker. A hard ceiling
     * guards against runaway growth even when retention is "keep forever".
     */
    private void cleanupOldFiles() {
        File dir = logsDir();
        if (dir == null) return;
        File[] files = dir.listFiles((d, n) -> n.startsWith("omniauth-audit-") && n.endsWith(".json"));
        if (files == null) return;
        List<File> sorted = new ArrayList<>();
        for (File f : files) sorted.add(f);
        sorted.sort((a, b) -> a.getName().compareTo(b.getName())); // oldest first (names are zero-padded)

        int days = configuredRetentionDays();
        if (days > 0) {
            // Zero-padded YYYY-MM names compare lexically in chronological order.
            String cutoffName = "omniauth-audit-" + YearMonth.from(
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days)).format(MONTH_FMT) + ".json";
            java.util.Iterator<File> it = sorted.iterator();
            while (it.hasNext()) {
                File f = it.next();
                if (f.getName().compareTo(cutoffName) < 0) { f.delete(); it.remove(); }
            }
        }
        while (sorted.size() > HARD_MAX_MONTHS) {
            sorted.get(0).delete();
            sorted.remove(0);
        }
    }

    /**
     * Precisely prunes every event older than {@code retentionDays} (0 = keep forever): whole files
     * that end up empty are deleted, the boundary file is rewritten keeping only recent lines.
     * Returns the number of events removed. Run from the daily retention worker.
     */
    public synchronized int pruneOlderThan(int retentionDays) {
        if (retentionDays <= 0) return 0;
        File dir = logsDir();
        if (dir == null) return 0;
        File[] files = dir.listFiles((d, n) -> n.startsWith("omniauth-audit-") && n.endsWith(".json"));
        if (files == null) return 0;
        java.time.Instant cutoff = java.time.Instant.now().minus(retentionDays, java.time.temporal.ChronoUnit.DAYS);
        int removed = 0;
        for (File f : files) {
            List<String> keep = new ArrayList<>();
            int fileRemoved = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String ts = parseLine(line) != null ? parseLine(line).get("ts") : null;
                    boolean old = false;
                    if (ts != null) {
                        try { old = java.time.Instant.parse(ts).isBefore(cutoff); } catch (Exception ignored) {}
                    }
                    if (old) fileRemoved++;
                    else keep.add(line);
                }
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Audit retention: failed to read " + f.getName(), ex);
                continue;
            }
            if (fileRemoved == 0) continue;
            if (keep.isEmpty()) {
                if (f.delete()) removed += fileRemoved;
            } else {
                try (PrintWriter pw = new PrintWriter(new FileWriter(f, false))) {
                    for (String l : keep) pw.println(l);
                    removed += fileRemoved;
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Audit retention: failed to rewrite " + f.getName(), ex);
                }
            }
        }
        return removed;
    }

    /** Snapshot of audit-log storage for the Settings page. */
    public static class StorageInfo {
        private final long events;
        private final long bytes;
        private final String oldest; // ISO ts of the earliest retained event, or "" if none
        private final int files;
        StorageInfo(long events, long bytes, String oldest, int files) {
            this.events = events; this.bytes = bytes; this.oldest = oldest; this.files = files;
        }
        public long getEvents()   { return events; }
        public long getBytes()    { return bytes; }
        public String getOldest() { return oldest; }
        public int getFiles()     { return files; }
        /** Human-readable size, e.g. "87 KB" / "1.2 MB". */
        public String getHumanSize() {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    /** Computes current audit-log storage usage (events, bytes, oldest event, file count). */
    public synchronized StorageInfo getStorageInfo() {
        File dir = logsDir();
        if (dir == null) return new StorageInfo(0, 0, "", 0);
        File[] files = dir.listFiles((d, n) -> n.startsWith("omniauth-audit-") && n.endsWith(".json"));
        if (files == null || files.length == 0) return new StorageInfo(0, 0, "", 0);
        List<File> sorted = new ArrayList<>();
        for (File f : files) sorted.add(f);
        sorted.sort((a, b) -> a.getName().compareTo(b.getName())); // oldest first
        long events = 0, bytes = 0;
        for (File f : sorted) {
            bytes += f.length();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) if (!line.isBlank()) events++;
            } catch (Exception ignored) {}
        }
        // Oldest event = first non-blank line of the earliest file.
        String oldest = "";
        for (File f : sorted) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Map<String, String> p = parseLine(line);
                    if (p != null && p.get("ts") != null) { oldest = p.get("ts"); break; }
                }
            } catch (Exception ignored) {}
            if (!oldest.isEmpty()) break;
        }
        return new StorageInfo(events, bytes, oldest, sorted.size());
    }

    private String toJson(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(jsonEscape(entry.getKey())).append("\":");
            sb.append("\"").append(jsonEscape(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private Map<String, String> parseLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            Map<String, String> map = new LinkedHashMap<>();
            String s = line.trim();
            if (s.startsWith("{")) s = s.substring(1);
            if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
            for (String pair : s.split(",(?=\")")) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).trim().replace("\"", "");
                String val = pair.substring(colon + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                map.put(key, unescape(val));
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    /** Single left-to-right pass — handles the escape character first, so e.g. a stored \\ then \" round-trips correctly. */
    private static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                switch (n) {
                    case 'n':  sb.append('\n'); break;
                    case 't':  sb.append('\t'); break;
                    case 'r':  sb.append('\r'); break;
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    default:   sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
