package io.jenkins.plugins.omniauth;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Pure unit tests for the one-time activation-link token ({@link OmniAuthActivationProperty}).
 *
 * Security focus: the raw token is never stored (only its SHA-256 hash), verification is
 * constant-time (MessageDigest.isEqual), the token is single-use (overwritten with an expired
 * marker once consumed), TTL/expiry is enforced, and the token carries 256 bits of entropy.
 *
 * No Jenkins is required — every method under test is pure.
 */
public class OmniAuthActivationPropertyTest {

    private static final long HOUR = 3_600_000L;

    private OmniAuthActivationProperty propFor(String rawToken, long expiresAtEpoch) {
        return new OmniAuthActivationProperty(OmniAuthActivationProperty.hash(rawToken), expiresAtEpoch);
    }

    // ── hash-at-rest: raw token is never stored ───────────────────────────────

    @Test
    public void storedTokenHash_isNotThePlaintextToken() {
        String raw = OmniAuthActivationProperty.newRawToken();
        OmniAuthActivationProperty p = propFor(raw, System.currentTimeMillis() + HOUR);

        assertNotEquals("Stored value must not be the raw token", raw, p.getTokenHash());
        assertEquals("Stored value must be exactly the SHA-256 hash of the raw token",
                OmniAuthActivationProperty.hash(raw), p.getTokenHash());
    }

    @Test
    public void hash_isSha256OfTokenAsBase64UrlWithoutPadding() throws Exception {
        String raw = "some-raw-activation-token";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        assertEquals("hash() must be SHA-256 then Base64url-no-padding", expected,
                OmniAuthActivationProperty.hash(raw));
    }

    @Test
    public void hash_isDeterministic_sameInputSameOutput() {
        assertEquals(OmniAuthActivationProperty.hash("abc"),
                     OmniAuthActivationProperty.hash("abc"));
    }

    @Test
    public void hash_differentInputsProduceDifferentHashes() {
        assertNotEquals(OmniAuthActivationProperty.hash("tokenA"),
                        OmniAuthActivationProperty.hash("tokenB"));
    }

    @Test
    public void hash_outputLength_matchesA32ByteDigest() {
        // SHA-256 = 32 bytes → Base64url without padding = 43 chars.
        assertEquals(43, OmniAuthActivationProperty.hash("anything").length());
    }

    // ── token strength / entropy ──────────────────────────────────────────────

    @Test
    public void newRawToken_is256BitsOfEntropy_43CharBase64Url() {
        String raw = OmniAuthActivationProperty.newRawToken();
        // 32 random bytes = 256 bits → Base64url without padding = 43 chars.
        assertEquals("Raw token should encode 32 bytes (256 bits)", 43, raw.length());
        // Base64url alphabet only: no '+', '/', or '=' padding.
        assertTrue("Token must be URL-safe (no +,/,=)",
                raw.matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    public void newRawToken_isUnpredictable_distinctAcrossManyCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue("newRawToken must never repeat", seen.add(OmniAuthActivationProperty.newRawToken()));
        }
    }

    // ── matches(): correct token within TTL accepted ──────────────────────────

    @Test
    public void matches_correctTokenWithinTtl_accepted() {
        String raw = OmniAuthActivationProperty.newRawToken();
        OmniAuthActivationProperty p = propFor(raw, System.currentTimeMillis() + HOUR);

        assertTrue("A correct, non-expired token must match", p.matches(raw));
    }

    // ── matches(): wrong token rejected ───────────────────────────────────────

    @Test
    public void matches_wrongToken_rejected() {
        String raw = OmniAuthActivationProperty.newRawToken();
        OmniAuthActivationProperty p = propFor(raw, System.currentTimeMillis() + HOUR);

        assertFalse("A different token must not match", p.matches(OmniAuthActivationProperty.newRawToken()));
    }

    @Test
    public void matches_tokenWithWrongLength_rejectedAndDoesNotThrow() {
        // MessageDigest.isEqual compares the hashes (always equal length), so a short/garbage raw
        // token hashes to a 43-char value that simply differs — rejected, never throwing.
        String raw = OmniAuthActivationProperty.newRawToken();
        OmniAuthActivationProperty p = propFor(raw, System.currentTimeMillis() + HOUR);

        assertFalse(p.matches("x"));
        assertFalse(p.matches("not-the-token-at-all"));
    }

    @Test
    public void matches_nullOrEmptyToken_rejected() {
        String raw = OmniAuthActivationProperty.newRawToken();
        OmniAuthActivationProperty p = propFor(raw, System.currentTimeMillis() + HOUR);

        assertFalse("null token must be rejected", p.matches(null));
        assertFalse("empty token must be rejected", p.matches(""));
    }

    @Test
    public void matches_whenTokenHashIsNull_rejected() {
        OmniAuthActivationProperty p = new OmniAuthActivationProperty(null, System.currentTimeMillis() + HOUR);
        assertFalse(p.matches("anything"));
    }

    // ── matches(): TTL / expiry enforced ──────────────────────────────────────

    @Test
    public void matches_expiredToken_rejectedEvenWhenTokenIsOtherwiseCorrect() {
        String raw = OmniAuthActivationProperty.newRawToken();
        OmniAuthActivationProperty p = propFor(raw, System.currentTimeMillis() - 1_000L); // already past

        assertTrue("sanity: property should report expired", p.isExpired());
        assertFalse("An expired token must never match, even if the hash is correct", p.matches(raw));
    }

    @Test
    public void isExpired_futureExpiry_isNotExpired() {
        OmniAuthActivationProperty p = propFor("t", System.currentTimeMillis() + HOUR);
        assertFalse(p.isExpired());
    }

    @Test
    public void isExpired_pastExpiry_isExpired() {
        OmniAuthActivationProperty p = propFor("t", System.currentTimeMillis() - 1L);
        assertTrue(p.isExpired());
    }

    @Test
    public void isExpired_nonPositiveExpiry_isTreatedAsExpired() {
        assertTrue("expiresAt=0 must be treated as expired", propFor("t", 0L).isExpired());
        assertTrue("negative expiresAt must be treated as expired", propFor("t", -5L).isExpired());
    }

    // ── single-use: consumed token becomes an inert expired marker ────────────

    @Test
    public void singleUse_expiredMarker_matchesNothingAndReportsExpired() {
        // This is exactly the marker OmniAuthRootAction writes when a password is set
        // (new OmniAuthActivationProperty("", 1L)) to overwrite/consume the live token.
        OmniAuthActivationProperty marker = new OmniAuthActivationProperty("", 1L);

        assertTrue("Consumed marker must be expired", marker.isExpired());
        assertFalse("Consumed marker must not match any token", marker.matches("anything"));
        assertFalse("Consumed marker must not match the empty string either", marker.matches(""));
    }

    @Test
    public void getters_exposeStoredHashAndExpiry() {
        String hash = OmniAuthActivationProperty.hash("raw");
        long exp = System.currentTimeMillis() + HOUR;
        OmniAuthActivationProperty p = new OmniAuthActivationProperty(hash, exp);

        assertEquals(hash, p.getTokenHash());
        assertEquals(exp, p.getExpiresAtEpoch());
    }
}
