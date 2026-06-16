package io.jenkins.plugins.omniauth;

import org.springframework.security.authentication.AccountStatusException;

/**
 * Thrown by the wrapped {@link org.springframework.security.authentication.AuthenticationManager}
 * when a native user whose credentials are otherwise valid still has the
 * {@link OmniAuthForcePasswordProperty force-password-change} flag set.
 *
 * <p>Modelled as an {@link AccountStatusException} (like {@code LockedException} /
 * {@code DisabledException}) so Spring Security treats it as an authentication
 * <em>failure</em>: no {@code SecurityContext} is established and no session is created.
 * This makes the force-password-change state fail-closed across <em>every</em>
 * authentication channel — web form, HTTP BASIC, API token and CLI — rather than
 * relying on a request filter to contain an already-authenticated session.
 *
 * <p>The interactive browser flow does not go through this manager: it posts to
 * {@code /omniauth/nativeLogin}, which validates the temporary password directly and
 * routes the user to the branded set-password page without ever logging them in.
 */
public class PasswordChangeRequiredException extends AccountStatusException {

    public PasswordChangeRequiredException(String username) {
        super("Password change required before login for: " + username);
    }
}
