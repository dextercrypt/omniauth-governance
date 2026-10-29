package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import jakarta.servlet.http.HttpSession;
import jenkins.model.Jenkins;
import jenkins.security.SecurityListener;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The SOS recovery endpoint at {@code /sos/recover}, reachable without a login (it exists precisely
 * for when no admin can log in). It is <b>dark</b> — redirects to the normal login page — unless
 * {@link OmniAuthSos#isArmed() SOS Mode is armed}. While armed it accepts a single
 * {@link OmniAuthRecoveryCodesProperty recovery code} (no username); a match identifies the admin,
 * re-grants {@link Jenkins#ADMINISTER}, and signs them in. See {@link OmniAuthSos} for the full model.
 *
 * <p>As an {@link UnprotectedRootAction}, Jenkins adds {@code /sos/**} to the Spring Security permit
 * list so an unauthenticated browser can reach it. The POST is CSRF-crumb-excluded via
 * {@link SosCrumbExclusion} (an attacker without a code cannot do anything with a forged POST).
 */
@Extension
public class OmniAuthSosRootAction implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(OmniAuthSosRootAction.class.getName());

    /** The POST path that {@link SosCrumbExclusion} must exempt. */
    static final String RECOVER_PATH = "/sos/recover";

    @Override public String getUrlName()      { return "sos"; }
    @Override public String getIconFileName() { return null; }
    @Override public String getDisplayName()  { return null; }

    /** GET renders the code form; POST verifies a submitted code. Both 302 to login when not armed. */
    // lgtm false positive: Emergency sign-in recovery (SOS): unauthenticated by design and gated by an armed window + one-time recovery code, not a Jenkins crumb; the POST branch verifies the code itself.
    @SuppressWarnings("lgtm[jenkins/csrf]")
    public void doRecover(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        String ctx = req.getContextPath();

        if (!OmniAuthSos.isArmed()) {
            rsp.sendRedirect(ctx + "/securityRealm/login");
            return;
        }
        // Announce the armed window promptly even before the worker's next tick.
        OmniAuthSos.noteArmedIfNeeded();

        // If the viewer already holds admin (e.g. they just recovered), there is nothing to recover.
        // Don't re-prompt — that would waste a single-use code — and don't frame it as a lockout.
        boolean alreadyAdmin = currentUserIsAdmin();

        if ("POST".equalsIgnoreCase(req.getMethod())) {
            if (alreadyAdmin) { rsp.sendRedirect(ctx + "/"); return; }
            handlePost(req, rsp, ctx);
        } else if (alreadyAdmin) {
            writePage(rsp, 200, alreadyAdminPage(ctx, currentUserId(), req));
        } else {
            String err = req.getParameter("e");
            writePage(rsp, 200, formPage(ctx, err, OmniAuthSos.attemptsRemaining(), OmniAuthSos.minutesRemaining()));
        }
    }

    private static boolean currentUserIsAdmin() {
        try {
            return Jenkins.get().hasPermission(Jenkins.ADMINISTER);
        } catch (Exception e) {
            return false;
        }
    }

    private static String currentUserId() {
        User u = User.current();
        return u != null ? u.getId() : "";
    }

    private void handlePost(StaplerRequest2 req, StaplerResponse2 rsp, String ctx) throws Exception {
        String code = req.getParameter("code");
        User recovered = OmniAuthSos.resolveAndConsume(code);

        if (recovered == null) {
            boolean locked = OmniAuthSos.recordFailedAttempt();
            if (locked) {
                writePage(rsp, 200, lockedPage());
            } else {
                rsp.sendRedirect(ctx + RECOVER_PATH + "?e=bad");
            }
            return;
        }

        String sid = recovered.getId();
        boolean adminGranted = OmniAuthSos.grantAdminister(sid);
        establishSession(req, recovered);

        OmniAuthAuditLog audit = OmniAuthAuditLog.get();
        if (audit != null) audit.logSosRecoverySuccess(sid);
        LOGGER.log(Level.WARNING, "OmniAuth SOS recovery succeeded for {0} (admin granted: {1}).",
                new Object[]{ sid, adminGranted });

        if (adminGranted) {
            // Land on the home page; the site-wide banner shows SOS is still active with a Deactivate action.
            rsp.sendRedirect(ctx + "/");
        } else {
            // Signed in, but the active authorization strategy is not matrix-based, so admin could not be
            // restored. Be explicit rather than dropping the user onto a confusing 403.
            writePage(rsp, 200, noAdminPage(ctx, sid, OmniAuthSos.activeAuthorizationStrategyName()));
        }
    }

    /** Build a session for {@code user} from their granted authorities (no password involved). */
    private static void establishSession(StaplerRequest2 req, User user) {
        try {
            Authentication auth = user.impersonate2();
            SecurityContext sc = new SecurityContextImpl();
            sc.setAuthentication(auth);
            SecurityContextHolder.setContext(sc);
            HttpSession session = req.getSession(true);
            try { req.changeSessionId(); } catch (IllegalStateException ignore) { /* no session to rotate */ }
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sc);
            if (auth.getPrincipal() instanceof UserDetails) {
                SecurityListener.fireAuthenticated2((UserDetails) auth.getPrincipal());
            }
            SecurityListener.fireLoggedIn(user.getId());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SOS: failed to establish session for " + user.getId(), e);
        }
    }

    // ----- page rendering (standalone — the user may be fully locked out) -----

    private static void writePage(StaplerResponse2 rsp, int status, String html) throws java.io.IOException {
        rsp.setStatus(status);
        rsp.setContentType("text/html;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-store");
        rsp.getWriter().write(html);
    }

    private static String formPage(String ctx, String err, int attemptsLeft, long minsLeft) {
        String errorHtml = "";
        if ("bad".equals(err)) {
            errorHtml = "<div class='err'>That code was not recognised. "
                    + attemptsLeft + " attempt" + (attemptsLeft == 1 ? "" : "s") + " left before SOS locks.</div>";
        }
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>SOS Recovery</title>" + STYLE
            + "</head><body><div class='card'>"
            + BRAND
            + "<div class='badge'>SOS MODE ACTIVE</div>"
            + "<h1>Emergency recovery</h1>"
            + "<p class='sub'>Enter one of your SOS recovery codes to regain administrator access. "
            + "This window closes automatically in about " + minsLeft + " minute" + (minsLeft == 1 ? "" : "s") + ".</p>"
            + errorHtml
            + "<form method='POST' action='" + esc(ctx) + RECOVER_PATH + "' autocomplete='off'>"
            + "<label for='code'>Recovery code</label>"
            + "<input id='code' name='code' type='text' autocomplete='off' autocapitalize='characters' "
            + "spellcheck='false' placeholder='XXXX-XXXX-XXXX' autofocus />"
            + "<button type='submit'>Recover access</button>"
            + "</form>"
            + "<p class='foot'>Each code works once. Every recovery attempt is written to the OmniAuth audit log.</p>"
            + "</div></body></html>";
    }

    private static String alreadyAdminPage(String ctx, String sid, StaplerRequest2 req) {
        String who = (sid == null || sid.isEmpty()) ? "an administrator" : "<strong>" + esc(sid) + "</strong>";
        // Deactivation is a plain form POST — no JavaScript — so it works under a strict Content-Security-Policy
        // (which blocks inline scripts and inline on* handlers) and on a degraded recovery session. The viewer
        // is a confirmed admin, so a CSRF crumb is available; embed it as a hidden field. The hidden "fromForm"
        // marker tells doSosDeactivate to 302 back to Jenkins instead of writing its JSON (fetch) response.
        String crumbField = "";
        String crumb = "";
        try {
            hudson.security.csrf.CrumbIssuer ci = Jenkins.get().getCrumbIssuer();
            if (ci != null) {
                crumbField = ci.getDescriptor().getCrumbRequestField();
                crumb = ci.getCrumb(req);
            }
        } catch (Exception ignore) { /* no crumb issuer configured: form still posts, filter decides */ }
        String crumbInput = (crumbField == null || crumbField.isEmpty()) ? ""
            : "<input type='hidden' name='" + esc(crumbField) + "' value='" + esc(crumb) + "'/>";
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>SOS Recovery</title>" + STYLE
            + "</head><body><div class='card'>"
            + BRAND
            + "<div class='badge'>SOS MODE ACTIVE</div>"
            + "<h1>You already have access</h1>"
            + "<p class='sub'>You are signed in as " + who + " with administrator access, so there is nothing to "
            + "recover. SOS Mode is still open — deactivate it once you no longer need the recovery window.</p>"
            + "<form method='POST' action='" + esc(ctx) + "/manage/omniauth-management/sosDeactivate' style='margin:0;'>"
            + crumbInput
            + "<input type='hidden' name='fromForm' value='1'/>"
            + "<button type='submit'>Deactivate SOS Mode</button>"
            + "</form>"
            + "<a class='btn-ghost' href='" + esc(ctx) + "/'>Continue to Jenkins</a>"
            + "<p class='foot'>Deactivating is recorded in the OmniAuth audit log.</p>"
            + "</div></body></html>";
    }

    private static String noAdminPage(String ctx, String sid, String strategyName) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>SOS Recovery</title>" + STYLE
            + "</head><body><div class='card'>"
            + BRAND
            + "<div class='badge'>SIGNED IN</div>"
            + "<h1>Admin not fully restored</h1>"
            + "<p class='sub'>You are signed in as <strong>" + esc(sid) + "</strong>, but administrator rights "
            + "could not be restored automatically because the active authorization strategy "
            + "(<strong>" + esc(strategyName) + "</strong>) is not matrix-based, so SOS cannot add a grant to it.</p>"
            + "<p class='sub'>To regain administrator access, repair the authorization strategy at the host level: "
            + "edit <code>config.xml</code> in <code>JENKINS_HOME</code> (or start with "
            + "<code>-Djenkins.model.Jenkins.useSecurity=false</code>) and restart.</p>"
            + "<a class='btn-link' href='" + esc(ctx) + "/'>Continue to Jenkins</a>"
            + "<p class='foot'>This recovery was recorded in the OmniAuth audit log.</p>"
            + "</div></body></html>";
    }

    private static String lockedPage() {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>SOS Locked</title>" + STYLE
            + "</head><body><div class='card'>"
            + BRAND
            + "<div class='badge badge-red'>SOS LOCKED</div>"
            + "<h1>Too many attempts</h1>"
            + "<p class='sub'>SOS Mode has been disarmed after " + OmniAuthSos.MAX_FAILED_ATTEMPTS
            + " failed recovery attempts. To try again, an operator with host access must drop a fresh "
            + "<code>" + OmniAuthSos.TRIGGER_FILE + "</code> file in <code>JENKINS_HOME</code>.</p>"
            + "<p class='foot'>This lockout was recorded in the OmniAuth audit log.</p>"
            + "</div></body></html>";
    }

    /**
     * OmniAuth brand lockup (mark + wordmark), inlined because the page is served pre-login and the
     * plugin's static assets are behind auth. The mark matches {@code images/omniauth-mark.svg}.
     */
    private static final String BRAND =
        "<div class='brand'>"
        + "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64' width='40' height='40' "
        + "shape-rendering='geometricPrecision' aria-hidden='true'>"
        + "<defs><radialGradient id='oauG' cx='50%' cy='52%' r='46%'>"
        + "<stop offset='0%' stop-color='#2563eb' stop-opacity='0.22'/>"
        + "<stop offset='100%' stop-color='#2563eb' stop-opacity='0'/></radialGradient></defs>"
        + "<rect x='6' y='6' width='52' height='52' rx='13' fill='#0c1628'/>"
        + "<rect x='6' y='6' width='52' height='52' rx='13' fill='url(#oauG)'/>"
        + "<path d='M16 51 L16 32 A16 16 0 0 1 32 16' fill='none' stroke='#5b8bf0' stroke-width='8.5' stroke-linecap='butt'/>"
        + "<path d='M32 16 A16 16 0 0 1 48 32 L48 51' fill='none' stroke='#2563eb' stroke-width='8.5' stroke-linecap='butt'/>"
        + "<circle cx='16' cy='51' r='3.2' fill='#5b8bf0'/>"
        + "<circle cx='48' cy='51' r='3.2' fill='#2563eb'/>"
        + "<circle cx='32' cy='29' r='5' fill='#ffffff'/>"
        + "<path d='M30 33 H34 L33.2 39.5 H30.8 Z' fill='#ffffff'/>"
        + "</svg>"
        + "<span class='wm'>Omni<span class='wm-a'>Auth</span></span>"
        + "</div>";

    private static final String STYLE =
        "<style>*{box-sizing:border-box;}"
        + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
        + "padding:24px 16px;background:#0f172a;color:#e2e8f0;"
        + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;}"
        + ".card{background:#1e293b;border:1px solid #334155;border-radius:12px;max-width:420px;width:100%;"
        + "padding:28px 26px;box-shadow:0 10px 40px rgba(0,0,0,0.4);}"
        + ".brand{display:flex;align-items:center;justify-content:center;gap:11px;margin-bottom:22px;}"
        + ".brand .wm{font-size:22px;font-weight:800;letter-spacing:-0.01em;color:#f8fafc;line-height:1;}"
        + ".brand .wm-a{color:#5b8bf0;}"
        + ".badge{display:inline-block;font-size:11px;font-weight:700;letter-spacing:1px;"
        + "background:#b45309;color:#fff;padding:4px 10px;border-radius:999px;margin-bottom:16px;}"
        + ".badge-red{background:#b91c1c;}"
        + "h1{margin:0 0 8px;font-size:20px;color:#f8fafc;}"
        + ".sub{margin:0 0 18px;font-size:13px;line-height:1.6;color:#94a3b8;}"
        + "label{display:block;font-size:12px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;"
        + "color:#cbd5e1;margin-bottom:6px;}"
        + "input{width:100%;padding:11px 12px;border:1px solid #475569;border-radius:7px;background:#0f172a;"
        + "color:#f1f5f9;font-size:15px;letter-spacing:2px;text-transform:uppercase;margin-bottom:16px;}"
        + "input:focus{outline:none;border-color:#f59e0b;}"
        + "button{width:100%;padding:11px;border:none;border-radius:7px;background:#f59e0b;color:#1e293b;"
        + "font-size:14px;font-weight:700;cursor:pointer;}button:hover{background:#fbbf24;}"
        + ".btn-link{display:block;text-align:center;padding:11px;border-radius:7px;background:#f59e0b;"
        + "color:#1e293b;font-size:14px;font-weight:700;text-decoration:none;}.btn-link:hover{background:#fbbf24;}"
        + ".btn-ghost{display:block;text-align:center;padding:11px;margin-top:10px;border-radius:7px;"
        + "background:transparent;border:1px solid #475569;color:#e2e8f0;font-size:14px;font-weight:600;"
        + "text-decoration:none;}.btn-ghost:hover{background:#0f172a;border-color:#64748b;}"
        + ".err{background:#7f1d1d;border:1px solid #b91c1c;color:#fecaca;font-size:13px;padding:9px 12px;"
        + "border-radius:7px;margin-bottom:16px;}"
        + ".foot{margin:18px 0 0;font-size:11px;color:#64748b;line-height:1.6;}"
        + "code{background:#0f172a;border:1px solid #334155;border-radius:4px;padding:1px 5px;font-size:11px;}"
        + "</style>";

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
