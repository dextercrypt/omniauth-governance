package io.jenkins.plugins.omniauth;
import io.jenkins.plugins.omniauth.sso.OmniAuthSsoProvider;
import io.jenkins.plugins.omniauth.sso.EntraOAuthConfig;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.User;
import hudson.security.GroupDetails;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.SecurityRealm;
import org.jenkinsci.Symbol;
import io.jenkins.plugins.omniauth.util.MsalTokenHelper;
import io.jenkins.plugins.omniauth.util.TokenHelper;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extends Jenkins' own HudsonPrivateSecurityRealm so that all native behaviour
 * (user database, Manage Users, password reset, self-signup, Remember Me, CSRF, …)
 * is 100 % identical to Jenkins' built-in "Jenkins' own user database" realm.
 *
 * The only addition is a "Sign in with Microsoft" button on the login page, backed
 * by an OAuth2 / OIDC code flow against Microsoft Entra (Azure AD).
 *
 * Strategy: override getLoginUrl() to serve our login.jelly, which is a verbatim
 * copy of Jenkins 2.541.2's login page with the Entra button appended.
 * All other SecurityRealm methods (createSecurityComponents, loadUserByUsername2,
 * allowsSignup, signup page, …) are inherited unchanged from the parent class.
 */
public class OmniAuthSecurityRealm extends HudsonPrivateSecurityRealm {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthSecurityRealm.class.getName());


    /**
     * The configured SSO provider (an {@link OmniAuthSsoProvider}), or null when SSO is not set up.
     * Today this is always an {@link EntraOAuthConfig}; modelling it as the extension-point type lets
     * a future provider drop in without touching the realm. {@link #getEntraConfig()} narrows it for
     * the Entra-specific login path that still lives here.
     */
    private final OmniAuthSsoProvider provider;

    /**
     * Authoritative on/off switch for single sign-on, independent of whether {@link #provider}
     * is filled in. This lets an admin keep provider settings stored but dormant: turning SSO off
     * preserves the Entra config so it can be switched back on later without re-entering the client
     * secret, while sign-in stays native-only in the meantime.
     *
     * <p>Nullable on purpose, to tell three states apart across upgrades:
     * <ul>
     *   <li>{@code null}  — config persisted before this field existed. SSO is treated as active iff
     *       {@code entraConfig} is set, which preserves pre-upgrade behaviour: an install that already
     *       had Entra configured stays on SSO after the plugin updates.</li>
     *   <li>{@code true}  — SSO on (it still only becomes <em>active</em> when a provider is fully
     *       configured).</li>
     *   <li>{@code false} — SSO explicitly off; any stored {@code entraConfig} is kept but dormant.</li>
     * </ul>
     */
    private Boolean ssoEnabled;

    /**
     * Cached MSAL4J helper — created once on first use and reused for all subsequent logins.
     *
     * Why transient: OmniAuthSecurityRealm is serialised by XStream for Jenkins config
     * persistence. ConfidentialClientApplication (inside MsalTokenHelper) is not
     * serialisable, so the field must be excluded from serialisation and rebuilt lazily
     * after deserialisation.
     *
     * Why volatile: multiple browser sessions can trigger concurrent logins. volatile
     * ensures the double-checked locking pattern below is safe on all JVMs (Java 5+).
     */
    private transient volatile TokenHelper cachedHelper;

    @DataBoundConstructor
    public OmniAuthSecurityRealm(boolean allowsSignup, OmniAuthSsoProvider provider) {
        // allowsSignup is accepted for binding compatibility but always forced OFF: self-signup is
        // replaced by the governed provisioning portal. This holds for the UI, JCasC, and any
        // previously-persisted config that had it enabled.
        super(false, false, null);          // allowsSignup=false, enableCaptcha=false, captchaSupport=null
        this.provider = provider;
    }

    /** The configured SSO provider (the extension-point type), for the realm config selector. */
    public OmniAuthSsoProvider getProvider() {
        return provider;
    }

    /** The Entra config when that is the configured provider, else null (narrowing convenience). */
    public EntraOAuthConfig getEntraConfig() {
        return (provider instanceof EntraOAuthConfig) ? (EntraOAuthConfig) provider : null;
    }

    @org.kohsuke.stapler.DataBoundSetter
    public void setSsoEnabled(boolean ssoEnabled) {
        this.ssoEnabled = ssoEnabled;
    }

    /**
     * Resolved on/off state of the SSO toggle (see {@link #ssoEnabled}). Drives the config-form
     * checkbox and the JCasC export. A legacy config with no stored flag reports "on" when Entra is
     * configured, so upgrades keep working SSO switched on.
     */
    public boolean isSsoEnabled() {
        return ssoEnabled == null ? (provider != null) : ssoEnabled;
    }

    /**
     * True when SSO sign-in is actually available: the toggle is on <em>and</em> a provider is fully
     * configured. Everything that gates runtime SSO behaviour (the login button, the OAuth
     * endpoints, group sync, pre-provisioning) keys off this, not off {@code entraConfig} alone — so
     * a dormant-but-preserved config never leaks SSO back on.
     */
    public boolean isSsoActive() {
        return isSsoEnabled() && provider != null;
    }

    /**
     * Self-signup is disabled (onboarding goes through the OmniAuth provisioning portal). Anyone
     * who reaches the inherited {@code /securityRealm/signup} URL directly — e.g. an old bookmark —
     * is redirected to the login page instead of being shown a sign-up form that could not succeed.
     * Account creation itself is already blocked by {@code allowsSignup == false}; this just avoids a
     * misleading dead-end page.
     */
    // lgtm false positive: Self-signup is disabled; this only redirects a stale /signup bookmark to the login page. Anonymous by design, no state change -- no crumb or permission check applies.
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public HttpResponse doSignup(StaplerRequest req) {
        return HttpResponses.redirectTo(req.getContextPath() + "/securityRealm/login");
    }

    /**
     * Wrap the inherited {@link AuthenticationManager} so that a native user with the
     * force-password-change flag set cannot authenticate on <em>any</em> channel — web
     * form, HTTP BASIC, API token or CLI — until they have chosen a new password.
     *
     * <p>The delegate verifies the password exactly as Jenkins' own realm does; only
     * <em>after</em> a successful credential check do we reject the login with a
     * {@link PasswordChangeRequiredException}. Spring Security treats that as a failure,
     * so no session or {@code SecurityContext} is created. This is fail-closed by
     * construction: there is no privileged session to contain.
     *
     * <p>The interactive browser flow deliberately bypasses this manager — it posts to
     * {@code /omniauth/nativeLogin}, which validates the temporary password directly and
     * sends the user to the branded set-password page without logging them in.
     */
    @Override
    public SecurityComponents createSecurityComponents() {
        SecurityComponents base = super.createSecurityComponents();
        final org.springframework.security.authentication.AuthenticationManager delegate = base.manager2;
        org.springframework.security.authentication.AuthenticationManager wrapped = authentication -> {
            // Brute-force lockout: reject a locked account BEFORE verifying the password,
            // so a correct password cannot get in during the cooldown. Covers every
            // channel (web form, HTTP BASIC, API token, CLI) since all route through here.
            String name = authentication != null ? authentication.getName() : null;
            if (BruteForceTracker.isLocked(name)) {
                throw new org.springframework.security.authentication.LockedException(
                        "Account temporarily locked due to too many failed login attempts.");
            }
            Authentication result = delegate.authenticate(authentication);
            if (result != null && result.isAuthenticated() && isForcePasswordChange(result.getName())) {
                throw new PasswordChangeRequiredException(result.getName());
            }
            return result;
        };
        return new SecurityComponents(wrapped, base.userDetails2, base.rememberMe2);
    }

    /** True when the named native user still owes a password change. Cheap, null-safe. */
    static boolean isForcePasswordChange(String username) {
        if (username == null || username.isEmpty()) return false;
        User u = User.getById(username, false);
        if (u == null) return false;
        OmniAuthForcePasswordProperty p = u.getProperty(OmniAuthForcePasswordProperty.class);
        return p != null && p.isForcePasswordChange();
    }

    /**
     * The active SSO provider, or {@code null} when no SSO is set up. Drives the sign-in button's
     * label and logo on the login page via the provider's own {@code getId}/{@code getButtonLabel}/
     * {@code getLoadingLabel}, so a new provider needs no change here.
     */
    public OmniAuthSsoProvider getSsoProvider() {
        return isSsoActive() ? provider : null;
    }

    public String getLoginHeading() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        String v = cfg != null ? cfg.getLoginHeading() : "";
        return (v != null && !v.isBlank()) ? v : "Sign in to Jenkins";
    }

    public String getLoginTabTitle() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        String v = cfg != null ? cfg.getLoginTabTitle() : "";
        return (v != null && !v.isBlank()) ? v : "Sign In";
    }

    public String getLoginAnnouncementText() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getLoginAnnouncementText() : "";
    }

    public String getLoginFooterText() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getLoginFooterText() : "";
    }

    public String getLoginBackground() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getLoginBackground() : "omniauth";
    }

    public String getLoginLogoPosition() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getLoginLogoPosition() : "a";
    }

    public String getLoginLogoSize() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getLoginLogoSize() : "40";
    }

    public String getLoginButler() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getLoginButler() : "default";
    }

    /** Concrete butler the branding panel renders (rotation modes resolved here). */
    public String getResolvedLoginButler() {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        return cfg != null ? cfg.getResolvedLoginButler() : "default";
    }

    /**
     * The symbol basename for the resolved butler, so animated-branding.jelly can render every butler
     * with a single {@code <l:icon>} instead of a per-butler branch. A couple of ids have a hyphenated
     * symbol file name.
     */
    public String getResolvedButlerSymbol() {
        switch (getResolvedLoginButler()) {
            case "santaclaus": return "santa-claus";
            case "sandiego":   return "san-diego";
            default:           return getResolvedLoginButler();
        }
    }

    /** The height class for the resolved butler, so the single {@code <l:icon>} sizes each correctly. */
    public String getResolvedButlerHeightClass() {
        switch (getResolvedLoginButler()) {
            case "austin":     return "oau-butler--h30";
            case "nerd":       return "oau-butler--h56";
            case "santaclaus": return "oau-butler--h44";
            case "superhero":
            case "raleigh":
            case "sandiego":
            case "cowboy":     return "oau-butler--h45";
            default:           return "oau-butler--h40"; // general, jenkinsx, ninja
        }
    }

    public String getLoginLogoUrl() {
        java.io.File dir = new java.io.File(jenkins.model.Jenkins.get().getRootDir(), "omniauth-branding");
        java.io.File[] files = dir.listFiles(f -> f.getName().startsWith("login-logo."));
        if (files == null || files.length == 0) return "";
        String root = jenkins.model.Jenkins.get().getRootUrl();
        if (root == null) return "";
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        // Cache-bust by the file's last-modified time so swapping the logo busts the
        // browser cache, while an unchanged logo keeps a stable, cacheable URL.
        return root + "/securityRealm/loginLogo?v=" + files[0].lastModified();
    }

    // lgtm false positive: Serves the public login-page logo image to anonymous users (read-only). Must be reachable unauthenticated; no state change, so no crumb or permission check applies.
    @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
    public void doLoginLogo(org.kohsuke.stapler.StaplerRequest req,
                            org.kohsuke.stapler.StaplerResponse rsp) throws Exception {
        java.io.File dir = new java.io.File(jenkins.model.Jenkins.get().getRootDir(), "omniauth-branding");
        java.io.File[] files = dir.listFiles(f -> f.getName().startsWith("login-logo."));
        if (files == null || files.length == 0) { rsp.setStatus(404); return; }
        java.io.File logo = files[0];
        String name = logo.getName().toLowerCase();
        String ct = "image/png";
        if (name.endsWith(".svg"))              ct = "image/svg+xml";
        else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) ct = "image/jpeg";
        else if (name.endsWith(".gif"))         ct = "image/gif";
        else if (name.endsWith(".webp"))        ct = "image/webp";
        else if (name.endsWith(".ico"))         ct = "image/x-icon";
        rsp.setContentType(ct);
        rsp.setHeader("Cache-Control", "public, max-age=3600");
        byte[] data = java.nio.file.Files.readAllBytes(logo.toPath());
        rsp.setContentLength(data.length);
        rsp.getOutputStream().write(data);
    }

    // -------------------------------------------------------------------------
    // Override only the login URL — everything else is inherited from
    // HudsonPrivateSecurityRealm without change.
    // -------------------------------------------------------------------------

    /**
     * Points Stapler at our login.jelly, which lives in:
     *   resources/io/jenkins/plugins/omniauth/OmniAuthSecurityRealm/login.jelly
     *
     * That file is an exact copy of Jenkins 2.541.2's login page with the
     * "Sign in with Microsoft" button added below the native login form.
     */
    @Override
    public String getLoginUrl() {
        return "securityRealm/login";
    }

    /**
     * Called by Jenkins when validating a username in the authorization matrix UI.
     * We try the native user database first. If not found and Entra is configured,
     * any email-format username is accepted as a valid pre-provisioned Entra user
     * so admins can assign permissions before the user's first login.
     */
    @Override
    public UserDetails loadUserByUsername2(String username) throws org.springframework.security.core.userdetails.UsernameNotFoundException {
        try {
            return super.loadUserByUsername2(username);
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            if (isSsoActive() && username.contains("@")) {
                return new EntraUserDetails(username, username, username, null, new ArrayList<>());
            }
            throw e;
        }
    }

    /**
     * Called by Jenkins when resolving a group name in the authorization matrix UI.
     * When group sync is enabled, we accept any group name as valid so that admins
     * can pre-add Azure AD group names to the Project Matrix before any member logs in —
     * preventing the red strikethrough that would otherwise appear.
     */
    @Override
    public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers)
            throws org.springframework.security.core.userdetails.UsernameNotFoundException {
        if (isSsoActive() && provider.isGroupSyncEnabled()) {
            return new GroupDetails() {
                @Override
                public String getName() { return groupname; }
            };
        }
        throw new org.springframework.security.core.userdetails.UsernameNotFoundException(groupname);
    }

    // -------------------------------------------------------------------------
    // SSO login — forwarded to the configured provider
    // -------------------------------------------------------------------------

    /**
     * Step 1 — begin SSO sign-in. Forwards to the configured provider's {@code startLogin}.
     * Called by OmniAuthRootAction at GET /omniauth/commenceLogin[?from=\u2026].
     */
    public HttpResponse startLogin(StaplerRequest2 req) throws Exception {
        if (!isSsoActive()) {
            return HttpResponses.error(503,
                    "Single sign-on is not enabled on this Jenkins. Ask your administrator to turn it on.");
        }
        return provider.startLogin(this, req);
    }

    /**
     * Step 2 — complete SSO sign-in from the provider callback. Forwards to the configured
     * provider's {@code finishLogin}. Called by OmniAuthRootAction at GET /omniauth/finishLogin.
     */
    public HttpResponse finishLogin(StaplerRequest2 req) throws Exception {
        if (provider == null) {
            return HttpResponses.error(503, "Single sign-on is not enabled on this Jenkins.");
        }
        return provider.finishLogin(this, req);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    public User getOrCreateEntraUser(String oid, String upn, String displayName,
                                      List<EntraGroupDetails> groups) throws IOException {
        // Determine provisioning source and active group OIDs
        OmniAuthAssignmentConfig assignmentConfig = OmniAuthAssignmentConfig.get();
        List<String> matchingGroupOids = new ArrayList<>();
        if (assignmentConfig != null) {
            for (EntraGroupDetails g : groups) {
                if (assignmentConfig.hasGroup(g.getObjectId())) {
                    matchingGroupOids.add(g.getObjectId());
                    // Resolve display name on the group entity if not yet set
                    assignmentConfig.resolveGroupDisplayName(g.getObjectId(), g.getDisplayName());
                }
            }
        }

        // Fast path: look up by UPN directly (UPN is the Jenkins user ID)
        if (upn != null && !upn.isEmpty()) {
            User existing = User.getById(upn, false);
            if (existing != null) {
                OmniAuthUserProperty prop = existing.getProperty(OmniAuthUserProperty.class);
                if (prop != null && oid.equals(prop.getEntraObjectId())) {
                    updateUserProperty(existing, oid, upn, groups, matchingGroupOids);
                    return existing;
                }
            }
        }
        // Slow path: UPN may have changed — scan all users by OID
        for (User u : User.getAll()) {
            OmniAuthUserProperty prop = u.getProperty(OmniAuthUserProperty.class);
            if (prop != null && oid.equals(prop.getEntraObjectId())) {
                updateUserProperty(u, oid, upn, groups, matchingGroupOids);
                return u;
            }
        }
        // New user — provision with UPN as Jenkins user ID
        String userId = (upn != null && !upn.isEmpty()) ? upn : oid;
        User newUser = User.getOrCreateByIdOrFullName(userId);
        newUser.setFullName(displayName != null ? displayName : upn);
        updateUserProperty(newUser, oid, upn, groups, matchingGroupOids);
        String source = matchingGroupOids.isEmpty() ? "individual pre-provisioning" : "group membership";
        LOGGER.log(Level.INFO, "Provisioned Jenkins user for Entra identity: {0} via {1}", new Object[]{upn, source});
        return newUser;
    }

    public boolean isPreProvisioned(String oid, String upn, List<EntraGroupDetails> groups) {
        // Check 1 — individually pre-provisioned (provisioningSource = INDIVIDUAL or legacy account)
        if (upn != null && !upn.isEmpty()) {
            User existing = User.getById(upn, false);
            if (existing != null) {
                OmniAuthUserProperty prop = existing.getProperty(OmniAuthUserProperty.class);
                if (prop != null && !prop.isViaGroup()) {
                    // Guard against UPN reuse: if this account already has a stable OID bound
                    // and the incoming token's OID differs, the UPN now points at a different
                    // directory object — do NOT let them inherit the existing account. When no
                    // OID is stored yet (pre-provisioned by UPN, or legacy), allow the match and
                    // let getOrCreateEntraUser bind the OID on this first login.
                    String storedOid = prop.getEntraObjectId();
                    if (storedOid == null || storedOid.isEmpty() || storedOid.equals(oid)) {
                        return true;
                    }
                    LOGGER.log(Level.WARNING, "Entra UPN {0} matches a provisioned account with a "
                            + "different stable OID — rejecting the UPN match (possible UPN reuse)", upn);
                }
            }
        }
        if (oid != null) {
            for (User u : User.getAll()) {
                OmniAuthUserProperty prop = u.getProperty(OmniAuthUserProperty.class);
                if (prop != null && oid.equals(prop.getEntraObjectId()) && !prop.isViaGroup()) return true;
            }
        }

        // Check 2 — VIA_ENTRA_GROUP: re-validate group membership on every login
        OmniAuthAssignmentConfig assignmentConfig = OmniAuthAssignmentConfig.get();
        if (assignmentConfig != null && assignmentConfig.hasAnyGroups()) {
            for (EntraGroupDetails g : groups) {
                if (assignmentConfig.hasGroup(g.getObjectId())) return true;
            }
        }

        // (A group-provisioned user whose groups no longer match falls through to the
        // return false below — re-validation already happened in Check 2 above.)
        return false;
    }

    /** When a VIA_ENTRA_GROUP user is rejected at login, clear their group OIDs and flag for deletion. */
    public void markOrphanedGroupAccount(String oid, String upn) {
        try {
            User existing = null;
            if (upn != null) existing = User.getById(upn, false);
            if (existing == null && oid != null) {
                for (User u : User.getAll()) {
                    OmniAuthUserProperty p = u.getProperty(OmniAuthUserProperty.class);
                    if (p != null && oid.equals(p.getEntraObjectId())) { existing = u; break; }
                }
            }
            if (existing == null) return;
            OmniAuthUserProperty prop = existing.getProperty(OmniAuthUserProperty.class);
            if (prop == null || !prop.isViaGroup()) return;
            // Preserve last known group name + OID before clearing activeGroupOids
            String savedGroupName = prop.getLastKnownGroupName();
            String savedGroupOid  = prop.getLastKnownGroupOid();
            if (!prop.getActiveGroupOids().isEmpty()) {
                String firstOid = prop.getActiveGroupOids().get(0);
                OmniAuthAssignmentConfig ac = OmniAuthAssignmentConfig.get();
                if (ac != null) {
                    OmniAuthGroupEntity ge = ac.findGroup(firstOid);
                    if (ge != null) { savedGroupName = ge.getEffectiveName(); savedGroupOid = firstOid; }
                }
                if (savedGroupOid == null) savedGroupOid = firstOid;
            }

            OmniAuthUserProperty updated = new OmniAuthUserProperty(prop.getEntraObjectId(), prop.getEntraUpn());
            updated.setGroupsLastSynced(prop.getGroupsLastSynced());
            updated.setLastLoginAt(prop.getLastLoginAt());
            updated.setCachedGroups(prop.getCachedGroups());
            updated.setProvisioningSource("VIA_ENTRA_GROUP");
            updated.setActiveGroupOids(new java.util.ArrayList<>());
            updated.setPendingDeletion(true);
            updated.setLastKnownGroupName(savedGroupName);
            updated.setLastKnownGroupOid(savedGroupOid);
            existing.addProperty(updated);
            existing.save();
            LOGGER.log(Level.INFO, "Marked orphaned group account for deletion: {0}", existing.getId());
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to mark orphaned group account", e);
        }
    }

    private void updateUserProperty(User user, String oid, String upn,
                                    List<EntraGroupDetails> groups,
                                    List<String> matchingGroupOids) throws IOException {
        String now = Instant.now().toString();
        OmniAuthUserProperty prop = new OmniAuthUserProperty(oid, upn);
        prop.setGroupsLastSynced(now);
        prop.setLastLoginAt(now);
        List<String> names = new ArrayList<>();
        for (EntraGroupDetails g : groups) names.add(g.getDisplayName());
        prop.setCachedGroups(names);

        // Set provisioning source
        if (!matchingGroupOids.isEmpty()) {
            // Check if previously individually provisioned — preserve INDIVIDUAL if so
            OmniAuthUserProperty existing = user.getProperty(OmniAuthUserProperty.class);
            if (existing != null && !existing.isViaGroup()) {
                prop.setProvisioningSource("INDIVIDUAL");
                prop.setActiveGroupOids(new ArrayList<>());
            } else {
                prop.setProvisioningSource("VIA_ENTRA_GROUP");
                prop.setActiveGroupOids(matchingGroupOids);
            }
        } else {
            prop.setProvisioningSource("INDIVIDUAL");
            prop.setActiveGroupOids(new ArrayList<>());
        }
        // Successful login always clears any pending-deletion flag
        prop.setPendingDeletion(false);

        user.addProperty(prop);
        user.save();
    }

    /**
     * Returns the shared MsalTokenHelper, creating it on first call (lazy init).
     *
     * ConfidentialClientApplication is designed to be long-lived and thread-safe — MSAL4J
     * caches authority metadata, token cache, and discovery results on it. Creating a new
     * instance per login throws all that away and re-runs the authority discovery call on
     * every login. We create it once and reuse it for the lifetime of this realm instance.
     *
     * entraConfig is final, so the helper never goes stale. If an admin reconfigures the
     * realm, Jenkins creates a new OmniAuthSecurityRealm instance, which gets a fresh helper.
     *
     * Overridden in tests to inject a fake helper without touching MSAL4J.
     */
    public TokenHelper createMsalTokenHelper() throws Exception {
        if (cachedHelper == null) {
            synchronized (this) {
                if (cachedHelper == null) {
                    cachedHelper = new MsalTokenHelper(getEntraConfig());
                }
            }
        }
        return cachedHelper;
    }

    /**
     * Returns true only for relative URLs that stay within this server.
     * Rejects absolute URLs (http/https), protocol-relative URLs (//evil.com),
     * and backslash paths (\evil.com) — all of which browsers treat as external redirects.
     * Only paths starting with a single "/" are accepted as safe post-login destinations.
     */
    public static boolean isSafeRedirectUrl(String url) {
        if (url == null || url.isEmpty() || url.charAt(0) != '/') return false;
        // Reject //host and /\host — browsers normalise the backslash form to a
        // protocol-relative URL, so /\evil.com would navigate off-site.
        if (url.length() > 1 && (url.charAt(1) == '/' || url.charAt(1) == '\\')) return false;
        // Reject any backslash or control char anywhere (they can confuse URL parsing).
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '\\' || c < 0x20) return false;
        }
        return true;
    }

    public String buildRedirectUri(StaplerRequest2 req) {
        String root = Jenkins.get().getRootUrl();
        if (root == null) root = req.getRootPath() + "/";
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "/omniauth/finishLogin";
    }

    // -------------------------------------------------------------------------
    // Descriptor
    // -------------------------------------------------------------------------

    @Extension
    @Symbol("omniAuth")
    public static final class DescriptorImpl extends Descriptor<SecurityRealm> {

        @Override
        public String getDisplayName() {
            return "OmniAuth — Jenkins' own user database + Single Sign-On";
        }

        @Override
        public SecurityRealm newInstance(StaplerRequest req, JSONObject formData)
                throws FormException {
            // Snapshot old config for diff notification
            Jenkins j = Jenkins.get();
            EntraOAuthConfig oldEntra = (j.getSecurityRealm() instanceof OmniAuthSecurityRealm)
                    ? ((OmniAuthSecurityRealm) j.getSecurityRealm()).getEntraConfig()
                    : null;
            boolean oldSsoEnabled = (j.getSecurityRealm() instanceof OmniAuthSecurityRealm)
                    && ((OmniAuthSecurityRealm) j.getSecurityRealm()).isSsoEnabled();
            boolean oldAllowsSignup = (j.getSecurityRealm() instanceof OmniAuthSecurityRealm)
                    && ((OmniAuthSecurityRealm) j.getSecurityRealm()).getAllowsSignup();

            // Self-signup is intentionally disabled: onboarding is governed through the OmniAuth
            // provisioning portal (request + approval + audit), not an open /signup page.
            boolean allowsSignup = false;

            // The SSO toggle is authoritative. The provider settings are always bound and kept (even
            // when the toggle is off) so they are preserved and sign-in just goes native only;
            // flipping the toggle back on later restores them without re-entering the secret.
            boolean ssoEnabled = formData.optBoolean("ssoEnabled", false);

            // Bind the selected SSO provider from the dropdown-descriptor-selector. Databinding uses
            // the provider's own @DataBoundConstructor/@DataBoundSetters, so each provider's full
            // config round-trips without the realm knowing its fields. A half-configured provider
            // (missing required settings) is treated as "no provider".
            OmniAuthSsoProvider provider = null;
            JSONObject providerData = formData.optJSONObject("provider");
            if (providerData != null && !providerData.isEmpty()) {
                provider = req.bindJSON(OmniAuthSsoProvider.class, providerData);
                if (provider != null && !provider.isConfigured()) {
                    provider = null;
                }
            }
            EntraOAuthConfig newEntra = (provider instanceof EntraOAuthConfig) ? (EntraOAuthConfig) provider : null;

            // Send config change notification
            OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
            if (cfg != null) {
                List<String> diff = buildConfigDiff(oldAllowsSignup, allowsSignup, oldEntra, newEntra,
                        oldSsoEnabled, ssoEnabled);
                if (!diff.isEmpty()) {
                    String changedBy = currentUserId();
                    NotificationService.sendConfigChanged(cfg, changedBy, Instant.now().toString(), diff);
                }
            }

            OmniAuthSecurityRealm realm = new OmniAuthSecurityRealm(allowsSignup, provider);
            realm.setSsoEnabled(ssoEnabled);
            return realm;
        }

        private static List<String> buildConfigDiff(boolean oldSignup, boolean newSignup,
                                                     EntraOAuthConfig oldE, EntraOAuthConfig newE,
                                                     boolean oldSsoEnabled, boolean newSsoEnabled) {
            List<String> lines = new ArrayList<>();
            if (oldSignup != newSignup) {
                lines.add("allowsSignup: " + oldSignup + " → " + newSignup);
            }
            if (oldSsoEnabled != newSsoEnabled) {
                lines.add("ssoEnabled: " + oldSsoEnabled + " → " + newSsoEnabled);
            }
            String oldTenant = oldE != null ? oldE.getTenantId() : "(none)";
            String newTenant = newE != null ? newE.getTenantId() : "(none)";
            if (!oldTenant.equals(newTenant)) lines.add("tenantId: " + oldTenant + " → " + newTenant);

            String oldClient = oldE != null ? oldE.getClientId() : "(none)";
            String newClient = newE != null ? newE.getClientId() : "(none)";
            if (!oldClient.equals(newClient)) lines.add("clientId: " + oldClient + " → " + newClient);

            boolean oldGroupSync = oldE != null && oldE.isEnableGroupSync();
            boolean newGroupSync = newE != null && newE.isEnableGroupSync();
            if (oldGroupSync != newGroupSync) {
                lines.add("enableGroupSync: " + oldGroupSync + " → " + newGroupSync);
            }

            // Detect secret change without exposing values
            String oldSecret = (oldE != null && oldE.getClientSecret() != null)
                    ? oldE.getClientSecret().getPlainText() : "";
            String newSecret = (newE != null && newE.getClientSecret() != null)
                    ? newE.getClientSecret().getPlainText() : "";
            if (!oldSecret.equals(newSecret)) {
                lines.add("clientSecret: [changed]");
            }
            return lines;
        }

        private static String currentUserId() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return (auth != null && auth.getName() != null) ? auth.getName() : "unknown";
        }
    }
}
