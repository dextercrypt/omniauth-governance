package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import hudson.security.HudsonPrivateSecurityRealm;
import jakarta.servlet.http.HttpSession;
import jenkins.model.Jenkins;
import jenkins.security.SecurityListener;
import jenkins.security.stapler.StaplerDispatchable;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Exposes the Microsoft Entra OAuth2 endpoints at /omniauth/commenceLogin and
 * /omniauth/finishLogin as an UnprotectedRootAction.
 *
 * Why not on OmniAuthSecurityRealm directly?
 * OmniAuthSecurityRealm extends HudsonPrivateSecurityRealm, which configures
 * Spring Security to require authentication for all paths except the built-in
 * login/logout/signup pages. Our commenceLogin and finishLogin endpoints must
 * be reachable by unauthenticated users (that is the whole point — they are
 * trying to log in). UnprotectedRootAction is the Jenkins-supported mechanism
 * for exactly this use case: Jenkins automatically adds /omniauth/** to the
 * Spring Security permit list for all UnprotectedRootAction implementations.
 */
@Extension
public class OmniAuthRootAction implements UnprotectedRootAction {

    /** URL prefix: /omniauth/... */
    @Override
    public String getUrlName() {
        return "omniauth";
    }

    /** No icon — this action is not visible in the side panel. */
    @Override
    public String getIconFileName() {
        return null;
    }

    /** No display name — hidden from the Jenkins UI. */
    @Override
    public String getDisplayName() {
        return null;
    }

    /**
     * Step 1 — redirect browser to Azure AD login page.
     * URL: GET /omniauth/commenceLogin[?from=…]
     */
    @StaplerDispatchable
    public HttpResponse doCommenceLogin(StaplerRequest req) throws Exception {
        OmniAuthSecurityRealm realm = getOmniAuthRealm();
        if (realm == null) {
            return HttpResponses.error(503,
                    "Omni Auth security realm is not active.");
        }
        return realm.startEntraLogin(req);
    }

    /**
     * Step 2 — Azure AD posts back with an authorization code.
     * URL: GET /omniauth/finishLogin?code=…&state=…
     */
    @StaplerDispatchable
    public HttpResponse doFinishLogin(StaplerRequest req) throws Exception {
        OmniAuthSecurityRealm realm = getOmniAuthRealm();
        if (realm == null) {
            return HttpResponses.error(503,
                    "Omni Auth security realm is not active.");
        }
        return realm.finishEntraLogin(req);
    }

    // -------------------------------------------------------------------------
    // Native login + force-password-change (Option B: no session until the
    // password is set). The login form posts here instead of straight to
    // j_spring_security_check so that a user who still owes a password change is
    // diverted to the branded set-password page WITHOUT ever being logged in.
    // The auth-layer lockout in OmniAuthSecurityRealm.createSecurityComponents()
    // independently closes BASIC / API-token / CLI for the same users.
    // -------------------------------------------------------------------------

    /** Session attribute holding the username that has validated its temp password and may set a new one. */
    static final String PENDING_USER = "omniauth.pendingPwdUser";
    /** Session attribute holding when the pending marker was issued (ms epoch), for expiry. */
    static final String PENDING_AT = "omniauth.pendingPwdAt";
    private static final long PENDING_TTL_MS = 15 * 60 * 1000L;

    /**
     * POST: native username/password login.
     * Delegates to the realm's (wrapped) AuthenticationManager:
     *  - normal user → establish the session here and fire the standard events;
     *  - valid password but force-change owed → {@link PasswordChangeRequiredException} is
     *    caught, a pending marker is stashed, and the user is sent to the set-password page
     *    with NO session;
     *  - bad credentials → record the failure and bounce to the styled error page.
     */
    @POST
    @StaplerDispatchable
    public void doNativeLogin(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        String ctx      = req.getContextPath();
        String username = req.getParameter("j_username");
        String password = req.getParameter("j_password");
        String from     = req.getParameter("from");

        OmniAuthSecurityRealm realm = getOmniAuthRealm();
        if (realm == null || username == null || username.isEmpty()) {
            rsp.sendRedirect(ctx + "/loginError"); return;
        }

        try {
            Authentication auth = realm.getSecurityComponents().manager2.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));

