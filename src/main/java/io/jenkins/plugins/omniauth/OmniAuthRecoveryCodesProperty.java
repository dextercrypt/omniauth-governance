package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Per-admin <b>SOS recovery codes</b>: a set of single-use, high-entropy codes an administrator
 * generates once and stores somewhere safe (password manager, printed). They are the <em>only</em>
 * credential accepted at {@code /sos/recover} while SOS Mode is armed — see {@link OmniAuthSos}.
 *
 * <p><b>What is stored.</b> Only a salted SHA-256 hash of each code, never the code itself. A read of
 * {@code JENKINS_HOME} (disk theft, poisoned backup, path-traversal read) therefore yields hashes, not
 * usable codes — this is the core advantage over a password/TOTP whose secret or verifier would be
 * exposed. The codes are high-entropy, so a fast salted hash is sufficient (no slow KDF needed) and
 * lets the recovery endpoint test a submitted code against every admin cheaply.
 *
 * <p><b>Identity from the code.</b> Because each code is unique and bound to one admin, a match at
 * {@code /sos/recover} identifies <em>who</em> recovered with no username needed. {@link #matchAndConsume}
 * verifies in constant time per stored hash and marks the matched code used, so each code works once.
 */
public class OmniAuthRecoveryCodesProperty extends UserProperty {

    /** How many codes a generation produces. */
    public static final int CODE_COUNT = 10;
    /** Unambiguous alphabet (no O/0/I/1) for the printable codes. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** Characters per code (excluding the grouping dashes). ~59 bits of entropy. */
    private static final int CODE_LEN = 12;

    /** ISO-8601 instant the current set was generated; null if never. */
    private String generatedAt;

    /** The stored codes (salted hashes + used flag). */
    private List<Code> codes;

    public OmniAuthRecoveryCodesProperty() {
        this.codes = new ArrayList<>();
    }

    /** One stored recovery code: a random salt, the salted SHA-256 hash, and whether it was used. */
    public static class Code {
        private String salt;   // Base64, 16 random bytes
        private String hash;   // Base64, SHA-256(salt-bytes || normalized-code)
        private boolean used;

        public Code() {}
        Code(String salt, String hash) { this.salt = salt; this.hash = hash; this.used = false; }

        public String getSalt()  { return salt; }
        public String getHash()  { return hash; }
        public boolean isUsed()  { return used; }
        void markUsed()          { this.used = true; }
    }

    // ----- state queries ------------------------------------------------------

    private List<Code> codes() {
        if (codes == null) codes = new ArrayList<>();
        return codes;
    }

    /** True once a set has been generated (whether or not any remain unused). */
    public boolean isEnrolled() { return !codes().isEmpty(); }

    public String getGeneratedAt() { return generatedAt; }

    /** Number of codes not yet consumed. */
    public int getUnusedCount() {
        int n = 0;
        for (Code c : codes()) if (!c.isUsed()) n++;
        return n;
    }

    /** Total codes in the current set. */
    public int getTotalCount() { return codes().size(); }

    /** The stored codes (salted hashes + used flags), read-only. */
    public List<Code> getCodes() { return java.util.Collections.unmodifiableList(codes()); }

    // ----- generation ---------------------------------------------------------

    /**
     * Generate a fresh set, replacing any existing codes (old codes stop working immediately).
     * Returns the raw, human-formatted codes to show the admin <em>once</em>; only their hashes are kept.
     */
    public List<String> regenerate() {
        SecureRandom rng = new SecureRandom();
        List<String> raw = new ArrayList<>(CODE_COUNT);
        List<Code> fresh = new ArrayList<>(CODE_COUNT);
        for (int i = 0; i < CODE_COUNT; i++) {
            String code = randomCode(rng);
            raw.add(formatForDisplay(code));
            byte[] saltBytes = new byte[16];
            rng.nextBytes(saltBytes);
            String salt = Base64.getEncoder().encodeToString(saltBytes);
            fresh.add(new Code(salt, hash(salt, code)));
        }
        this.codes = fresh;
        this.generatedAt = java.time.Instant.now().toString();
        return raw;
    }

    // ----- verification -------------------------------------------------------

    /**
     * If {@code submitted} matches an unused code, mark it used and return true; otherwise false.
     * Normalizes case / dashes / whitespace first, and compares every stored hash in constant time
     * so a match reveals nothing by timing. The caller must {@link User#save()} on a true result.
     */
    public boolean matchAndConsume(String submitted) {
        if (submitted == null) return false;
        String normalized = normalize(submitted);
        if (normalized.isEmpty()) return false;
        Code matched = null;
        for (Code c : codes()) {
            if (c.isUsed() || c.getSalt() == null || c.getHash() == null) continue;
            byte[] a = c.getHash().getBytes(StandardCharsets.UTF_8);
            byte[] b = hash(c.getSalt(), normalized).getBytes(StandardCharsets.UTF_8);
            // MessageDigest.isEqual is constant-time; capture the first match without early-exit.
            if (MessageDigest.isEqual(a, b)) matched = c;
        }
        if (matched == null) return false;
        matched.markUsed();
        return true;
    }

    // ----- helpers ------------------------------------------------------------

    private static String randomCode(SecureRandom rng) {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Group a raw code into 4-char blocks for legibility, e.g. {@code ABCD-EFGH-JKLM}. */
    private static String formatForDisplay(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    /** Uppercase, drop everything that is not an alphabet character (dashes, spaces, O→ none). */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (ALPHABET.indexOf(c) >= 0) sb.append(c);
        }
        return sb.toString();
    }

    private static String hash(String saltBase64, String normalizedCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Base64.getDecoder().decode(saltBase64));
            byte[] d = md.digest(normalizedCode.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {
        @Override public String getDisplayName() { return "OmniAuth SOS Recovery Codes"; }
        @Override public boolean isEnabled() { return false; }
        @Override public UserProperty newInstance(User user) { return null; }
    }
}
