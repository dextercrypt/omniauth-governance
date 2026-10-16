package io.jenkins.plugins.omniauth;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for the private/package-private static helpers on
 * {@link ProvisioningPortalAction}: {@code domainAllowed}, {@code parseDays}, {@code cap}.
 *
 * <p>No Jenkins is required — these helpers are static pure logic. {@code domainAllowed} is
 * package-private so it is called directly; {@code parseDays}/{@code cap} are private and are
 * invoked via reflection (the cleanest way to exercise them without touching production code).
 */
public class ProvisioningPortalActionHelpersTest {

    private static int parseDays(String v, int fallback) throws Exception {
        Method m = ProvisioningPortalAction.class.getDeclaredMethod("parseDays", String.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, v, fallback);
    }

    private static String cap(String s, int max) throws Exception {
        Method m = ProvisioningPortalAction.class.getDeclaredMethod("cap", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s, max);
    }

    // ── domainAllowed ─────────────────────────────────────────────────────────

    @Test
    public void domainAllowed_blankAllowlistAllowsAll() {
        assertTrue(ProvisioningPortalAction.domainAllowed("anyone@whatever.com", ""));
        assertTrue(ProvisioningPortalAction.domainAllowed("anyone@whatever.com", "   "));
        assertTrue(ProvisioningPortalAction.domainAllowed("anyone@whatever.com", null));
    }

    @Test
    public void domainAllowed_matchesListedDomain() {
        assertTrue(ProvisioningPortalAction.domainAllowed("bob@example.com", "example.com"));
    }

    @Test
    public void domainAllowed_matchesWithLeadingAtPrefix() {
        assertTrue(ProvisioningPortalAction.domainAllowed("bob@example.com", "@example.com"));
    }

    @Test
    public void domainAllowed_caseInsensitive() {
        assertTrue(ProvisioningPortalAction.domainAllowed("Bob@Example.COM", "example.com"));
        assertTrue(ProvisioningPortalAction.domainAllowed("bob@example.com", "EXAMPLE.COM"));
    }

    @Test
    public void domainAllowed_commaAndSpaceSeparatedList() {
        assertTrue(ProvisioningPortalAction.domainAllowed("bob@b.org", "a.com, b.org"));
        assertTrue(ProvisioningPortalAction.domainAllowed("bob@b.org", "a.com b.org"));
        assertTrue(ProvisioningPortalAction.domainAllowed("bob@b.org", "@a.com, @b.org"));
    }

    @Test
    public void domainAllowed_notInListRejected() {
        assertFalse(ProvisioningPortalAction.domainAllowed("bob@evil.com", "example.com, b.org"));
    }

    @Test
    public void domainAllowed_malformedNoAtRejected() {
        assertFalse(ProvisioningPortalAction.domainAllowed("not-an-email", "example.com"));
    }

    @Test
    public void domainAllowed_trailingAtRejected() {
        assertFalse(ProvisioningPortalAction.domainAllowed("bob@", "example.com"));
    }

    @Test
    public void domainAllowed_usesLastAtForDomain() {
        // Last '@' delimits the domain; "weird@sub@example.com" resolves to example.com.
        assertTrue(ProvisioningPortalAction.domainAllowed("weird@sub@example.com", "example.com"));
        assertFalse(ProvisioningPortalAction.domainAllowed("weird@sub@example.com", "sub"));
    }

    // ── parseDays ─────────────────────────────────────────────────────────────

    @Test
    public void parseDays_blankOrNullUsesFallback() throws Exception {
        assertEquals(7, parseDays(null, 7));
        assertEquals(7, parseDays("", 7));
        assertEquals(7, parseDays("   ", 7));
    }

    @Test
    public void parseDays_negativeFallbackFlooredToZero() throws Exception {
        assertEquals(0, parseDays(null, -5));
    }

    @Test
    public void parseDays_validInteger() throws Exception {
        assertEquals(30, parseDays("30", 7));
        assertEquals(0, parseDays("0", 7));
    }

    @Test
    public void parseDays_negativeValueFlooredToZero() throws Exception {
        assertEquals(0, parseDays("-10", 7));
    }

    @Test
    public void parseDays_nonNumericUsesFallback() throws Exception {
        assertEquals(7, parseDays("abc", 7));
        assertEquals(7, parseDays("12x", 7));
    }

    @Test
    public void parseDays_hugeValueClampedToMax_regression() throws Exception {
        // Regression: a public-form "999999" must not become the account expiry; clamp to 3650.
        assertEquals(3650, parseDays("999999", 7));
    }

    @Test
    public void parseDays_justUnderMaxUnchanged() throws Exception {
        assertEquals(3649, parseDays("3649", 7));
    }

    @Test
    public void parseDays_exactlyMaxUnchanged() throws Exception {
        assertEquals(3650, parseDays("3650", 7));
    }

    @Test
    public void parseDays_justOverMaxClamped() throws Exception {
        assertEquals(3650, parseDays("3651", 7));
    }

    @Test
    public void parseDays_fallbackAlsoClamped() throws Exception {
        // Even the fallback path is clamped (blank value, absurd fallback).
        assertEquals(3650, parseDays("", 999999));
    }

    // ── cap ───────────────────────────────────────────────────────────────────

    @Test
    public void cap_nullBecomesEmpty() throws Exception {
        assertEquals("", cap(null, 10));
    }

    @Test
    public void cap_shortStringUnchanged() throws Exception {
        assertEquals("hello", cap("hello", 10));
    }

    @Test
    public void cap_exactLengthUnchanged() throws Exception {
        assertEquals("hello", cap("hello", 5));
    }

    @Test
    public void cap_longStringTruncated() throws Exception {
        assertEquals("hel", cap("hello", 3));
    }

    @Test
    public void cap_zeroMaxTruncatesToEmpty() throws Exception {
        assertEquals("", cap("hello", 0));
    }
}
