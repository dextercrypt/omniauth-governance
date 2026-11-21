package io.jenkins.plugins.omniauth.sso;

import io.jenkins.plugins.omniauth.OmniAuthAssignmentConfig;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IClientSecret;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.Collections;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.nimbusds.jwt.JWTClaimsSet;
import hudson.model.User;
import io.jenkins.plugins.omniauth.EntraAuthenticationToken;
import io.jenkins.plugins.omniauth.EntraGroupDetails;
import io.jenkins.plugins.omniauth.EntraUserDetails;
import io.jenkins.plugins.omniauth.NotificationService;
import io.jenkins.plugins.omniauth.OmniAuthAuditLog;
import io.jenkins.plugins.omniauth.OmniAuthGlobalConfig;
import io.jenkins.plugins.omniauth.OmniAuthSecurityRealm;
import io.jenkins.plugins.omniauth.ProvisioningPortalAction;
import io.jenkins.plugins.omniauth.ProvisioningRequestConfig;
import io.jenkins.plugins.omniauth.util.GraphApiHelper;
import io.jenkins.plugins.omniauth.util.MsalTokenHelper;
import io.jenkins.plugins.omniauth.util.TokenHelper;
import jenkins.security.SecurityListener;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Holds Azure AD / Microsoft Entra configuration:
 * tenant ID, client ID, client secret, scopes, and optional group sync flag.
 */
public class EntraOAuthConfig extends OmniAuthSsoProvider {

    private final String tenantId;
    private final String clientId;
    private final Secret clientSecret;
    private boolean enableGroupSync;
    private String allowedDomains = "";   // comma/space separated; empty = allow all

    // OAuth flow state kept on the HTTP session between commenceLogin and finishLogin.
    public static final String SESSION_OAUTH_STATE    = "omniauth_oauth_state";
    public static final String SESSION_FROM_URL       = "omniauth_from_url";
    public static final String SESSION_CODE_VERIFIER  = "omniauth_code_verifier";
    public static final String SESSION_NONCE          = "omniauth_nonce";
    /** Set when the OAuth flow was started as a provisioning-portal access request, not a login. */
    public static final String SESSION_PROVISION_MODE = "omniauth_provision_mode";

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(EntraOAuthConfig.class.getName());

    @DataBoundConstructor
    public EntraOAuthConfig(String tenantId, String clientId, Secret clientSecret) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** Entra can sign a user in once tenant, client and secret are all present. */
    @Override
    public boolean isConfigured() {
        return tenantId != null && !tenantId.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !Secret.toString(clientSecret).isBlank();
    }

    @Override
    public String getId() {
        return "microsoft";
    }

    @Override
    public String getButtonLabel() {
        return "Sign in with Microsoft";
    }

    @Override
    public String getLoadingLabel() {
        return "Redirecting to Microsoft…";
    }

    /** Group sync is driven by the {@code enableGroupSync} toggle on this provider. */
    @Override
    public boolean isGroupSyncEnabled() {
        return enableGroupSync;
    }

    /**
     * Step 1 — redirect browser to Azure AD login page.
     * Called by OmniAuthRootAction at GET /omniauth/commenceLogin[?from=…]
     */
    @Override
    public HttpResponse startLogin(OmniAuthSecurityRealm realm, StaplerRequest2 req)
            throws Exception {
        String state = UUID.randomUUID().toString();
        req.getSession().setAttribute(SESSION_OAUTH_STATE, state);

        String from = req.getParameter("from");
        if (from != null && !from.isEmpty() && OmniAuthSecurityRealm.isSafeRedirectUrl(from)) {
            req.getSession().setAttribute(SESSION_FROM_URL, from);
        }

        // Provisioning portal: this OAuth flow is an access REQUEST, not a login. The flag is
        // read back in finishEntraLogin to create a PENDING request instead of a session.
        if ("1".equals(req.getParameter("provision"))) {
            req.getSession().setAttribute(SESSION_PROVISION_MODE, Boolean.TRUE);
        } else {
            req.getSession().removeAttribute(SESSION_PROVISION_MODE);
        }

        // PKCE — generate a cryptographically random code verifier and derive its challenge
        // The verifier stays in the session; only the SHA-256 hash (challenge) goes to Azure.
        // On token exchange, Azure re-hashes the verifier and compares — proving the same
        // server that started the login is completing it.
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        byte[] challengeBytes = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
        req.getSession().setAttribute(SESSION_CODE_VERIFIER, codeVerifier);

        // Nonce — embedded in the ID token by Azure AD and validated on return.
        // Prevents token replay attacks where an attacker tries to reuse a previously
        // captured ID token to establish a new session.
        String nonce = UUID.randomUUID().toString();
        req.getSession().setAttribute(SESSION_NONCE, nonce);

        TokenHelper helper = realm.createMsalTokenHelper();
        String authUrl = helper.buildAuthorizationUrl(realm.buildRedirectUri(req), state, codeChallenge, nonce);

        LOGGER.log(Level.FINE, "Redirecting to Azure AD for Entra login");
        return HttpResponses.redirectTo(authUrl);
    }

