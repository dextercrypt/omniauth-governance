package io.jenkins.plugins.rotogate.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MsalTokenHelper#forceQueryResponseMode(String)}.
 *
 * <p>MSAL4J 1.17.0 emits {@code response_mode=form_post} on the authorization URL even though
 * the flow requests {@link com.microsoft.aad.msal4j.ResponseMode#QUERY}. form_post turns the
 * Entra callback into a cross-site POST that drops the SameSite=Lax session cookie, losing the
 * OAuth state/nonce/PKCE verifier; forcing query keeps the callback a GET so the cookie - and
 * the stored state - survive.
 */
public class MsalTokenHelperTest {

    @Test public void rewritesFormPostToQuery() {
        String in  = "https://login.microsoftonline.com/t/oauth2/v2.0/authorize"
                   + "?response_type=code&response_mode=form_post&state=abc&code_challenge=xyz";
        String out = MsalTokenHelper.forceQueryResponseMode(in);
        assertTrue(out.contains("response_mode=query"), "should request query mode");
        assertFalse(out.contains("form_post"), "no form_post should remain");
    }

    @Test public void leavesOtherParametersUntouched() {
        String in  = "https://login.microsoftonline.com/t/oauth2/v2.0/authorize"
                   + "?response_type=code&response_mode=form_post&state=abc123&nonce=n-1&code_challenge=xyz";
        String out = MsalTokenHelper.forceQueryResponseMode(in);
        assertTrue(out.contains("response_type=code"));
        assertTrue(out.contains("state=abc123"));
        assertTrue(out.contains("nonce=n-1"));
        assertTrue(out.contains("code_challenge=xyz"));
    }

    @Test public void idempotentWhenAlreadyQuery() {
        // If MSAL ever honours query, the URL is already correct - must be a no-op.
        String already = "https://login.microsoftonline.com/t/oauth2/v2.0/authorize"
                       + "?response_type=code&response_mode=query&state=abc";
        assertEquals(already, MsalTokenHelper.forceQueryResponseMode(already));
    }

    @Test public void noOpWhenResponseModeAbsent() {
        // If MSAL omits response_mode, Entra defaults to query for response_type=code - leave as-is.
        String noMode = "https://login.microsoftonline.com/t/oauth2/v2.0/authorize"
                      + "?response_type=code&state=abc";
        assertEquals(noMode, MsalTokenHelper.forceQueryResponseMode(noMode));
    }
}
