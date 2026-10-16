package io.jenkins.plugins.omniauth;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Anti-open-redirect gate tests. Pure string logic — no Jenkins needed.
 *
 * Covers the two guards that decide whether a post-login {@code from=} / redirect
 * target may be followed:
 *   - {@link OmniAuthSecurityRealm}#isSafeRedirectUrl(String)  (private static, boolean)
 *   - {@link OmniAuthRootAction}#safeFrom(String, String)      (private static; returns the
 *     target when safe, otherwise falls back to {@code ctx + "/"})
 *
 * Both must accept ONLY a single-leading-slash relative path and reject protocol-relative
 * URLs, backslash tricks, absolute URLs, control chars, and null/blank.
 */
public class RedirectGuardTest {

    private static final String CTX = "/jenkins";
    private static final String FALLBACK = CTX + "/";

    // ── reflection accessors ────────────────────────────────────────────────

    private static boolean isSafe(String url) throws Exception {
        Method m = OmniAuthSecurityRealm.class.getDeclaredMethod("isSafeRedirectUrl", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, url);
    }

    private static String safeFrom(String from) throws Exception {
        Method m = OmniAuthRootAction.class.getDeclaredMethod("safeFrom", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, from, CTX);
    }

    /** Assert BOTH guards agree the target is accepted. */
    private void accept(String url) throws Exception {
        assertTrue("isSafeRedirectUrl should ACCEPT: " + url, isSafe(url));
        assertEquals("safeFrom should return the target unchanged for: " + url, url, safeFrom(url));
    }

    /** Assert BOTH guards agree the target is rejected. */
    private void reject(String url) throws Exception {
        assertFalse("isSafeRedirectUrl should REJECT: " + url, isSafe(url));
        assertEquals("safeFrom should fall back to ctx+'/' for: " + url, FALLBACK, safeFrom(url));
    }

    // ── ACCEPT: single-leading-slash relative paths ──────────────────────────

    @Test public void accepts_rootSlash()               throws Exception { accept("/"); }
    @Test public void accepts_simpleRelativePath()       throws Exception { accept("/jenkins/foo"); }
    @Test public void accepts_singleSegment()            throws Exception { accept("/job"); }
    @Test public void accepts_nestedPath()               throws Exception { accept("/view/all/builds"); }
    @Test public void accepts_pathWithQueryAmpersand()   throws Exception { accept("/search?q=a&r=b"); }
    @Test public void accepts_pathWithFragment()         throws Exception { accept("/configure#section"); }
    @Test public void accepts_pathWithEncodedChars()     throws Exception { accept("/job/My%20Job/"); }

    // ── REJECT: null / blank / no leading slash ──────────────────────────────

    @Test public void rejects_null()         throws Exception { reject(null); }
    @Test public void rejects_empty()        throws Exception { reject(""); }
    @Test public void rejects_noLeadingSlash() throws Exception { reject("foo/bar"); }
    @Test public void rejects_leadingSpace() throws Exception { reject(" /foo"); }

    // ── REJECT: protocol-relative ("//host") ─────────────────────────────────

    @Test public void rejects_protocolRelative()        throws Exception { reject("//evil.com"); }
    @Test public void rejects_protocolRelativeWithPath() throws Exception { reject("//evil.com/path"); }

    // ── REJECT: slash-backslash ("/\\host") and any backslash ────────────────

    @Test public void rejects_slashBackslashHost()  throws Exception { reject("/\\evil.com"); }
    @Test public void rejects_backslashInMiddle()   throws Exception { reject("/path\\evil"); }
    @Test public void rejects_leadingDoubleBackslash() throws Exception { reject("\\\\evil.com"); }
    @Test public void rejects_backslashFirstChar()  throws Exception { reject("\\evil"); }

    // ── REJECT: absolute URLs ────────────────────────────────────────────────

    @Test public void rejects_httpAbsolute()  throws Exception { reject("http://evil.com"); }
    @Test public void rejects_httpsAbsolute() throws Exception { reject("https://evil.com"); }

    // ── REJECT: control characters anywhere ──────────────────────────────────

    @Test public void rejects_tabControlChar()     throws Exception { reject("/foo\tbar"); }
    @Test public void rejects_newlineControlChar()  throws Exception { reject("/foo\nbar"); }
    @Test public void rejects_nullControlChar()     throws Exception { reject("/foo\u0000bar"); }
    @Test public void rejects_carriageReturnControlChar() throws Exception { reject("/foo\rbar"); }
}
