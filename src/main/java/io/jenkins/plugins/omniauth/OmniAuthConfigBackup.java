package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serialises OmniAuth configuration to / from a JCasC-shaped YAML document for the UI
 * <em>Backup &amp; Restore</em> feature.
 *
 * <p><b>Scope (deliberately narrow and safe):</b> roles, assignments, Access-Management groups, and
 * the non-secret global settings. This explicitly <b>excludes</b>:
 * <ul>
 *   <li>the <b>security realm</b> (Entra sign-in config) — set by hand or via JCasC; leaving it out
 *       means an import can never change how you log in, so it cannot lock you out;</li>
 *   <li>all <b>secrets</b> (any {@link Secret}-typed field) — stripped on export, never read on
 *       import, so existing secrets are preserved and nothing sensitive lands in the file;</li>
 *   <li>operational data (JIT/provisioning queues, logs) — never config.</li>
 * </ul>
 *
 * <p>The document mirrors the JCasC structure (keys under {@code unclassified}) so an exported file
 * is also a valid JCasC fragment, but parsing/applying is self-contained and needs no JCasC plugin.
 * Untrusted uploads are parsed with {@link SafeConstructor} and a size cap.
 */
public final class OmniAuthConfigBackup {

    private OmniAuthConfigBackup() {}

    private static final String UNCLASSIFIED = "unclassified";
    private static final String K_ROLES = "omniAuthRoles";
    private static final String K_ASSIGN = "omniAuthAssignments";
    private static final String K_GLOBAL = "omniAuthGlobalConfig";

    /** Upper bound on an uploaded document, to bound parser memory on untrusted input. */
    private static final int MAX_YAML_BYTES = 2 * 1024 * 1024; // 2 MiB

    /** Immutable result of parsing + validating a backup document, ready to apply. */
    public static final class Parsed {
        final List<OmniAuthRoleConfig.RoleDefinition> roles;
        final List<OmniAuthGroupEntity> groups;
        final List<OmniAuthAssignment> assignments;
        final Map<String, Object> globals;
        private Parsed(List<OmniAuthRoleConfig.RoleDefinition> roles, List<OmniAuthGroupEntity> groups,
                       List<OmniAuthAssignment> assignments, Map<String, Object> globals) {
            this.roles = roles; this.groups = groups; this.assignments = assignments; this.globals = globals;
        }
        public int roleCount()       { return roles.size(); }
        public int groupCount()      { return groups.size(); }
        public int assignmentCount() { return assignments.size(); }
        public int globalCount()     { return globals.size(); }
    }

    // ── Diff (plan) ───────────────────────────────────────────────────────────

    /** A single changed global setting, with its current and incoming values. */
    public static final class SettingChange {
        // lgtm false positive: Not a credential: "key" is the name of a changed global setting in a diff view-model (shown next to from/to values).
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        public final String key, from, to;
        SettingChange(String key, String from, String to) { this.key = key; this.from = from; this.to = to; }
    }

    /** A coloured run of text in a change line. {@code tone} is {@code add} / {@code remove} / {@code neutral}. */
    public static final class Seg {
        public final String text, tone;
        Seg(String text, String tone) { this.text = text; this.tone = tone; }
    }

    /** One differing field of a changed item: a {@code label} and the value as coloured segments. */
    public static final class ChangeLine {
        public final String label;
        public final List<Seg> segs;
        ChangeLine(String label, List<Seg> segs) { this.label = label; this.segs = segs; }
    }

    /**
     * A changed role / group / assignment: its display name plus one {@link ChangeLine} per
     * differing field (e.g. {@code permissions: +…Build, -…Delete} with +added green, -removed red).
     */
    public static final class ItemChange {
        public final String name;
        public final List<ChangeLine> lines;
        ItemChange(String name, List<ChangeLine> lines) { this.name = name; this.lines = lines; }
    }

