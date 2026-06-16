package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Exempts the native login flow from CSRF crumb checking, exactly as Jenkins core
 * exempts its own {@code j_spring_security_check} login endpoint.
 *
 * <p>These endpoints are reached by <em>anonymous</em> users for whom a Jelly-issued
 * crumb is unreliable, and they are part of the authentication flow itself:
 * <ul>
 *   <li>{@code /omniauth/nativeLogin} — username/password submission;</li>
 *   <li>{@code /omniauth/submitPasswordChange} — first-login set-password, gated by a
 *       server-side pending marker that already requires the valid temporary password.</li>
 * </ul>
 */
@Extension
public class NativeLoginCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getPathInfo();
        if (path != null
                && (path.equals("/omniauth/nativeLogin")
                 || path.equals("/omniauth/submitPasswordChange"))) {
            chain.doFilter(request, response);
            return true;
        }
        return false;
    }
}
