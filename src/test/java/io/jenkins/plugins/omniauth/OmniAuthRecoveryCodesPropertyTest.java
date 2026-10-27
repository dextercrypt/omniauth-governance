package io.jenkins.plugins.omniauth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the hashed, single-use SOS recovery codes — no Jenkins instance needed.
 */
public class OmniAuthRecoveryCodesPropertyTest {

    @Test
    public void regenerate_producesTenFormattedCodes_andEnrolls() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        assertFalse(p.isEnrolled());

        List<String> codes = p.regenerate();
        assertEquals(OmniAuthRecoveryCodesProperty.CODE_COUNT, codes.size());
        assertTrue(p.isEnrolled());
        assertEquals(OmniAuthRecoveryCodesProperty.CODE_COUNT, p.getUnusedCount());
        assertNotNull(p.getGeneratedAt());
        // Formatted as dash-grouped blocks, e.g. ABCD-EFGH-JKLM
        for (String c : codes) assertTrue(c.contains("-"), "code should be grouped with dashes: " + c);
        // All distinct
        assertEquals(codes.size(), new java.util.HashSet<>(codes).size());
    }

    @Test
    public void matchAndConsume_acceptsValidCodeOnce_thenRejectsReuse() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        List<String> codes = p.regenerate();
        String raw = codes.get(3);

        assertTrue(p.matchAndConsume(raw), "valid code must match");
        assertEquals(OmniAuthRecoveryCodesProperty.CODE_COUNT - 1, p.getUnusedCount());
        assertFalse(p.matchAndConsume(raw), "a consumed code must not match again");
        assertEquals(OmniAuthRecoveryCodesProperty.CODE_COUNT - 1, p.getUnusedCount(), "unused count unchanged after rejected reuse");
    }

    @Test
    public void matchAndConsume_isCaseAndSeparatorInsensitive() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        String raw = p.regenerate().get(0);
        // Lowercase, strip dashes, add stray spaces — all should still match.
        String messy = "  " + raw.toLowerCase(java.util.Locale.ROOT).replace("-", " ") + "  ";
        assertTrue(p.matchAndConsume(messy));
    }

    @Test
    public void matchAndConsume_rejectsUnknownCode() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        p.regenerate();
        assertFalse(p.matchAndConsume("ZZZZ-ZZZZ-ZZZZ"));
        assertFalse(p.matchAndConsume(null));
        assertFalse(p.matchAndConsume("   "));
    }

    @Test
    public void consumingAllCodes_leavesZeroUnused_butStillEnrolled() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        List<String> codes = p.regenerate();
        for (String c : codes) assertTrue(p.matchAndConsume(c));
        // Every code used: the gate keys on unused (0), while a set still technically exists.
        assertEquals(0, p.getUnusedCount());
        assertTrue(p.isEnrolled(), "a fully-consumed set still counts as a generated set");
        // No code works any more until regeneration.
        assertFalse(p.matchAndConsume(codes.get(0)));
        // Regenerating restores a full, usable set.
        p.regenerate();
        assertEquals(OmniAuthRecoveryCodesProperty.CODE_COUNT, p.getUnusedCount());
    }

    @Test
    public void regenerate_invalidatesPreviousSet() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        String oldCode = p.regenerate().get(0);
        p.regenerate(); // new set replaces the old
        assertFalse(p.matchAndConsume(oldCode), "old code must stop working after regeneration");
        assertEquals(OmniAuthRecoveryCodesProperty.CODE_COUNT, p.getUnusedCount());
    }

    @Test
    public void storedCodesAreHashedNotPlaintext() {
        OmniAuthRecoveryCodesProperty p = new OmniAuthRecoveryCodesProperty();
        String raw = p.regenerate().get(0);
        String normalized = OmniAuthRecoveryCodesProperty.normalize(raw);
        // Only a salt + salted hash is retained; neither equals the raw/normalized code.
        for (OmniAuthRecoveryCodesProperty.Code c : p.getCodes()) {
            assertNotNull(c.getSalt());
            assertNotNull(c.getHash());
            assertNotEquals(normalized, c.getHash());
            assertNotEquals(raw, c.getHash());
        }
    }
}