    /**
     * Step 2 — Azure AD posts back with an authorization code.
     * Called by OmniAuthRootAction at GET /omniauth/finishLogin?code=…&state=…
     */
    @Override
    public HttpResponse finishLogin(OmniAuthSecurityRealm realm, StaplerRequest2 req)
            throws Exception {
        // CSRF: validate state nonce
        String returnedState = req.getParameter("state");
        String sessionState  = (String) req.getSession().getAttribute(SESSION_OAUTH_STATE);
        if (returnedState == null || !returnedState.equals(sessionState)) {
            LOGGER.log(Level.WARNING, "OAuth2 state mismatch — session likely expired, redirecting to login");
            return HttpResponses.redirectTo(Jenkins.get().getRootUrl() + "securityRealm/login?error=session");
        }
        req.getSession().removeAttribute(SESSION_OAUTH_STATE);

        // Azure AD error (e.g. user cancelled)
        String error = req.getParameter("error");
        if (error != null) {
            LOGGER.log(Level.WARNING, "Azure AD error: {0} — {1}",
                    new Object[]{error, req.getParameter("error_description")});
            return HttpResponses.redirectTo(Jenkins.get().getRootUrl() + "loginError?error=entra");
        }

        String authCode = req.getParameter("code");
        if (authCode == null || authCode.isEmpty()) {
            return HttpResponses.error(400, "No authorization code received from Azure AD.");
        }

        // PKCE — retrieve the verifier stored during commenceLogin and send it to Azure.
        // Azure hashes it and checks it matches the challenge sent earlier. An attacker
        // who intercepted the authorization code cannot exchange it without this verifier.
        String codeVerifier = (String) req.getSession().getAttribute(SESSION_CODE_VERIFIER);
        req.getSession().removeAttribute(SESSION_CODE_VERIFIER);
        if (codeVerifier == null) {
            LOGGER.log(Level.WARNING, "PKCE code verifier missing from session — possible session expiry");
            return HttpResponses.redirectTo(Jenkins.get().getRootUrl() + "securityRealm/login?error=session");
        }

        // Exchange code → tokens
        TokenHelper helper = realm.createMsalTokenHelper();
        IAuthenticationResult result = helper.exchangeCodeForTokens(authCode, realm.buildRedirectUri(req), codeVerifier);

        JWTClaimsSet claims = helper.parseIdToken(result.idToken());

        // Nonce validation — confirm the ID token was issued for this exact login attempt.
        // Azure AD embeds the nonce we sent in the token; we verify it matches what we stored.
        String sessionNonce = (String) req.getSession().getAttribute(SESSION_NONCE);
        req.getSession().removeAttribute(SESSION_NONCE);
        String tokenNonce = MsalTokenHelper.getStringClaim(claims, "nonce");
        if (sessionNonce == null || !sessionNonce.equals(tokenNonce)) {
            LOGGER.log(Level.SEVERE, "ID token nonce mismatch — possible token replay attack, rejecting login");
            return HttpResponses.error(400, "Authentication failed: nonce validation error.");
        }

        String oid   = MsalTokenHelper.getStringClaim(claims, "oid");
        String upn   = MsalTokenHelper.getStringClaim(claims, "preferred_username");
        String name  = MsalTokenHelper.getStringClaim(claims, "name");
        String email = MsalTokenHelper.getStringClaim(claims, "email");

        if (oid == null) {
            LOGGER.log(Level.SEVERE, "ID token missing 'oid' claim — cannot identify user");
            return HttpResponses.error(500, "Microsoft did not return a valid user identifier.");
        }

        // Fetch groups if group sync is enabled OR if GROUP entities exist in Access Management.
        // GROUP entities require group data at gate time regardless of the enableGroupSync flag.
        List<EntraGroupDetails> groups = new ArrayList<>();
        OmniAuthAssignmentConfig assignmentConfig = OmniAuthAssignmentConfig.get();
        boolean needGroups = (isGroupSyncEnabled() || (assignmentConfig != null && assignmentConfig.hasAnyGroups()));
        if (needGroups && result.accessToken() != null) {
            try {
                groups = new GraphApiHelper().getGroupMemberships(result.accessToken());
            } catch (IOException | InterruptedException e) {
                LOGGER.log(Level.WARNING, "Graph API group sync failed — continuing without groups", e);
                NotificationService.sendGraphApiFailed(OmniAuthGlobalConfig.get(),
                        upn != null ? upn : oid, e.getMessage());
            }
        }

        // Provisioning portal: if this flow was started as an access REQUEST (provision=1),
        // create a PENDING request from the Microsoft-verified identity instead of logging in.
        boolean provisionMode = Boolean.TRUE.equals(req.getSession().getAttribute(SESSION_PROVISION_MODE));
        req.getSession().removeAttribute(SESSION_PROVISION_MODE);
        if (provisionMode) {
            return handleEntraProvisionRequest(realm, oid, upn, name, email, groups);
        }

        // Gate: only individually pre-provisioned users or members of allowed groups can log in.
        if (!realm.isPreProvisioned(oid, upn, groups)) {
            String displayId = upn != null ? upn : oid;
            LOGGER.log(Level.WARNING, "Rejected SSO login — not pre-provisioned: {0}", displayId);
            OmniAuthAuditLog audit = OmniAuthAuditLog.get();
            if (audit != null) audit.logLoginFailure(displayId, req.getRemoteAddr());
            // If the rejected user was previously VIA_ENTRA_GROUP, clear their group OIDs
            // and mark pending deletion so the orphaned account can be cleaned up.
            realm.markOrphanedGroupAccount(oid, upn);
            return HttpResponses.redirectTo(Jenkins.get().getRootUrl() + "loginError?error=notProvisioned");
        }

        // Domain gate: even a pre-provisioned user or allowed-group member must present a
        // Microsoft-verified email/UPN in an allowed domain (defence against e.g. a guest or
        // cross-tenant account slipping in through group membership). Empty list = allow all.
        String emailForDomain = (email != null && !email.isEmpty()) ? email
                : (upn != null ? upn : "");
        if (!ProvisioningPortalAction.domainAllowed(emailForDomain, getAllowedDomains())) {
            String displayId = upn != null ? upn : oid;
            int at = emailForDomain.lastIndexOf('@');
            String domain = at >= 0 ? emailForDomain.substring(at + 1) : "(none)";
            LOGGER.log(Level.WARNING, "Rejected SSO login — domain not allowed: {0}", displayId);
            OmniAuthAuditLog audit = OmniAuthAuditLog.get();
            if (audit != null) audit.logLoginDeniedDomain(displayId, domain, req.getRemoteAddr());
            return HttpResponses.redirectTo(Jenkins.get().getRootUrl() + "loginError?error=domainNotAllowed");
        }

        // Provision / update Jenkins user
        User jenkinsUser = realm.getOrCreateEntraUser(oid, upn, name, groups);

        // Establish Spring Security session
        EntraUserDetails userDetails = new EntraUserDetails(
                jenkinsUser.getId(), name, email, oid, groups);
        EntraAuthenticationToken auth = new EntraAuthenticationToken(userDetails);

        // Use SecurityContextImpl explicitly — Spring Security 6's holder may return
        // a deferred/lazy wrapper that doesn't serialize cleanly into the HTTP session.
        SecurityContext securityContext = new SecurityContextImpl(auth);
        SecurityContextHolder.setContext(securityContext);

        // Rotate the session id on this privilege change (session-fixation defence), mirroring
        // the native path's establishSession(). The pre-auth stash (from URL, seed) survives the
        // rotation because changeSessionId() keeps the session's attributes.
        req.getSession(); // ensure a session exists before rotating
        try { req.changeSessionId(); } catch (Exception ignore) { /* no session / unsupported */ }

        // Persist the security context into the HTTP session.
        req.getSession().setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);