            // Normal user — log them in ourselves, mirroring Jenkins' own form login.
            establishSession(req, auth);
            if (isChecked(req.getParameter("remember_me"))) {
                try {
                    RememberMeServices rms = realm.getSecurityComponents().rememberMe2;
                    if (rms != null) rms.loginSuccess(req, rsp, auth);
                } catch (Exception ignore) { /* remember-me is best-effort */ }
            }
            SecurityListener.fireLoggedIn(auth.getName());
            rsp.sendRedirect(safeFrom(from, ctx));

        } catch (PasswordChangeRequiredException e) {
            // Credentials were valid, but a password change is owed — do NOT log in.
            HttpSession s = req.getSession(true);
            s.setAttribute(PENDING_USER, username);
            s.setAttribute(PENDING_AT, System.currentTimeMillis());
            rsp.sendRedirect(ctx + "/omniauth/changePassword");

        } catch (org.springframework.security.authentication.LockedException e) {
            // Too many failed attempts. We rejected BEFORE the delegate ran, so core
            // fires no failure event — fire it ourselves to record the attempt and
            // slide the cooldown, and show the dedicated "locked" message.
            SecurityListener.fireFailedToAuthenticate(username);
            rsp.sendRedirect(ctx + "/loginError?error=locked");

        } catch (AuthenticationException e) {
            // The delegate already published a failure event (which core bridges to
            // SecurityListener.failedToAuthenticate), so do NOT fire again here or the
            // brute-force counter double-counts and locks at half the configured threshold.
            rsp.sendRedirect(ctx + "/loginError");
        }
    }

    /**
     * GET: one-time activation link for a provisioning-portal native account.
     * Validates the token on the user's {@link OmniAuthActivationProperty} and, on success,
     * marks the set-password session so the requester lands on the branded set-password page
     * without ever handling a password. Invalid / expired / already-used → branded login error.
     * URL: /omniauth/activate?u=&lt;userId&gt;&amp;t=&lt;rawToken&gt;
     */
    @StaplerDispatchable
    public void doActivate(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        String ctx    = req.getContextPath();
        String userId = req.getParameter("u");
        String token  = req.getParameter("t");
        if (userId == null || userId.isEmpty() || token == null || token.isEmpty()) {
            rsp.sendRedirect(ctx + "/loginError?error=activation"); return;
        }
        User user = User.getById(userId, false);
        OmniAuthActivationProperty act = (user != null) ? user.getProperty(OmniAuthActivationProperty.class) : null;
        // Valid only while the account still owes its first password set (single-use by construction:
        // the property is removed once the password is set).
        if (act == null || !act.matches(token) || !OmniAuthSecurityRealm.isForcePasswordChange(userId)) {
            rsp.sendRedirect(ctx + "/loginError?error=activation"); return;
        }
        HttpSession s = req.getSession(true);
        s.setAttribute(PENDING_USER, userId);
        s.setAttribute(PENDING_AT, System.currentTimeMillis());
        rsp.sendRedirect(ctx + "/omniauth/changePassword");
    }

    /** GET: serve the branded set-password page, but only to a user that has a valid pending marker. */
    @StaplerDispatchable
    public void doChangePassword(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        if (pendingUser(req) == null) {
            rsp.sendRedirect(req.getContextPath() + "/securityRealm/login"); return;
        }
        req.getView(this, "changePassword.jelly").forward(req, rsp);
    }

    /** Exposed to changePassword.jelly so it can greet the user by name. */
    public String getPendingPasswordUser() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req == null ? null : pendingUser(req);
    }

    /**
     * CSRF crumb field name / value for the set-password form. This custom page doesn't use
     * the standard Jenkins layout (which auto-injects the crumb), so the form supplies it
     * explicitly. Empty when no crumb issuer is configured (then the endpoint is unprotected
     * by crumb anyway, matching core behaviour with CSRF protection disabled).
     */
    public String getCrumbFieldName() {
        hudson.security.csrf.CrumbIssuer ci = jenkins.model.Jenkins.get().getCrumbIssuer();
        return ci != null ? ci.getCrumbRequestField() : "";
    }

    public String getCrumbValue() {
        hudson.security.csrf.CrumbIssuer ci = jenkins.model.Jenkins.get().getCrumbIssuer();
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return (ci != null && req != null) ? ci.getCrumb(req) : "";
    }

    /** POST: set the new password for the pending user, clear the flag, send them to sign in. */
    @POST
    @StaplerDispatchable
    public void doSubmitPasswordChange(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        String newPwd     = req.getParameter("newPassword");
        String confirmPwd = req.getParameter("confirmPassword");
        String ctx        = req.getContextPath();

        // Identity comes from the pending marker, NOT from an authenticated session.
        String username = pendingUser(req);
        if (username == null) {
            rsp.sendRedirect(ctx + "/securityRealm/login"); return;
        }
        if (newPwd == null || newPwd.length() < 8) {
            rsp.sendRedirect(ctx + "/omniauth/changePassword?error=tooShort"); return;
        }
        if (!newPwd.equals(confirmPwd)) {
            rsp.sendRedirect(ctx + "/omniauth/changePassword?error=mismatch"); return;
        }

        User user = User.getById(username, false);
        if (user == null) {
            rsp.sendRedirect(ctx + "/omniauth/changePassword?error=noUser"); return;
        }
        // Defence in depth: only proceed if this user genuinely owes a password change.
        OmniAuthForcePasswordProperty prop = user.getProperty(OmniAuthForcePasswordProperty.class);
        if (prop == null || !prop.isForcePasswordChange()) {
            clearPending(req);
            rsp.sendRedirect(ctx + "/securityRealm/login"); return;
        }

        // Update password via reflection (fromPlainPassword is package-private in Jenkins core)
        try {
            java.lang.reflect.Method m = HudsonPrivateSecurityRealm.Details.class
                    .getDeclaredMethod("fromPlainPassword", String.class);
            m.setAccessible(true);
            HudsonPrivateSecurityRealm.Details details =
                    (HudsonPrivateSecurityRealm.Details) m.invoke(null, newPwd);
            user.addProperty(details);
        } catch (Exception e) {
            rsp.sendRedirect(ctx + "/omniauth/changePassword?error=saveFailed"); return;
        }

        prop.setForcePasswordChange(false);
        // Consume any one-time activation token. Clearing force-password-change already makes the
        // link unusable (doActivate requires it), but overwrite the token with an expired marker
        // so no live hash lingers in JENKINS_HOME.
        if (user.getProperty(OmniAuthActivationProperty.class) != null) {
            user.addProperty(new OmniAuthActivationProperty("", 1L));
        }
        user.save();
        clearPending(req);

        // Fail-closed: send them to sign in with the new password (no auto-login).
        rsp.sendRedirect(ctx + "/securityRealm/login?passwordSet=1");
    }

    // ----- helpers -----------------------------------------------------------

    /** Establish an authenticated session for {@code auth}, mirroring Jenkins' own form login. */
    private static void establishSession(StaplerRequest2 req, Authentication auth) {
        SecurityContext sc = new SecurityContextImpl();
        sc.setAuthentication(auth);
        SecurityContextHolder.setContext(sc);
        HttpSession session = req.getSession(true);
        try { req.changeSessionId(); } catch (IllegalStateException ignore) { /* no session to rotate */ }
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);
        if (auth.getPrincipal() instanceof UserDetails) {
            SecurityListener.fireAuthenticated2((UserDetails) auth.getPrincipal());
        }
    }

    /** The username from a valid, unexpired pending marker, or {@code null}. */
    private static String pendingUser(StaplerRequest2 req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        Object u  = s.getAttribute(PENDING_USER);
        Object at = s.getAttribute(PENDING_AT);
        if (u == null || at == null) return null;
        long ts = (at instanceof Long) ? (Long) at : 0L;
        if (System.currentTimeMillis() - ts > PENDING_TTL_MS) {
            clearPending(req);
            return null;
        }
        return u.toString();
    }

    private static void clearPending(StaplerRequest2 req) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.removeAttribute(PENDING_USER);
            s.removeAttribute(PENDING_AT);
        }
    }

    private static boolean isChecked(String v) {
        return "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** Only allow same-site relative redirect targets to avoid an open redirect. */
    private static String safeFrom(String from, String ctx) {
        if (from != null && from.startsWith("/")
                // reject //host and /\host (browsers treat the backslash form as protocol-relative)
                && !(from.length() > 1 && (from.charAt(1) == '/' || from.charAt(1) == '\\'))
                && from.indexOf('\\') < 0
                && from.chars().noneMatch(c -> c < 0x20)) {
            return from;
        }
        return ctx + "/";
    }

    private OmniAuthSecurityRealm getOmniAuthRealm() {
        hudson.security.SecurityRealm realm = Jenkins.get().getSecurityRealm();
        return realm instanceof OmniAuthSecurityRealm
                ? (OmniAuthSecurityRealm) realm
                : null;
    }
}
