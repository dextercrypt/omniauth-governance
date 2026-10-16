package io.jenkins.plugins.omniauth;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Config save-safety + getter-clamping tests against a REAL {@link OmniAuthGlobalConfig}
 * via JenkinsRule (the class is a heavy GlobalConfiguration that cannot be Mockito-mocked
 * on this JVM).
 *
 * The central invariant (the "save footgun"): a single-field change must not reset unrelated
 * fields. {@link OmniAuthGlobalConfig#setProtectedUsers} is the dedicated setter that bypasses
 * {@link OmniAuthGlobalConfig#configure}; configure() is the FULL-form handler and deliberately
 * re-reads every key it owns (resetting absent ones to defaults), which is exactly why the
 * dedicated setter exists.
 */
public class OmniAuthGlobalConfigTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthGlobalConfig cfg() { return OmniAuthGlobalConfig.get(); }

    private static void setIntField(OmniAuthGlobalConfig c, String name, int val) throws Exception {
        Field f = OmniAuthGlobalConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(c, val);
    }

    // ── setProtectedUsers must touch ONLY the protected list ──────────────────

    @Test
    public void setProtectedUsers_changesList_andPreservesUnrelatedFields() {
        OmniAuthGlobalConfig c = cfg();

        // Prime a spread of unrelated settings across different field groups.
        c.setProvisioningEnabled(true);
        c.setBruteForceLockoutMinutes(42);
        c.setJitHistoryRetentionDays(55);
        c.setLoginHeading("Welcome Team");
        c.setProvisioningBreakerMax(250);

        c.setProtectedUsers(Arrays.asList("admin", "root"));

        // The list changed...
        assertTrue(c.getProtectedUsers().contains("admin"));
        assertTrue(c.getProtectedUsers().contains("root"));
        assertEquals(2, c.getProtectedUsers().size());

        // ...and nothing else was reset (would have been, had this routed through configure()).
        assertTrue("provisioningEnabled must survive", c.isProvisioningEnabled());
        assertEquals("bruteForceLockoutMinutes must survive", 42, c.getBruteForceLockoutMinutes());
        assertEquals("jitHistoryRetentionDays must survive", 55, c.getJitHistoryRetentionDays());
        assertEquals("loginHeading must survive", "Welcome Team", c.getLoginHeading());
        assertEquals("provisioningBreakerMax must survive", 250, c.getProvisioningBreakerMax());
    }

    @Test
    public void setProtectedUsers_null_yieldsEmptyList_notCrash() {
        OmniAuthGlobalConfig c = cfg();
        c.setProtectedUsers(null);
        assertTrue(c.getProtectedUsers().isEmpty());
    }

    @Test
    public void getProtectedUsers_returnsUnmodifiableSnapshot() {
        OmniAuthGlobalConfig c = cfg();
        c.setProtectedUsers(Arrays.asList("a"));
        List<String> view = c.getProtectedUsers();
        try {
            view.add("b");
            org.junit.Assert.fail("protected-users view should be unmodifiable");
        } catch (UnsupportedOperationException expected) { /* ok */ }
    }

    // ── configure() SCOPE: provisioning fields are NOT reset by the form handler ─

    @Test
    public void configure_doesNotResetProvisioningFields_itNeverReadsThem() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        c.setProvisioningEnabled(true);
        c.setProvisioningAllowNative(true);
        c.setProvisioningBreakerMax(321);
        c.setActivationLinkTtlHours(96);

        // A form submit that omits every provisioning key entirely.
        c.configure((org.kohsuke.stapler.StaplerRequest) null, new JSONObject());

        assertTrue("provisioningEnabled is outside configure()'s scope", c.isProvisioningEnabled());
        assertTrue("provisioningAllowNative is outside configure()'s scope", c.isProvisioningAllowNative());
        assertEquals("provisioningBreakerMax is outside configure()'s scope", 321, c.getProvisioningBreakerMax());
        assertEquals("activationLinkTtlHours is outside configure()'s scope", 96, c.getActivationLinkTtlHours());
    }

    @Test
    public void configure_roundTripsProtectedUsers_fromJsonArray() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        JSONObject json = new JSONObject();
        JSONArray arr = new JSONArray();
        arr.add("alice");
        arr.add("bob");
        json.put("protectedUsers", arr);

        c.configure((org.kohsuke.stapler.StaplerRequest) null, json);

        assertEquals(2, c.getProtectedUsers().size());
        assertTrue(c.getProtectedUsers().contains("alice"));
        assertTrue(c.getProtectedUsers().contains("bob"));
    }

    @Test
    public void configure_roundTripsProtectedUsers_fromSingleString() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        JSONObject json = new JSONObject();
        json.put("protectedUsers", "solo");

        c.configure((org.kohsuke.stapler.StaplerRequest) null, json);

        assertEquals(1, c.getProtectedUsers().size());
        assertTrue(c.getProtectedUsers().contains("solo"));
    }

    @Test
    public void configure_absentManagedField_resetsToDefault_documentsFootgun() throws Exception {
        OmniAuthGlobalConfig c = cfg();

        // First submit sets a non-default threshold (there is no public setter for it).
        JSONObject first = new JSONObject();
        first.put("staleThresholdDays", 120);
        c.configure((org.kohsuke.stapler.StaplerRequest) null, first);
        assertEquals(120, c.getStaleThresholdDays());

        // A later submit that omits the key resets it to the default — this is why a
        // subset-of-fields save must use a dedicated setter rather than configure().
        c.configure((org.kohsuke.stapler.StaplerRequest) null, new JSONObject());
        assertEquals("absent managed field reverts to default", 90, c.getStaleThresholdDays());
    }

    @Test
    public void configure_readsBruteForceAndSmtpFields() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        JSONObject json = new JSONObject();
        json.put("bruteForceThreshold", 9);
        json.put("bruteForceLockoutMinutes", 25);
        json.put("smtpHost", "mail.example.com");
        json.put("smtpPort", 2525);

        c.configure((org.kohsuke.stapler.StaplerRequest) null, json);

        assertEquals(9, c.getBruteForceThreshold());
        assertEquals(25, c.getBruteForceLockoutMinutes());
        assertEquals("mail.example.com", c.getSmtpHost());
        assertEquals(2525, c.getSmtpPort());
    }

    @Test
    public void configure_splitsHostColonPort() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        JSONObject json = new JSONObject();
        json.put("smtpHost", "smtp.example.com:465");
        c.configure((org.kohsuke.stapler.StaplerRequest) null, json);
        assertEquals("smtp.example.com", c.getSmtpHost());
        assertEquals(465, c.getSmtpPort());
    }

    // ── Getter clamping / defaults (robust against legacy XML values) ─────────
    // Poke the raw field below the floor via reflection, then assert the getter clamps.

    @Test
    public void getProvisioningMaxPendingPerUser_floorsToOne() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "provisioningMaxPendingPerUser", 0);
        assertEquals(1, c.getProvisioningMaxPendingPerUser());
        setIntField(c, "provisioningMaxPendingPerUser", -5);
        assertEquals(1, c.getProvisioningMaxPendingPerUser());
    }

    @Test
    public void getProvisioningBreakerMax_defaultsTo100WhenNonPositive() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "provisioningBreakerMax", 0);
        assertEquals(100, c.getProvisioningBreakerMax());
    }

    @Test
    public void getProvisioningBreakerWindowMin_defaultsTo10WhenNonPositive() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "provisioningBreakerWindowMin", 0);
        assertEquals(10, c.getProvisioningBreakerWindowMin());
    }

    @Test
    public void getProvisioningBreakerCooldownMin_defaultsTo30WhenNonPositive() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "provisioningBreakerCooldownMin", -1);
        assertEquals(30, c.getProvisioningBreakerCooldownMin());
    }

    @Test
    public void getBruteForceLockoutMinutes_defaultsTo15WhenNonPositive() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "bruteForceLockoutMinutes", 0);
        assertEquals(15, c.getBruteForceLockoutMinutes());
    }

    @Test
    public void getJitHistoryRetentionDays_defaultsTo90WhenNonPositive() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "jitHistoryRetentionDays", 0);
        assertEquals(90, c.getJitHistoryRetentionDays());
    }

    @Test
    public void getActivationLinkTtlHours_defaultsTo72WhenNonPositive() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "activationLinkTtlHours", 0);
        assertEquals(72, c.getActivationLinkTtlHours());
    }

    @Test
    public void getProvisioningDefaultDurationDays_flooredToZero() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "provisioningDefaultDurationDays", -5);
        assertEquals(0, c.getProvisioningDefaultDurationDays());
    }

    @Test
    public void getProvisioningExpiryReminderDays_flooredToZero() throws Exception {
        OmniAuthGlobalConfig c = cfg();
        setIntField(c, "provisioningExpiryReminderDays", -3);
        assertEquals(0, c.getProvisioningExpiryReminderDays());
    }

    // ── Round-trip setters keep valid values ─────────────────────────────────

    @Test
    public void setters_roundTripValidValues() {
        OmniAuthGlobalConfig c = cfg();
        c.setProvisioningMaxPendingPerUser(3);
        c.setProvisioningBreakerMax(500);
        c.setProvisioningBreakerWindowMin(20);
        c.setProvisioningBreakerCooldownMin(45);
        c.setBruteForceLockoutMinutes(30);
        c.setJitHistoryRetentionDays(120);
        c.setActivationLinkTtlHours(168);
        c.setProvisioningDefaultDurationDays(14);
        c.setProvisioningExpiryReminderDays(5);

        assertEquals(3,   c.getProvisioningMaxPendingPerUser());
        assertEquals(500, c.getProvisioningBreakerMax());
        assertEquals(20,  c.getProvisioningBreakerWindowMin());
        assertEquals(45,  c.getProvisioningBreakerCooldownMin());
        assertEquals(30,  c.getBruteForceLockoutMinutes());
        assertEquals(120, c.getJitHistoryRetentionDays());
        assertEquals(168, c.getActivationLinkTtlHours());
        assertEquals(14,  c.getProvisioningDefaultDurationDays());
        assertEquals(5,   c.getProvisioningExpiryReminderDays());
    }

    @Test
    public void setProvisioningExpiryAction_normalisesToDisableOrDelete() {
        OmniAuthGlobalConfig c = cfg();
        assertEquals("default is DISABLE", "DISABLE", c.getProvisioningExpiryAction());
        c.setProvisioningExpiryAction("delete");
        assertEquals("DELETE", c.getProvisioningExpiryAction());
        c.setProvisioningExpiryAction("garbage");
        assertEquals("unknown value normalises to DISABLE", "DISABLE", c.getProvisioningExpiryAction());
    }

    @Test
    public void isProvisioningUsable_requiresEnabledPlusAtLeastOneChannel() {
        OmniAuthGlobalConfig c = cfg();
        c.setProvisioningEnabled(false);
        c.setProvisioningAllowEntra(true);
        assertFalse("disabled portal is never usable", c.isProvisioningUsable());

        c.setProvisioningEnabled(true);
        c.setProvisioningAllowEntra(false);
        c.setProvisioningAllowNative(false);
        assertFalse("enabled but no channel is not usable", c.isProvisioningUsable());

        c.setProvisioningAllowNative(true);
        assertTrue("enabled + a channel is usable", c.isProvisioningUsable());
    }
}
