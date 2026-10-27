package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import org.jboss.aerogear.security.otp.Totp;
import org.jboss.aerogear.security.otp.api.Base32;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OmniAuthUserProperty}: Break-Glass TOTP verification + secret-at-rest,
 * the 3-device enrollment cap, and the Entra identity / last-login fields.
 *
 * Uses JenkinsRule because proving the TOTP secret is encrypted at rest (hudson.util.Secret)
 * requires a live ConfidentialStore. The TOTP code is generated in-test with the SAME library
 * the production code uses (org.jboss.aerogear) from the enrolled secret.
 */
@WithJenkins
public class OmniAuthUserPropertyTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    private OmniAuthUserProperty newProp() {
        return new OmniAuthUserProperty("oid-123", "user@example.com");
    }

    // ── identity fields ───────────────────────────────────────────────────────

    @Test
    public void constructor_storesEntraObjectIdAndUpn() {
        OmniAuthUserProperty p = newProp();
        assertEquals("oid-123", p.getEntraObjectId());
        assertEquals("user@example.com", p.getEntraUpn());
    }

    @Test
    public void hasEntraIdentity_trueWhenObjectIdPresent() {
        assertTrue(newProp().hasEntraIdentity());
    }

    @Test
    public void hasEntraIdentity_falseForNullOrEmptyObjectId() {
        assertFalse(new OmniAuthUserProperty(null, "u@x").hasEntraIdentity());
        assertFalse(new OmniAuthUserProperty("", "u@x").hasEntraIdentity());
    }

    @Test
    public void lastLoginAt_getterSetterRoundTrip() {
        OmniAuthUserProperty p = newProp();
        assertNull(p.getLastLoginAt());
        p.setLastLoginAt("2026-10-16T10:00:00Z");
        assertEquals("2026-10-16T10:00:00Z", p.getLastLoginAt());
    }

    @Test
    public void provisioningSource_defaultsToIndividual() {
        assertEquals("INDIVIDUAL", newProp().getProvisioningSource());
        assertFalse(newProp().isViaGroup());
    }

    @Test
    public void provisioningSource_viaGroupReflectedByIsViaGroup() {
        OmniAuthUserProperty p = newProp();
        p.setProvisioningSource("VIA_ENTRA_GROUP");
        assertEquals("VIA_ENTRA_GROUP", p.getProvisioningSource());
        assertTrue(p.isViaGroup());
    }

    @Test
    public void resolveLastLogin_prefersEntraPropertyOverNative() {
        OmniAuthUserProperty entra = newProp();
        entra.setLastLoginAt("2026-01-02T00:00:00Z");
        LastLoginProperty native_ = new LastLoginProperty("2026-01-01T00:00:00Z");

        assertEquals("2026-01-02T00:00:00Z",
                OmniAuthUserProperty.resolveLastLogin(entra, native_));
    }

    @Test
    public void resolveLastLogin_fallsBackToNativeWhenEntraMissing() {
        LastLoginProperty native_ = new LastLoginProperty("2026-01-01T00:00:00Z");
        assertEquals("2026-01-01T00:00:00Z",
                OmniAuthUserProperty.resolveLastLogin(null, native_));
        // Entra prop present but with no timestamp also falls back.
        assertEquals("2026-01-01T00:00:00Z",
                OmniAuthUserProperty.resolveLastLogin(newProp(), native_));
    }

    @Test
    public void resolveLastLogin_nullWhenNeitherHasLogin() {
        assertNull(OmniAuthUserProperty.resolveLastLogin(null, null));
        assertNull(OmniAuthUserProperty.resolveLastLogin(newProp(), new LastLoginProperty(null)));
    }

    // ── Break-Glass enrollment + secret-at-rest ───────────────────────────────

    @Test
    public void breakGlass_notEnrolledByDefault() {
        OmniAuthUserProperty p = newProp();
        assertFalse(p.isBreakGlassTotpEnrolled());
        assertTrue(p.getBreakGlassDevices().isEmpty());
    }

    @Test
    public void addBreakGlassDevice_marksEnrolled() {
        OmniAuthUserProperty p = newProp();
        assertTrue(p.addBreakGlassDevice("Phone", Base32.random()));
        assertTrue(p.isBreakGlassTotpEnrolled());
        assertEquals(1, p.getBreakGlassDevices().size());
        assertEquals("Phone", p.getBreakGlassDevices().get(0).getName());
    }

    @Test
    public void breakGlassSecret_isStoredEncryptedAtRestAsHudsonSecret() throws Exception {
        String plaintext = Base32.random();
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("Phone", plaintext);

        OmniAuthUserProperty.BreakGlassDevice d = p.getBreakGlassDevices().get(0);

        // The private backing field must be a hudson.util.Secret, not a raw String.
        Field f = OmniAuthUserProperty.BreakGlassDevice.class.getDeclaredField("secret");
        f.setAccessible(true);
        Object stored = f.get(d);
        assertTrue(stored instanceof Secret, "TOTP secret must be stored as hudson.util.Secret");

        Secret secret = (Secret) stored;
        String encrypted = secret.getEncryptedValue();
        assertNotEquals(plaintext, encrypted, "Encrypted-at-rest form must differ from the plaintext secret");
        assertEquals(plaintext, Secret.decrypt(encrypted).getPlainText(), "Secret must round-trip back to the original plaintext");

        // In-process accessor returns the decrypted plaintext for TOTP verification.
        assertEquals(plaintext, d.getSecret());
    }

    @Test
    public void addBreakGlassDevice_cappedAtThreeDevices() {
        OmniAuthUserProperty p = newProp();
        assertTrue(p.addBreakGlassDevice("d1", Base32.random()));
        assertTrue(p.addBreakGlassDevice("d2", Base32.random()));
        assertTrue(p.addBreakGlassDevice("d3", Base32.random()));
        assertFalse(p.addBreakGlassDevice("d4", Base32.random()), "A 4th device must be refused");
        assertEquals(3, p.getBreakGlassDevices().size());
    }

    @Test
    public void removeBreakGlassDevice_removesById() {
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("d1", Base32.random());
        p.addBreakGlassDevice("d2", Base32.random());
        String idToRemove = p.getBreakGlassDevices().get(0).getId();

        assertTrue(p.removeBreakGlassDevice(idToRemove));
        assertEquals(1, p.getBreakGlassDevices().size());
        assertFalse(p.removeBreakGlassDevice("no-such-id"), "Removing an unknown id returns false");
    }

    @Test
    public void removeBreakGlassDevice_lastDeviceResetsEnrollSkips() {
        OmniAuthUserProperty p = newProp();
        p.setBreakGlassEnrollSkips(2);
        p.addBreakGlassDevice("only", Base32.random());
        String id = p.getBreakGlassDevices().get(0).getId();

        p.removeBreakGlassDevice(id);
        assertTrue(p.getBreakGlassDevices().isEmpty());
        assertEquals(0, p.getBreakGlassEnrollSkips(), "Removing the last device resets the skip counter");
    }

    @Test
    public void getBreakGlassDevices_returnsUnmodifiableList() {
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("d1", Base32.random());
        try {
            p.getBreakGlassDevices().add(new OmniAuthUserProperty.BreakGlassDevice("x", Base32.random()));
            fail("Returned device list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    // ── TOTP verification correctness ─────────────────────────────────────────

    @Test
    public void verifyBreakGlassCode_acceptsValidCurrentCode() {
        String secret = Base32.random();
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("Phone", secret);

        String currentCode = new Totp(secret).now();
        assertTrue(p.verifyBreakGlassCode(currentCode), "The current TOTP code computed from the enrolled secret must verify");
    }

    @Test
    public void verifyBreakGlassCode_rejectsNonNumericCode() {
        String secret = Base32.random();
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("Phone", secret);

        assertFalse(p.verifyBreakGlassCode("abcdef"), "A malformed (non-numeric) code must be rejected, not throw");
    }

    @Test
    public void verifyBreakGlassCode_rejectsCodeForADifferentSecret() {
        String enrolledSecret = Base32.random();
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("Phone", enrolledSecret);

        // A valid TOTP code, but generated from an unrelated secret → must not verify.
        String foreignCode = new Totp(Base32.random()).now();
        // Guard the astronomically-unlikely collision with the enrolled secret's window.
        if (p.verifyBreakGlassCode(foreignCode)) {
            foreignCode = new Totp(Base32.random()).now();
        }
        assertFalse(p.verifyBreakGlassCode(foreignCode), "A code from a different secret must be rejected");
    }

    @Test
    public void verifyBreakGlassCode_withNoDevicesEnrolled_returnsFalse() {
        assertFalse(newProp().verifyBreakGlassCode("123456"));
    }

    @Test
    public void verifyBreakGlassCode_acceptsCodeForAnySecondDevice() {
        OmniAuthUserProperty p = newProp();
        p.addBreakGlassDevice("Phone", Base32.random());
        String secondSecret = Base32.random();
        p.addBreakGlassDevice("Tablet", secondSecret);

        // verifyBreakGlassCode iterates all enrolled devices, so the second device's code works.
        assertTrue(p.verifyBreakGlassCode(new Totp(secondSecret).now()));
    }

    // ── migration: legacy single-secret → device list ────────────────────────

    @Test
    public void legacySingleSecret_isMigratedIntoDeviceListOnFirstRead() throws Exception {
        OmniAuthUserProperty p = newProp();
        // Simulate a pre-upgrade record that only had the deprecated single secret field.
        String legacySecret = Base32.random();
        Field f = OmniAuthUserProperty.class.getDeclaredField("breakGlassTotpSecret");
        f.setAccessible(true);
        f.set(p, legacySecret);

        List<OmniAuthUserProperty.BreakGlassDevice> devices = p.getBreakGlassDevices();
        assertEquals(1, devices.size(), "Legacy secret must migrate to exactly one device");
        assertEquals(legacySecret, devices.get(0).getSecret());
        // Migrated secret still verifies via TOTP.
        assertTrue(p.verifyBreakGlassCode(new Totp(legacySecret).now()));
    }
}
