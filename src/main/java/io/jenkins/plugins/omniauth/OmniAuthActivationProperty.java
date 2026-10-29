package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * One-time activation token for a provisioning-portal-created native account.
 *
 * <p>When an admin approves a native access request, the account is created with a
 * random temporary password the requester never sees; instead they receive an email
 * with a single-use link carrying a raw token. Only the token's SHA-256 hash is stored
 * here (so a read of JENKINS_HOME cannot replay it), together with an expiry. Visiting
 * the link validates the token and drops the user straight on the set-password page; the
 * property is cleared once they set their password, so the link is single-use.
 */
public class OmniAuthActivationProperty extends UserProperty {

    // lgtm false positive: Not a credential: stores only the SHA-256 hash of the one-time activation token (never the raw token), so encryption would add nothing.
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String tokenHash;      // SHA-256(raw token), Base64url; never the raw token
    private long   expiresAtEpoch; // epoch millis

    public OmniAuthActivationProperty(String tokenHash, long expiresAtEpoch) {
        this.tokenHash = tokenHash;
        this.expiresAtEpoch = expiresAtEpoch;
    }

    public String getTokenHash()     { return tokenHash; }
    public long   getExpiresAtEpoch() { return expiresAtEpoch; }

    public boolean isExpired() {
        return expiresAtEpoch <= 0 || System.currentTimeMillis() >= expiresAtEpoch;
    }

    /** Constant-time check that {@code rawToken} matches this property and is not expired. */
    public boolean matches(String rawToken) {
        if (rawToken == null || rawToken.isEmpty() || tokenHash == null || isExpired()) return false;
        byte[] a = tokenHash.getBytes(StandardCharsets.UTF_8);
        byte[] b = hash(rawToken).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    // ----- token helpers -----------------------------------------------------

    /** A fresh URL-safe random token (raw — emailed to the requester, never stored). */
    public static String newRawToken() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {
        @Override public String getDisplayName() { return "OmniAuth Activation Token"; }
        @Override public boolean isEnabled() { return false; }
        @Override public UserProperty newInstance(User user) { return null; }
    }
}
