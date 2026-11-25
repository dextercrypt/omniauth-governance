package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Exempts the Entra SSO callback from CSRF crumb checking.
 *
 * <p>The sign-in flow requests {@code response_mode=query}, so Entra normally returns the
 * authorization response via a GET redirect (no crumb check applies to GET). This exemption is
 * defence-in-depth for any tenant/app configuration that still returns the response as an HTTP
 * <strong>POST</strong> ({@code form_post}): that POST is a cross-site navigation from Microsoft
 * and can never carry a Jenkins crumb, so without this exemption such a callback would fail with
 * "No valid crumb was included in the request". (form_post also loses the SameSite=Lax session
 * cookie, which is why the flow forces query in the first place — see
 * {@code MsalTokenHelper.forceQueryResponseMode}.)
 *
 * <p>This is safe because {@code finishLogin} does not rely on the crumb for CSRF protection:
 * it validates the OAuth2 {@code state} (a single-use random token stashed in the caller's
 * session), the OIDC {@code nonce}, and the PKCE {@code code_verifier}. A forged callback
 * cannot produce a matching state, so cross-site requests are rejected by the flow itself —
 * exactly as Jenkins core exempts its own {@code j_spring_security_check} login endpoint.
 *
 * <p>Only the exact {@code /rotogate/finishLogin} path is exempt; {@code commenceLogin} needs
 * no exemption (it is reached by GET), and no other endpoint is covered.
 */
@Extension
public class FinishLoginCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getPathInfo();
        if (path != null && path.equals("/rotogate/finishLogin")) {
            chain.doFilter(request, response);
            return true;
        }
        return false;
    }
}
