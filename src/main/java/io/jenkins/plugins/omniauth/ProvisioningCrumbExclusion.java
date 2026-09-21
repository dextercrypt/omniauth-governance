package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Exempts the public provisioning portal's native submission from CSRF crumb checking,
 * exactly as {@link NativeLoginCrumbExclusion} does for the login form.
 *
 * <p>{@code /request-access/submitNative} is posted by <em>anonymous</em> users, for whom a
 * Jelly-issued crumb is unreliable. CSRF adds little here — the endpoint only creates a
 * {@code PENDING} request that still requires admin approval, and the {@link ProvisioningBreaker}
 * guards against flooding.
 */
@Extension
public class ProvisioningCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getPathInfo();
        if (path != null && path.equals("/request-access/submitNative")) {
            chain.doFilter(request, response);
            return true;
        }
        return false;
    }
}