        // Jenkins's HttpSessionContextIntegrationFilter2 checks "_JENKINS_SESSION_SEED" on
        // every request and wipes the security context if it is absent or stale.
        // SecurityListener.fireAuthenticated2() tries to store it via Stapler.getCurrentRequest2()
        // (the Jakarta-Servlet API), but doFinishLogin uses the old StaplerRequest API so
        // getCurrentRequest2() returns null and the seed is never written.
        // We store it directly here to guarantee the check passes.
        jenkins.security.seed.UserSeedProperty seedProp =
                jenkinsUser.getProperty(jenkins.security.seed.UserSeedProperty.class);
        if (seedProp != null) {
            req.getSession().setAttribute(
                    jenkins.security.seed.UserSeedProperty.USER_SESSION_SEED,
                    seedProp.getSeed());
        }

        // Notify Jenkins security listeners (audit log, active sessions tracker, etc.)
        SecurityListener.fireAuthenticated2(userDetails);

        LOGGER.log(Level.INFO, "Entra login successful: {0} (OID: {1})", new Object[]{upn, oid});

        String from = (String) req.getSession().getAttribute(SESSION_FROM_URL);
        req.getSession().removeAttribute(SESSION_FROM_URL);
        String dest = (from != null && !from.isEmpty())
                ? from
                : (Jenkins.get().getRootUrl() != null ? Jenkins.get().getRootUrl() : "/");
        return HttpResponses.redirectTo(dest);
    }

    /**
     * Handle a provisioning-portal access request that came through the Microsoft sign-in flow.
     * The caller has already proven the user's identity via OAuth; here we record a PENDING
     * {@link ProvisioningRequest} (unless the user already has access or a request is pending),
     * then send them back to the request page. Never establishes a session.
     */
    private HttpResponse handleEntraProvisionRequest(OmniAuthSecurityRealm realm, String oid, String upn, String name,
                                                     String email, List<EntraGroupDetails> groups) {
        String root = Jenkins.get().getRootUrl();
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();

        // Portal must be enabled with Entra requests allowed; otherwise behave like a normal
        // not-provisioned rejection so we don't silently swallow the login.
        if (cfg == null || !cfg.isProvisioningEnabled() || !cfg.isProvisioningAllowEntra()) {
            return HttpResponses.redirectTo(root + "loginError?error=notProvisioned");
        }

        // The Jenkins user id mirrors the login flow: UPN (email) when present, else the OID.
        String userId = (upn != null && !upn.isEmpty()) ? upn : oid;

        // Already has access (individually or via group)? Don't create a request — tell them.
        if (realm.isPreProvisioned(oid, upn, groups) || User.getById(userId, false) != null) {
            return HttpResponses.redirectTo(root + "request-access/?already=1");
        }

        // Optional domain gate, against the verified UPN/email.
        String emailForDomain = (email != null && !email.isEmpty()) ? email : userId;
        if (!ProvisioningPortalAction.domainAllowed(emailForDomain, cfg.getProvisioningAllowedDomains())) {
            return HttpResponses.redirectTo(root + "request-access/?error=domain");
        }

        // Already a pending request for this identity? Don't ask for a reason again.
        ProvisioningRequestConfig store = ProvisioningRequestConfig.get();
        if (store != null && store.findPendingForUser(userId) != null) {
            return HttpResponses.redirectTo(root + "request-access/?submitted=1");
        }

        // Identity is Microsoft-verified. Stash it on the session and collect a reason on the
        // next page; the PENDING request is created from that submission (ProvisioningPortalAction
        // #doSubmitEntraReason), bringing Entra to parity with the native form's reason field.
        StaplerRequest2 cur = Stapler.getCurrentRequest2();
        if (cur != null) {
            jakarta.servlet.http.HttpSession s = cur.getSession(true);
            s.setAttribute(ProvisioningPortalAction.SESSION_ENTRA_OID,    oid);
            s.setAttribute(ProvisioningPortalAction.SESSION_ENTRA_USERID, userId);
            s.setAttribute(ProvisioningPortalAction.SESSION_ENTRA_NAME,   name  != null ? name  : "");
            s.setAttribute(ProvisioningPortalAction.SESSION_ENTRA_EMAIL,  email != null ? email : "");
        }
        LOGGER.log(Level.INFO, "Provisioning: Entra identity verified for {0}, collecting reason", userId);
        return HttpResponses.redirectTo(root + "request-access/reason");
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public Secret getClientSecret() {
        return clientSecret;
    }

    public boolean isEnableGroupSync() {
        return enableGroupSync;
    }

    @DataBoundSetter
    public void setEnableGroupSync(boolean enableGroupSync) {
        this.enableGroupSync = enableGroupSync;
    }

    /**
     * Comma/space-separated list of email domains permitted to sign in via SSO (e.g.
     * {@code example.com}). Empty means allow any domain. This is an authentication-time gate,
     * distinct from the provisioning portal's domain allowlist (which governs who may request
     * access). Enforced in the login callback against the Microsoft-verified UPN/email.
     */
    public String getAllowedDomains() {
        return allowedDomains != null ? allowedDomains : "";
    }

    @DataBoundSetter
    public void setAllowedDomains(String allowedDomains) {
        this.allowedDomains = allowedDomains != null ? allowedDomains.trim() : "";
    }

    /**
     * Returns the OAuth2 scopes to request. Always includes openid, profile, email.
     * Adds the Graph API scope whenever group information may be needed — i.e. group sync
     * is enabled OR any GROUP entity exists in Access Management. (Otherwise group-based
     * access would silently break: the token would lack the Graph scope, the /me/memberOf
     * call would 403, and every group-gated user would be rejected at login.)
     */
    public String getScope() {
        if (enableGroupSync || groupsConfigured()) {
            return "openid profile email https://graph.microsoft.com/.default";
        }
        return "openid profile email";
    }

    private static boolean groupsConfigured() {
        OmniAuthAssignmentConfig c = OmniAuthAssignmentConfig.get();
        return c != null && c.hasAnyGroups();
    }

    /**
     * Returns the Azure AD authority URL for this tenant.
     */
    public String getAuthority() {
        return "https://login.microsoftonline.com/" + tenantId;
    }

    @Extension
    @Symbol("entraConfig")
    public static class DescriptorImpl extends Descriptor<OmniAuthSsoProvider> {

        @Override
        public String getDisplayName() {
            return "Microsoft Entra (Azure AD)";
        }

        /**
         * The full redirect URI to register in Azure, built from the configured Jenkins URL — so it
         * can be copied straight into the App Registration. Falls back to a placeholder if the
         * Jenkins URL isn't set yet.
         */
        public String getRedirectUri() {
            String root = jenkins.model.Jenkins.get().getRootUrl();
            if (root == null) return "<set your Jenkins URL in Manage Jenkins → System>/omniauth/finishLogin";
            if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
            return root + "/omniauth/finishLogin";
        }

        // lgtm false positive: Stateless form-validation (doCheck) of a single typed field value; no state change and no sensitive data returned, so neither a POST nor a permission check applies.
        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
        public FormValidation doCheckTenantId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Tenant ID is required");
            }
            return FormValidation.ok();
        }

        // lgtm false positive: Stateless form-validation (doCheck) of a single typed field value; no state change and no sensitive data returned, so neither a POST nor a permission check applies.
        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
        public FormValidation doCheckClientId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Client ID is required");
            }
            return FormValidation.ok();
        }

        // lgtm false positive: Stateless form-validation (doCheck) of a single typed field value; no state change and no sensitive data returned, so neither a POST nor a permission check applies.
        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
        public FormValidation doCheckClientSecret(@QueryParameter Secret value) {
            if (value == null || Secret.toString(value).trim().isEmpty()) {
                return FormValidation.error("Client Secret is required");
            }
            return FormValidation.ok();
        }

        // Lucide-style stroke icons (matching the rest of the plugin's inline SVGs); stroke is
        // currentColor so each inherits the surrounding text colour (green / red).
        private static final String SVG_ATTRS =
                "width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor'"
              + " stroke-width='2' stroke-linecap='round' stroke-linejoin='round'"
              + " style='flex-shrink:0;margin-top:2px'";
        /** Party-popper (celebration) for a successful test. */
        private static final String ICON_CELEBRATE =
                "<svg " + SVG_ATTRS + ">"
              + "<path d='M5.8 11.3 2 22l10.7-3.79'/><path d='M4 3h.01'/><path d='M22 8h.01'/>"
              + "<path d='M15 2h.01'/><path d='M22 20h.01'/>"
              + "<path d='m22 2-2.24.75a2.9 2.9 0 0 0-1.96 3.12c.1.86-.57 1.63-1.45 1.63h-.38"
              + "c-.86 0-1.6.6-1.76 1.44L12 10'/>"
              + "<path d='m22 13-.82-.33c-.86-.34-1.82.2-1.98 1.11c-.11.7-.72 1.22-1.43 1.22H17'/>"
              + "<path d='m11 2 .33.82c.34.86-.2 1.82-1.11 1.98C9.52 4.9 9 5.52 9 6.23V7'/>"
              + "<path d='M11 13c1.93 1.93 2.83 4.17 2 5-.83.83-3.07-.07-5-2-1.93-1.93-2.83-4.17-2-5"
              + " .83-.83 3.07.07 5 2Z'/></svg>";
        /** Circle-x for a failed test. */
        private static final String ICON_X =
                "<svg " + SVG_ATTRS + ">"
              + "<circle cx='12' cy='12' r='10'/><path d='m15 9-6 6'/><path d='m9 9 6 6'/></svg>";

        private static String statusHtml(String colorVar, String icon, String text) {
            return "<span style=\"display:inline-flex;align-items:flex-start;gap:6px;"
                 + "color:" + colorVar + ";font-weight:600;\">"
                 + icon + "<span>" + text + "</span></span>";
        }

        /**
         * Validates the Entra credentials without saving them, so an admin gets immediate
         * feedback instead of discovering a typo at the first SSO sign-in. Performs a real
         * client-credentials token grant against the tenant for the Graph {@code .default}
         * scope: success proves the tenant ID, client ID and client secret are all correct and
         * the app registration can obtain tokens. Admin-only and POST-only (it reaches out to
         * Microsoft with the supplied secret).
         */
        @RequirePOST
        public FormValidation doTestConnection(@QueryParameter String tenantId,
                                               @QueryParameter String clientId,
                                               @QueryParameter Secret clientSecret) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (tenantId == null || tenantId.trim().isEmpty()
                    || clientId == null || clientId.trim().isEmpty()
                    || clientSecret == null || Secret.toString(clientSecret).trim().isEmpty()) {
                return FormValidation.error("Tenant ID, Client ID and Client Secret are all required to test.");
            }

            try {
                IClientSecret credential = ClientCredentialFactory.createFromSecret(clientSecret.getPlainText());
                ConfidentialClientApplication app = ConfidentialClientApplication.builder(clientId.trim(), credential)
                        .authority("https://login.microsoftonline.com/" + tenantId.trim())
                        .validateAuthority(true)
                        .build();
                ClientCredentialParameters params = ClientCredentialParameters
                        .builder(Collections.singleton("https://graph.microsoft.com/.default"))
                        .build();
                app.acquireToken(params).get();
                return FormValidation.okWithMarkup(statusHtml(
                        "var(--success-color, #2e7d32)", ICON_CELEBRATE,
                        "Connection successful — Microsoft Entra accepted these credentials."));
            } catch (Exception e) {
                // MSAL wraps the AADSTS error (bad secret, unknown client, wrong tenant, …) in the
                // cause; surface the clearest message we have so the admin knows which field to fix.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = cause.getMessage();
                if (msg == null || msg.isBlank()) msg = e.toString();
                // msg is from Microsoft — escape it before embedding in markup.
                return FormValidation.errorWithMarkup(statusHtml(
                        "var(--error-color, #c0392b)", ICON_X,
                        "Connection failed: " + Util.escape(msg)));
            }
        }
    }
}
