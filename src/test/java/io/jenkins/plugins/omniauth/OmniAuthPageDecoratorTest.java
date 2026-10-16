package io.jenkins.plugins.omniauth;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

/**
 * Tests the pure helpers and the break-glass gating of OmniAuthPageDecorator.
 *
 *   - htmlEsc                     — escapes & < > " ' (private static, reflection)
 *   - buildBreakGlassActiveScript — embeds the expiry epoch-millis into the injected script
 *   - resolveScript               — the Long-expiry gate: a FUTURE Long emits the active-override
 *                                   script, a PAST Long does not (the attr is a Long, read as Long)
 *
 * The three servlet filters' full doFilter paths need a real servlet pipeline and are not
 * unit-tested here; the gating decision (resolveScript) is the security-relevant logic.
 */
public class OmniAuthPageDecoratorTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String MATRIX_FILTER   = "io.jenkins.plugins.omniauth.OmniAuthPageDecorator$MatrixDisableFilter";
    private static final String APPROVER_FILTER = "io.jenkins.plugins.omniauth.OmniAuthPageDecorator$ApproverActionFilter";

    // ─── htmlEsc (ApproverActionFilter) ──────────────────────────────────────

    private static String htmlEsc(String s) throws Exception {
        Class<?> c = Class.forName(APPROVER_FILTER);
        Method m = c.getDeclaredMethod("htmlEsc", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    public void htmlEsc_escapesAllFiveSpecialChars() throws Exception {
        assertEquals("&lt;b&gt;&amp;&quot;&#39;", htmlEsc("<b>&\"'"));
    }

    @Test
    public void htmlEsc_null_returnsEmptyString() throws Exception {
        assertEquals("", htmlEsc(null));
    }

    @Test
    public void htmlEsc_plainText_isUnchanged() throws Exception {
        assertEquals("hello world", htmlEsc("hello world"));
    }

    // ─── buildBreakGlassActiveScript (MatrixDisableFilter) ───────────────────

    private static String buildBreakGlassActiveScript(long expiryMs) throws Exception {
        Class<?> c = Class.forName(MATRIX_FILTER);
        Method m = c.getDeclaredMethod("buildBreakGlassActiveScript", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, expiryMs);
    }

    @Test
    public void buildBreakGlassActiveScript_embedsExpiryAndBanner() throws Exception {
        long expiry = 1_900_000_000_000L;
        String script = buildBreakGlassActiveScript(expiry);

        assertTrue("script must embed the expiry epoch-millis as a JS literal",
                script.contains("var expiry=" + expiry + ";"));
        assertTrue("script must render the active-override banner",
                script.contains("Emergency Override Active"));
        assertTrue("script must be a <script> block", script.startsWith("<script>") && script.endsWith("</script>"));
    }

    // ─── resolveScript — Long-expiry gate (MatrixDisableFilter) ──────────────

    private static String resolveScript(HttpServletRequest req) throws Exception {
        Class<?> c = Class.forName(MATRIX_FILTER);
        Method m = c.getDeclaredMethod("resolveScript", HttpServletRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(null, req);
    }

    private static HttpSession fakeSession(Object expiry) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("getAttribute".equals(method.getName())
                    && "omniauth.breakGlass.expiry".equals(args[0])) {
                return expiry;
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == long.class) return 0;
            return null;
        };
        return (HttpSession) Proxy.newProxyInstance(
                OmniAuthPageDecoratorTest.class.getClassLoader(),
                new Class<?>[]{ HttpSession.class }, h);
    }

    private static HttpServletRequest fakeRequest(String uri, HttpSession session) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getRequestURI": return uri;
                case "getSession":    return session;  // getSession(false)
                default:
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class || rt == long.class) return 0;
                    return null;
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                OmniAuthPageDecoratorTest.class.getClassLoader(),
                new Class<?>[]{ HttpServletRequest.class }, h);
    }

    @Test
    public void resolveScript_futureLongExpiry_emitsActiveOverrideScript() throws Exception {
        j.jenkins.setAuthorizationStrategy(new OmniAuthAuthorizationStrategy());
        long future = System.currentTimeMillis() + 900_000L;
        HttpServletRequest req = fakeRequest("http://x/jenkins/configureSecurity", fakeSession(Long.valueOf(future)));

        String script = resolveScript(req);

        assertNotNull("an active break-glass session must produce a script", script);
        assertTrue("future Long expiry must emit the active-override script with the embedded expiry",
                script.contains("var expiry=" + future + ";"));
    }

    @Test
    public void resolveScript_pastLongExpiry_doesNotEmitActiveScript() throws Exception {
        j.jenkins.setAuthorizationStrategy(new OmniAuthAuthorizationStrategy());
        long past = System.currentTimeMillis() - 900_000L;
        HttpServletRequest req = fakeRequest("http://x/jenkins/configureSecurity", fakeSession(Long.valueOf(past)));

        String script = resolveScript(req);

        // Expired → falls through to the standard (not-enrolled) configure-security script,
        // which never carries the active-override expiry literal.
        assertNotNull(script);
        assertFalse("a past expiry must NOT emit the active-override script",
                script.contains("var expiry="));
        assertTrue("falls through to the not-enrolled configure-security script",
                script.contains("No Authenticator Device Enrolled"));
    }

    @Test
    public void resolveScript_nonSecurityPath_returnsNull() throws Exception {
        j.jenkins.setAuthorizationStrategy(new OmniAuthAuthorizationStrategy());
        HttpServletRequest req = fakeRequest("http://x/jenkins/somewhere/else", fakeSession(null));
        assertNull("only configure pages inject a script", resolveScript(req));
    }
}
