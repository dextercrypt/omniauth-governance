package io.jenkins.plugins.omniauth;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.mockito.MockedStatic;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * Tests the pure helpers of OmniAuthManagementLink:
 *   - escapeJson / jsonEsc       — JSON string escaping (invoked by reflection, private static)
 *   - buildPreviewHtml           — the reflected-XSS fix: a caller-supplied logo URL must land
 *                                  HTML-escaped inside the single-quoted src attribute
 *   - isBreakGlassActive /
 *     getBreakGlassExpiresAt     — the Long-vs-String fix: the session "expiry" attribute is a
 *                                  Long epoch-millis and reading it must not throw / must compare
 *                                  numerically against System.currentTimeMillis()
 *
 * The doXxx Stapler handlers (ADMINISTER + request + crumb) are intentionally not tested here.
 */
@WithJenkins
public class OmniAuthManagementLinkHelpersTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    // ─── reflection helpers ─────────────────────────────────────────────────

    private static String escapeJson(String s) throws Exception {
        Method m = OmniAuthManagementLink.class.getDeclaredMethod("escapeJson", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static String jsonEsc(String s) throws Exception {
        Method m = OmniAuthManagementLink.class.getDeclaredMethod("jsonEsc", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static String buildPreviewHtml(OmniAuthGlobalConfig cfg, String eventType,
                                           String logoOverride, String footerNote) throws Exception {
        Method m = OmniAuthManagementLink.class.getDeclaredMethod(
                "buildPreviewHtml", OmniAuthGlobalConfig.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, cfg, eventType, logoOverride, footerNote);
    }

    // ─── escapeJson ──────────────────────────────────────────────────────────

    @Test
    public void escapeJson_escapesQuoteBackslashAndNewline() throws Exception {
        assertEquals("he said \\\"hi\\\"", escapeJson("he said \"hi\""));
        assertEquals("a\\\\b", escapeJson("a\\b"));
        assertEquals("line1\\nline2", escapeJson("line1\nline2"));
    }

    @Test
    public void escapeJson_stripsCarriageReturn() throws Exception {
        assertEquals("ab", escapeJson("a\rb"));
    }

    // ─── jsonEsc ────────────────────────────────────────────────────────────

    @Test
    public void jsonEsc_nullReturnsEmptyString() throws Exception {
        assertEquals("", jsonEsc(null));
    }

    @Test
    public void jsonEsc_escapesQuoteBackslashNewline() throws Exception {
        assertEquals("a\\\"b", jsonEsc("a\"b"));
        assertEquals("a\\\\b", jsonEsc("a\\b"));
        assertEquals("a\\nb", jsonEsc("a\nb"));
        assertEquals("ab", jsonEsc("a\rb"));
    }

    // ─── buildPreviewHtml — reflected XSS escaping of the logo URL ────────────

    @Test
    public void buildPreviewHtml_logoUrlWithSingleQuote_isEscaped_cannotBreakOutOfSrc() throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        String payload = "x' onerror='alert(1)";
        String html = buildPreviewHtml(cfg, "configChanged", payload, null);

        assertFalse(html.contains("src='x' onerror='alert(1)'"), "the raw single quote must not break out of the src attribute");
        assertTrue(html.contains("src='x&#39; onerror=&#39;alert(1)'"), "the single quote must be HTML-entity-escaped");
    }

    @Test
    public void buildPreviewHtml_logoUrlWithAngleBrackets_isEscaped() throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        String html = buildPreviewHtml(cfg, "configChanged", "<img x>", null);

        assertFalse(html.contains("src='<img x>'"), "raw angle brackets must not appear in the logo src");
        assertTrue(html.contains("src='&lt;img x&gt;'"), "angle brackets must be escaped");
    }

    @Test
    public void buildPreviewHtml_logoUrlWithDoubleQuote_isEscaped() throws Exception {
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        String html = buildPreviewHtml(cfg, "configChanged", "a\"b", null);
        assertTrue(html.contains("src='a&quot;b'"), "double quote must be escaped");
    }

    // ─── break-glass session state (Long expiry) ──────────────────────────────

    /** Proxy a jakarta HttpSession that returns {@code expiry} for the break-glass attribute. */
    private static jakarta.servlet.http.HttpSession fakeSession(Object expiry) {
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
        return (jakarta.servlet.http.HttpSession) Proxy.newProxyInstance(
                OmniAuthManagementLinkHelpersTest.class.getClassLoader(),
                new Class<?>[]{ jakarta.servlet.http.HttpSession.class }, h);
    }

    /** Proxy a StaplerRequest2 whose getSession(false) returns the given session. */
    private static StaplerRequest2 fakeRequest(jakarta.servlet.http.HttpSession session) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("getSession".equals(method.getName())) return session;
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == long.class) return 0;
            return null;
        };
        return (StaplerRequest2) Proxy.newProxyInstance(
                OmniAuthManagementLinkHelpersTest.class.getClassLoader(),
                new Class<?>[]{ StaplerRequest2.class }, h);
    }

    @Test
    public void isBreakGlassActive_futureLongExpiry_returnsTrue() {
        OmniAuthManagementLink link = new OmniAuthManagementLink();
        long future = System.currentTimeMillis() + 600_000L;
        StaplerRequest2 req = fakeRequest(fakeSession(Long.valueOf(future)));

        try (MockedStatic<Stapler> st = mockStatic(Stapler.class)) {
            st.when(Stapler::getCurrentRequest2).thenReturn(req);
            // Regression: before the fix this cast the Long to String → ClassCastException
            // (swallowed) → always reported inactive.
            assertTrue(link.isBreakGlassActive(), "a future Long expiry must report break-glass active");
        }
    }

    @Test
    public void isBreakGlassActive_pastLongExpiry_returnsFalse() {
        OmniAuthManagementLink link = new OmniAuthManagementLink();
        long past = System.currentTimeMillis() - 600_000L;
        StaplerRequest2 req = fakeRequest(fakeSession(Long.valueOf(past)));

        try (MockedStatic<Stapler> st = mockStatic(Stapler.class)) {
            st.when(Stapler::getCurrentRequest2).thenReturn(req);
            assertFalse(link.isBreakGlassActive(), "a past Long expiry must report break-glass inactive");
        }
    }

    @Test
    public void isBreakGlassActive_noExpiryAttribute_returnsFalse() {
        OmniAuthManagementLink link = new OmniAuthManagementLink();
        StaplerRequest2 req = fakeRequest(fakeSession(null));

        try (MockedStatic<Stapler> st = mockStatic(Stapler.class)) {
            st.when(Stapler::getCurrentRequest2).thenReturn(req);
            assertFalse(link.isBreakGlassActive());
        }
    }

    @Test
    public void isBreakGlassActive_noCurrentRequest_returnsFalse() {
        OmniAuthManagementLink link = new OmniAuthManagementLink();
        try (MockedStatic<Stapler> st = mockStatic(Stapler.class)) {
            st.when(Stapler::getCurrentRequest2).thenReturn(null);
            assertFalse(link.isBreakGlassActive());
        }
    }

    @Test
    public void getBreakGlassExpiresAt_futureLong_returnsIsoInstant() {
        OmniAuthManagementLink link = new OmniAuthManagementLink();
        long future = System.currentTimeMillis() + 600_000L;
        StaplerRequest2 req = fakeRequest(fakeSession(Long.valueOf(future)));

        try (MockedStatic<Stapler> st = mockStatic(Stapler.class)) {
            st.when(Stapler::getCurrentRequest2).thenReturn(req);
            String iso = link.getBreakGlassExpiresAt();
            assertNotNull(iso, "a Long expiry must be read without throwing");
            assertEquals(java.time.Instant.ofEpochMilli(future).toString(), iso, "must equal the ISO form of the stored epoch-millis");
        }
    }

    @Test
    public void getBreakGlassExpiresAt_noExpiry_returnsNull() {
        OmniAuthManagementLink link = new OmniAuthManagementLink();
        StaplerRequest2 req = fakeRequest(fakeSession(null));

        try (MockedStatic<Stapler> st = mockStatic(Stapler.class)) {
            st.when(Stapler::getCurrentRequest2).thenReturn(req);
            assertNull(link.getBreakGlassExpiresAt());
        }
    }
}
