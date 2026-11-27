package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.util.PluginServletFilter;
import jenkins.model.Jenkins;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Serves Rotogate's branded login views at Jenkins core's built-in URLs when Rotogate is the
 * active security realm. Core owns /login and /loginError and renders its own plain views there;
 * those jellies live on the core classloader and cannot be overridden from a plugin. This filter
 * intercepts both before Stapler dispatch and forwards them to the matching RotogateSecurityRealm
 * view (securityRealm/login, securityRealm/loginError), so the branded page (butler, background,
 * "Sign in with Microsoft") renders while the original URL is preserved.
 *
 * <p>The realm leaves getLoginUrl() at its core default ("login"), so Jenkins routes every auth
 * challenge to the conventional /login URL and this filter is what brands it (the same way it
 * already brands core's hardcoded /loginError). The forward keeps the request intact (query string
 * such as ?from= is preserved) and does not re-enter this filter (PluginServletFilter runs on
 * REQUEST dispatch only), so there is no loop.
 */
@Extension
public class LoginViewInterceptor {

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public static void registerFilter() throws Exception {
        PluginServletFilter.addFilter(new LoginViewFilter());
    }

    private static class LoginViewFilter implements Filter {

        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;

            String contextPath = req.getContextPath();
            String uri = req.getRequestURI();
            String path = contextPath.isEmpty() ? uri : uri.substring(contextPath.length());

            if (("/login".equals(path) || "/loginError".equals(path))
                    && Jenkins.get().getSecurityRealm() instanceof RotogateSecurityRealm) {
                // getRequestDispatcher path is relative to the servlet context root (no context path prefix)
                req.getRequestDispatcher("/securityRealm" + path).forward(request, response);
                return;
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {}
    }
}
