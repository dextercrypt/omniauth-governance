package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Exempts the public provisioning portal's submissions from CSRF crumb checking,
 * exactly as {@link NativeLoginCrumbExclusion} does for the login form.
 *
 * <p>{@code /request-access/submitNative} and {@code /request-access/submitEntraReason} are
 * posted by <em>anonymous</em> users, for whom a Jelly-issued crumb is unreliable. CSRF adds
 * little here — both endpoints only create a {@code PENDING} request that still requires admin
 * approval; the native form is additionally guarded by the {@link ProvisioningBreaker}, and the
 * Entra reason page requires a Microsoft-verified identity already stashed on the session.
 */
@Extension
public class ProvisioningCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getPathInfo();
        if (path != null
                && (path.equals("/request-access/submitNative")
                 || path.equals("/request-access/submitEntraReason"))) {
            chain.doFilter(request, response);
            return true;
        }
        return false;
    }
}
