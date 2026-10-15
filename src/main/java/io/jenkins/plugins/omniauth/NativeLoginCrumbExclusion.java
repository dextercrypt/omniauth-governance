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
 * <p>Only {@code /omniauth/nativeLogin} (the username/password submission itself) is
 * exempt, mirroring core's own {@code j_spring_security_check} login endpoint. The
 * first-login set-password endpoint ({@code /omniauth/submitPasswordChange}) is NOT
 * exempt — its form carries a real CSRF crumb — so a malicious page can't set a
 * mid-flow user's password on their behalf.
 */
@Extension
public class NativeLoginCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getPathInfo();
        if (path != null && path.equals("/omniauth/nativeLogin")) {
            chain.doFilter(request, response);
            return true;
        }
        return false;
    }
}
