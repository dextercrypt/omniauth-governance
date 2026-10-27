package io.jenkins.plugins.omniauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSRF crumb-exemption SCOPE tests. Pure unit tests — CrumbExclusion has no Jenkins
 * dependency in its constructor and {@code process()} only inspects the request path.
 *
 * The security contract: ONLY the anonymous login/submission endpoints are crumb-exempt.
 * In particular the first-login set-password endpoint ({@code /omniauth/submitPasswordChange})
 * must NOT be exempt — its form carries a real crumb so a malicious page cannot set a
 * mid-flow user's password.
 */
public class CrumbExclusionScopeTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    private static HttpServletRequest reqWithPath(final String path) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                CrumbExclusionScopeTest.class.getClassLoader(),
                new Class<?>[]{ HttpServletRequest.class },
                (proxy, method, args) -> {
                    if ("getPathInfo".equals(method.getName())) return path;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class)     return 0;
                    return null;
                });
    }

    /** FilterChain that records whether the request was let through. */
    private static final class RecordingChain implements FilterChain {
        boolean called = false;
        @Override public void doFilter(ServletRequest request, ServletResponse response) { called = true; }
    }

    /** Runs process() and asserts the exempt/not-exempt outcome is internally consistent. */
    private void assertExempt(boolean expectExempt, boolean actual, RecordingChain chain, String path) {
        if (expectExempt) {
            assertTrue(actual, "process() should return true (exempt) for " + path);
            assertTrue(chain.called, "chain.doFilter must be invoked when exempt for " + path);
        } else {
            assertFalse(actual, "process() should return false (not exempt) for " + path);
            assertFalse(chain.called, "chain.doFilter must NOT be invoked when not exempt for " + path);
        }
    }

    private boolean runNative(String path) throws Exception {
        RecordingChain chain = new RecordingChain();
        boolean r = new NativeLoginCrumbExclusion().process(reqWithPath(path), null, chain);
        assertExemptConsistency(r, chain, path);
        return r;
    }

    private boolean runProvisioning(String path) throws Exception {
        RecordingChain chain = new RecordingChain();
        boolean r = new ProvisioningCrumbExclusion().process(reqWithPath(path), null, chain);
        assertExemptConsistency(r, chain, path);
        return r;
    }

    /** The two observable signals (return value and chain invocation) must always agree. */
    private void assertExemptConsistency(boolean returned, RecordingChain chain, String path) {
        assertExempt(returned, returned, chain, path);
    }

    // ── NativeLoginCrumbExclusion: ONLY /omniauth/nativeLogin is exempt ───────

    @Test public void native_nativeLoginPath_isExempt() throws Exception {
        assertTrue(runNative("/omniauth/nativeLogin"));
    }

    @Test public void native_submitPasswordChangePath_isNotExempt() throws Exception {
        // Critical: the set-password POST must stay crumb-protected.
        assertFalse(runNative("/omniauth/submitPasswordChange"));
    }

    @Test public void native_changePasswordPath_isNotExempt() throws Exception {
        assertFalse(runNative("/omniauth/changePassword"));
    }

    @Test public void native_finishLoginPath_isNotExempt() throws Exception {
        assertFalse(runNative("/omniauth/finishLogin"));
    }

    @Test public void native_nullPath_isNotExempt() throws Exception {
        assertFalse(runNative(null));
    }

    @Test public void native_provisioningPath_isNotExempt() throws Exception {
        assertFalse(runNative("/request-access/submitNative"));
    }

    @Test public void native_prefixLookalikePath_isNotExempt() throws Exception {
        // Must be an exact match, not a prefix — a trailing segment must not sneak through.
        assertFalse(runNative("/omniauth/nativeLogin/evil"));
    }

    // ── ProvisioningCrumbExclusion: ONLY the two request-access POSTs ─────────

    @Test public void provisioning_submitNativePath_isExempt() throws Exception {
        assertTrue(runProvisioning("/request-access/submitNative"));
    }

    @Test public void provisioning_submitEntraReasonPath_isExempt() throws Exception {
        assertTrue(runProvisioning("/request-access/submitEntraReason"));
    }

    @Test public void provisioning_otherRequestAccessPath_isNotExempt() throws Exception {
        assertFalse(runProvisioning("/request-access/reason"));
    }

    @Test public void provisioning_nativeLoginPath_isNotExempt() throws Exception {
        assertFalse(runProvisioning("/omniauth/nativeLogin"));
    }

    @Test public void provisioning_nullPath_isNotExempt() throws Exception {
        assertFalse(runProvisioning(null));
    }
}