    /**
     * The plan: what applying a {@link Parsed} backup would add, remove or change relative to the
     * live configuration. Added/removed entries are display labels; changed entries carry
     * field-level detail. All values are human-readable (not raw ids) for display in the UI.
     */
    public static final class Diff {
        public final List<String> rolesAdded = new ArrayList<>(), rolesRemoved = new ArrayList<>();
        public final List<ItemChange> rolesChanged = new ArrayList<>();
        public final List<String> groupsAdded = new ArrayList<>(), groupsRemoved = new ArrayList<>();
        public final List<ItemChange> groupsChanged = new ArrayList<>();
        public final List<String> assignmentsAdded = new ArrayList<>(), assignmentsRemoved = new ArrayList<>();
        public final List<ItemChange> assignmentsChanged = new ArrayList<>();
        public final List<SettingChange> settingsChanged = new ArrayList<>();

        public int totalChanges() {
            return rolesAdded.size() + rolesRemoved.size() + rolesChanged.size()
                 + groupsAdded.size() + groupsRemoved.size() + groupsChanged.size()
                 + assignmentsAdded.size() + assignmentsRemoved.size() + assignmentsChanged.size()
                 + settingsChanged.size();
        }
        public boolean isEmpty() { return totalChanges() == 0; }

        /** A compact one-line summary, for the import-history log. */
        public String summary() {
            if (isEmpty()) return "no changes";
            List<String> parts = new ArrayList<>();
            addPart(parts, "roles",       rolesAdded.size(),       rolesRemoved.size(),       rolesChanged.size());
            addPart(parts, "groups",      groupsAdded.size(),      groupsRemoved.size(),      groupsChanged.size());
            addPart(parts, "assignments", assignmentsAdded.size(), assignmentsRemoved.size(), assignmentsChanged.size());
            if (!settingsChanged.isEmpty()) parts.add(settingsChanged.size() + " setting(s) changed");
            return String.join("; ", parts);
        }
        private static void addPart(List<String> parts, String label, int added, int removed, int changed) {
            if (added == 0 && removed == 0 && changed == 0) return;
            List<String> bits = new ArrayList<>();
            if (added > 0)   bits.add("+" + added);
            if (removed > 0) bits.add("-" + removed);
            if (changed > 0) bits.add("~" + changed);
            parts.add(label + " " + String.join("/", bits));
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /** Serialises the current OmniAuth config (no realm, no secrets) to a YAML document. */
    public static String export() {
        Map<String, Object> unc = new LinkedHashMap<>();
        Map<String, Object> rolesWrap = new LinkedHashMap<>();
        rolesWrap.put("roles", exportRoles());
        unc.put(K_ROLES, rolesWrap);

        Map<String, Object> amWrap = new LinkedHashMap<>();
        amWrap.put("groups", exportGroups());
        amWrap.put("assignments", exportAssignments());
        unc.put(K_ASSIGN, amWrap);

        unc.put(K_GLOBAL, exportGlobals());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put(UNCLASSIFIED, unc);

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return header() + new Yaml(opts).dump(root);
    }

    private static String header() {
        // ASCII-only header: a non-ASCII char (e.g. an em-dash) can become a C1 control character
        // under a charset mismatch on upload, which SnakeYAML then rejects as "special characters".
        return "# OmniAuth configuration backup - " + Instant.now() + "\n"
             + "# Restore via: Manage Jenkins > OmniAuth Management > Backup & Restore > Import.\n"
             + "# Scope: roles, assignments, Access-Management groups, and global settings.\n"
             + "# NOT included: the sign-in / security realm (set by hand), and all secrets.\n"
             + "# After importing onto a DIFFERENT controller, re-enter on the relevant pages:\n"
             + "#   Entra client secret, SMTP password, Slack/Teams webhook URLs.\n"
             + "#\n";
    }

    private static List<Object> exportRoles() {
        List<Object> out = new ArrayList<>();
        for (OmniAuthRoleConfig.RoleDefinition r : OmniAuthRoleConfig.get().getRoles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("permissionIds", new ArrayList<>(r.getPermissionIds()));
            out.add(m);
        }
        return out;
    }

    private static List<Object> exportGroups() {
        List<Object> out = new ArrayList<>();
        for (OmniAuthGroupEntity g : OmniAuthAssignmentConfig.get().getGroups()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupOid", g.getGroupOid());
            m.put("label", g.getLabel());
            if (g.getDisplayName() != null) m.put("displayName", g.getDisplayName());
            m.put("addedAt", g.getAddedAt());
            m.put("addedBy", g.getAddedBy());
            out.add(m);
        }
        return out;
    }

    private static List<Object> exportAssignments() {
        List<Object> out = new ArrayList<>();
        for (OmniAuthAssignment a : OmniAuthAssignmentConfig.get().getAssignments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", a.getUserId());
            m.put("authType", a.getAuthType());
            m.put("roleId", a.getRoleId());
            m.put("scope", a.getScope());
            m.put("scopeType", a.getScopeType());
            m.put("customPermissions", new ArrayList<>(a.getCustomPermissions()));
            m.put("grantedAt", a.getGrantedAt());
            m.put("grantedBy", a.getGrantedBy());
            if (a.getExpiresAt() != null)  m.put("expiresAt", a.getExpiresAt());
            if (a.getReviewedAt() != null) m.put("reviewedAt", a.getReviewedAt());
            m.put("accessType", a.getAccessType());
            m.put("approvers", new ArrayList<>(a.getApprovers()));
            m.put("maxDurationHours", a.getMaxDurationHours());
            m.put("approvalTimeoutHours", a.getApprovalTimeoutHours());
            out.add(m);
        }
        return out;
    }

    /**
     * Reflection over the global config's own declared bean properties: every property that has a
     * matching getter+setter of a supported type is exported. {@link Secret}-typed properties are
     * skipped, so secrets can never be written to the file.
     */
    private static Map<String, Object> exportGlobals() {
        OmniAuthGlobalConfig c = OmniAuthGlobalConfig.get();
        Map<String, Object> m = new TreeMap<>();
        for (Method setter : OmniAuthGlobalConfig.class.getDeclaredMethods()) {
            String prop = scalarProp(setter);
            if (prop == null) continue;
            Class<?> pt = setter.getParameterTypes()[0];
            Method getter = findGetter(prop, pt);
            if (getter == null) continue;
            try {
                Object v = getter.invoke(c);
                if (pt == boolean.class)      m.put(prop, Boolean.TRUE.equals(v));
                else if (pt == int.class)     m.put(prop, v == null ? 0 : ((Number) v).intValue());
                else if (pt == String.class)  m.put(prop, v == null ? "" : v.toString());
                else if (List.class.isAssignableFrom(pt)) m.put(prop, v == null ? new ArrayList<>() : new ArrayList<>((List<?>) v));
            } catch (ReflectiveOperationException ignored) {
                // skip anything that won't read cleanly
            }
        }
        return m;
    }

    // ── Parse + validate ────────────────────────────────────────────────────────

    /** Parses and validates a backup document. Throws {@link IllegalArgumentException} if malformed. */
    @SuppressWarnings("unchecked")
    public static Parsed parse(String yaml) {
        if (yaml == null || yaml.isBlank()) throw new IllegalArgumentException("The file is empty.");
        if (yaml.length() > MAX_YAML_BYTES) throw new IllegalArgumentException("The file is too large.");
        if (!yaml.isEmpty() && yaml.charAt(0) == '﻿') yaml = yaml.substring(1); // strip UTF-8 BOM
        // Strip characters the YAML reader forbids (C0 except tab/LF/CR, DEL, and the C1 range).
        // A form upload decoded with the wrong charset can turn a multi-byte char into a C1 control
        // char; dropping these keeps the (ASCII) structure parseable instead of failing the import.
        yaml = yaml.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x84\\x86-\\x9f]", "");

        LoaderOptions lo = new LoaderOptions();
        lo.setCodePointLimit(MAX_YAML_BYTES);
        Object docObj;
        try {
            docObj = new Yaml(new SafeConstructor(lo)).load(yaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not valid YAML: " + e.getMessage());
        }
        if (!(docObj instanceof Map)) throw new IllegalArgumentException("Unexpected file structure.");
        Map<String, Object> doc = (Map<String, Object>) docObj;

        // Accept either an unclassified-wrapped (JCasC) document or the bare sections.
        Map<String, Object> sections = asMap(doc.get(UNCLASSIFIED));
        if (sections == null) sections = doc;

        List<OmniAuthRoleConfig.RoleDefinition> roles = parseRoles(asMap(sections.get(K_ROLES)));
        Map<String, Object> am = asMap(sections.get(K_ASSIGN));
        List<OmniAuthGroupEntity> groups = parseGroups(am);
        List<OmniAuthAssignment> assignments = parseAssignments(am);
        Map<String, Object> globals = parseGlobals(asMap(sections.get(K_GLOBAL)));

        if (roles.isEmpty() && groups.isEmpty() && assignments.isEmpty() && globals.isEmpty()) {
            throw new IllegalArgumentException("No OmniAuth configuration found in the file.");
        }
        return new Parsed(roles, groups, assignments, globals);
    }

    private static List<OmniAuthRoleConfig.RoleDefinition> parseRoles(Map<String, Object> wrap) {
        List<OmniAuthRoleConfig.RoleDefinition> out = new ArrayList<>();
        if (wrap == null) return out;
        for (Object o : asList(wrap.get("roles"))) {
            Map<String, Object> m = asMap(o);
            if (m == null) continue;
            String name = str(m.get("name"));
            if (name.isEmpty()) throw new IllegalArgumentException("A role is missing its name.");
            out.add(new OmniAuthRoleConfig.RoleDefinition(name, str(m.get("description")), strList(m.get("permissionIds"))));
        }
        return out;
    }

    private static List<OmniAuthGroupEntity> parseGroups(Map<String, Object> am) {
        List<OmniAuthGroupEntity> out = new ArrayList<>();
        if (am == null) return out;
        for (Object o : asList(am.get("groups"))) {
            Map<String, Object> m = asMap(o);
            if (m == null) continue;
            String oid = str(m.get("groupOid"));
            if (oid.isEmpty()) throw new IllegalArgumentException("A group is missing its groupOid.");
            OmniAuthGroupEntity g = new OmniAuthGroupEntity(oid, str(m.get("label")), str(m.get("addedAt")), str(m.get("addedBy")));
            Object dn = m.get("displayName");
            if (dn != null) g.setDisplayName(dn.toString());
            out.add(g);
        }
        return out;
    }

    private static List<OmniAuthAssignment> parseAssignments(Map<String, Object> am) {
        List<OmniAuthAssignment> out = new ArrayList<>();
        if (am == null) return out;
        for (Object o : asList(am.get("assignments"))) {
            Map<String, Object> m = asMap(o);
            if (m == null) continue;
            String userId = str(m.get("userId"));
            if (userId.isEmpty()) throw new IllegalArgumentException("An assignment is missing its userId.");
            OmniAuthAssignment a = new OmniAuthAssignment(
                    userId, str(m.get("authType")), str(m.get("roleId")), str(m.get("scope")),
                    str(m.get("scopeType")), strList(m.get("customPermissions")),
                    str(m.get("grantedAt")), str(m.get("grantedBy")));
            if (m.get("expiresAt") != null)  a.setExpiresAt(m.get("expiresAt").toString());
            if (m.get("reviewedAt") != null) a.setReviewedAt(m.get("reviewedAt").toString());
            if (m.get("accessType") != null) a.setAccessType(m.get("accessType").toString());
            a.setApprovers(strList(m.get("approvers")));
            if (m.get("maxDurationHours") != null)     a.setMaxDurationHours(toInt(m.get("maxDurationHours")));
            if (m.get("approvalTimeoutHours") != null) a.setApprovalTimeoutHours(toInt(m.get("approvalTimeoutHours")));
            out.add(a);
        }
        return out;
    }

    /** Keeps only keys that map to a supported, non-Secret setter on the global config. */
    private static Map<String, Object> parseGlobals(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Method setter = findScalarSetter(e.getKey());
            if (setter != null) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Applies a parsed backup to the live config and persists it. Replace semantics for the lists;
     * only the global keys present are set; secrets are never touched. Invalidates the ACL cache.
     */
    public static void applyParsed(Parsed p) {
        OmniAuthRoleConfig roleCfg = OmniAuthRoleConfig.get();
        roleCfg.setRoles(p.roles);
        roleCfg.save();

        OmniAuthAssignmentConfig amCfg = OmniAuthAssignmentConfig.get();
        amCfg.setGroups(p.groups);
        amCfg.setAssignments(p.assignments);
        amCfg.save();

        OmniAuthGlobalConfig gc = OmniAuthGlobalConfig.get();
        for (Map.Entry<String, Object> e : p.globals.entrySet()) {
            Method setter = findScalarSetter(e.getKey());
            if (setter == null) continue;
            Class<?> pt = setter.getParameterTypes()[0];
            try {
                if (pt == boolean.class)      setter.invoke(gc, toBool(e.getValue()));
                else if (pt == int.class)     setter.invoke(gc, toInt(e.getValue()));
                else if (pt == String.class)  setter.invoke(gc, e.getValue() == null ? "" : e.getValue().toString());
                else if (List.class.isAssignableFrom(pt)) setter.invoke(gc, strList(e.getValue()));
            } catch (ReflectiveOperationException ignored) {
                // a single bad field never aborts the whole apply
            }
        }
        gc.save();
        OmniAuthAuthorizationStrategy.invalidateCache();
    }

    // ── Current state + diff ──────────────────────────────────────────────────

    /** The live configuration captured as a {@link Parsed}, so it can be diffed against an import. */
    public static Parsed current() {
        return parse(export());
    }

    /**
     * Computes what applying {@code incoming} would change relative to the live configuration —
     * the "plan" shown before an import. Roles/groups are keyed by their identity; assignments by
     * (user, scope); settings compare only keys the import carries (an import sets only those).
     * For a changed item, each differing field becomes a readable {@code current → incoming} line.
     */
    public static Diff diff(Parsed incoming) {
        Parsed cur = current();
        Diff d = new Diff();

        Map<String, OmniAuthRoleConfig.RoleDefinition> curRoles = index(cur.roles, OmniAuthRoleConfig.RoleDefinition::getName);
        Map<String, OmniAuthRoleConfig.RoleDefinition> incRoles = index(incoming.roles, OmniAuthRoleConfig.RoleDefinition::getName);
        for (Map.Entry<String, OmniAuthRoleConfig.RoleDefinition> e : incRoles.entrySet()) {
            OmniAuthRoleConfig.RoleDefinition c = curRoles.get(e.getKey());
            if (c == null) d.rolesAdded.add(e.getKey());
            else { List<ChangeLine> l = roleChangeLines(c, e.getValue()); if (!l.isEmpty()) d.rolesChanged.add(new ItemChange(e.getKey(), l)); }
        }
        for (String k : curRoles.keySet()) if (!incRoles.containsKey(k)) d.rolesRemoved.add(k);

        Map<String, OmniAuthGroupEntity> curGroups = index(cur.groups, OmniAuthGroupEntity::getGroupOid);
        Map<String, OmniAuthGroupEntity> incGroups = index(incoming.groups, OmniAuthGroupEntity::getGroupOid);
        for (Map.Entry<String, OmniAuthGroupEntity> e : incGroups.entrySet()) {
            OmniAuthGroupEntity c = curGroups.get(e.getKey());
            if (c == null) d.groupsAdded.add(groupLabel(e.getValue()));
            else { List<ChangeLine> l = groupChangeLines(c, e.getValue()); if (!l.isEmpty()) d.groupsChanged.add(new ItemChange(groupLabel(e.getValue()), l)); }
        }
        for (Map.Entry<String, OmniAuthGroupEntity> e : curGroups.entrySet())
            if (!incGroups.containsKey(e.getKey())) d.groupsRemoved.add(groupLabel(e.getValue()));

        Map<String, OmniAuthAssignment> curAssign = index(cur.assignments, OmniAuthConfigBackup::assignKey);
        Map<String, OmniAuthAssignment> incAssign = index(incoming.assignments, OmniAuthConfigBackup::assignKey);
        for (Map.Entry<String, OmniAuthAssignment> e : incAssign.entrySet()) {
            OmniAuthAssignment c = curAssign.get(e.getKey());
            if (c == null) d.assignmentsAdded.add(assignLabel(e.getValue()));
            else { List<ChangeLine> l = assignChangeLines(c, e.getValue()); if (!l.isEmpty()) d.assignmentsChanged.add(new ItemChange(assignLabel(e.getValue()), l)); }
        }
        for (Map.Entry<String, OmniAuthAssignment> e : curAssign.entrySet())
            if (!incAssign.containsKey(e.getKey())) d.assignmentsRemoved.add(assignLabel(e.getValue()));

        for (Map.Entry<String, Object> e : incoming.globals.entrySet()) {
            String to = String.valueOf(e.getValue());
            String from = cur.globals.containsKey(e.getKey()) ? String.valueOf(cur.globals.get(e.getKey())) : "";
            if (!to.equals(from)) d.settingsChanged.add(new SettingChange(e.getKey(), from, to));
        }
        return d;
    }

    private static <T> Map<String, T> index(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T t : items) m.put(key.apply(t), t);
        return m;
    }

    private static List<ChangeLine> roleChangeLines(OmniAuthRoleConfig.RoleDefinition c, OmniAuthRoleConfig.RoleDefinition i) {
        List<ChangeLine> l = new ArrayList<>();
        addScalar(l, "description", c.getDescription(), i.getDescription());
        addDelta(l, "permissions", c.getPermissionIds(), i.getPermissionIds());
        return l;
    }

    private static List<ChangeLine> groupChangeLines(OmniAuthGroupEntity c, OmniAuthGroupEntity i) {
        List<ChangeLine> l = new ArrayList<>();
        addScalar(l, "label", c.getLabel(), i.getLabel());
        addScalar(l, "display name", c.getDisplayName(), i.getDisplayName());
        return l;
    }

    private static List<ChangeLine> assignChangeLines(OmniAuthAssignment c, OmniAuthAssignment i) {
        List<ChangeLine> l = new ArrayList<>();
        addScalar(l, "role", c.getRoleId(), i.getRoleId());
        addDelta(l, "custom permissions", c.getCustomPermissions(), i.getCustomPermissions());
        addScalar(l, "access type", c.getAccessType(), i.getAccessType());
        addDelta(l, "approvers", c.getApprovers(), i.getApprovers());
        if (!eqNum(c.getMaxDurationHours(), i.getMaxDurationHours()))
            addScalar(l, "max duration (h)", String.valueOf(c.getMaxDurationHours()), String.valueOf(i.getMaxDurationHours()));
        if (!eqNum(c.getApprovalTimeoutHours(), i.getApprovalTimeoutHours()))
            addScalar(l, "approval timeout (h)", String.valueOf(c.getApprovalTimeoutHours()), String.valueOf(i.getApprovalTimeoutHours()));
        addScalar(l, "expires", c.getExpiresAt(), i.getExpiresAt());
        addScalar(l, "reviewed", c.getReviewedAt(), i.getReviewedAt());
        return l;
    }

    /** A {@code "from" -> "to"} line (from = current, red; to = incoming, green), if they differ. */
    private static void addScalar(List<ChangeLine> out, String label, String from, String to) {
        if (str(from).equals(str(to))) return;
        List<Seg> s = new ArrayList<>();
        s.add(new Seg(q(from), "remove"));
        s.add(new Seg(" → ", "neutral"));
        s.add(new Seg(q(to), "add"));
        out.add(new ChangeLine(label, s));
    }

    /** A membership-delta line: {@code +x} (green, added by import) / {@code -y} (red, removed), if any. */
    private static void addDelta(List<ChangeLine> out, String label, List<String> cur, List<String> inc) {
        java.util.Set<String> c = new java.util.LinkedHashSet<>(cur == null ? List.of() : cur);
        java.util.Set<String> i = new java.util.LinkedHashSet<>(inc == null ? List.of() : inc);
        List<Seg> s = new ArrayList<>();
        for (String x : i) if (!c.contains(x)) { if (!s.isEmpty()) s.add(new Seg(", ", "neutral")); s.add(new Seg("+" + x, "add")); }
        for (String x : c) if (!i.contains(x)) { if (!s.isEmpty()) s.add(new Seg(", ", "neutral")); s.add(new Seg("-" + x, "remove")); }
        if (!s.isEmpty()) out.add(new ChangeLine(label, s));
    }

    private static String groupLabel(OmniAuthGroupEntity g) {
        String label = str(g.getLabel());
        return label.isEmpty() ? g.getGroupOid() : label;
    }
    private static String assignLabel(OmniAuthAssignment a) {
        String where = "GLOBAL".equalsIgnoreCase(str(a.getScopeType())) ? "global" : str(a.getScope());
        return a.getUserId() + (where.isEmpty() ? "" : " @ " + where);
    }
    private static String assignKey(OmniAuthAssignment a) {
        return str(a.getUserId()) + "|" + str(a.getAuthType()) + "|" + str(a.getScopeType()) + "|" + str(a.getScope());
    }

    /** Quotes a value for display, showing {@code (empty)} for null/blank. */
    private static String q(String s) { return (s == null || s.isEmpty()) ? "(empty)" : "\"" + s + "\""; }

    private static boolean eqNum(int a, int b) { return a == b; }

    // ── Reflection helpers ──────────────────────────────────────────────────────

    /** Property name for a {@code setX(single supported-type arg)} method, else null. */
    private static String scalarProp(Method setter) {
        if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) return null;
        if (setter.getName().length() <= 3) return null;
        Class<?> pt = setter.getParameterTypes()[0];
        if (Secret.class.equals(pt)) return null; // never serialise secrets
        if (!(pt == String.class || pt == int.class || pt == boolean.class || List.class.isAssignableFrom(pt))) return null;
        return decap(setter.getName().substring(3));
    }

    private static Method findScalarSetter(String prop) {
        String name = "set" + cap(prop);
        for (Method mth : OmniAuthGlobalConfig.class.getDeclaredMethods()) {
            if (mth.getName().equals(name) && scalarProp(mth) != null) return mth;
        }
        return null;
    }

    private static Method findGetter(String prop, Class<?> type) {
        String getName = (type == boolean.class ? "is" : "get") + cap(prop);
        try {
            Method g = OmniAuthGlobalConfig.class.getDeclaredMethod(getName);
            return g.getParameterCount() == 0 ? g : null;
        } catch (NoSuchMethodException e) {
            // Fall back to the other prefix (e.g. a boolean exposed via getX).
            try {
                Method g = OmniAuthGlobalConfig.class.getDeclaredMethod((type == boolean.class ? "get" : "is") + cap(prop));
                return g.getParameterCount() == 0 ? g : null;
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    private static String cap(String s)   { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static String decap(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    // ── Coercion helpers ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    private static List<Object> asList(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) if (x != null) out.add(x.toString());
        return out;
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(str(o).trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(str(o).trim());
    }
}
