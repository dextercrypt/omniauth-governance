package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Exempts the SOS recovery POST ({@code /sos/recover}) from CSRF crumb checking, mirroring how the
 * native login POST is exempt. The endpoint is reachable by unauthenticated users by design (a
 * locked-out admin has no session to carry a crumb), and a forged POST is harmless to an attacker
 * who does not possess a valid single-use recovery code.
 */
@Extension
public class SosCrumbExclusion extends CrumbExclusion {

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getPathInfo();
        if (path != null && path.equals(OmniAuthSosRootAction.RECOVER_PATH)) {
            chain.doFilter(request, response);
            return true;
        }
        return false;
    }
}
