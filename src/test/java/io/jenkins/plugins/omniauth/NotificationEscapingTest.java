package io.jenkins.plugins.omniauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests (no JenkinsRule) for the JSON / HTML escaping used by the
 * notification channels. These are the highest-priority tests: a broken esc()
 * is a JSON-injection / HTML-injection hole in attacker-influenced content
 * (usernames, reasons, scopes, comments).
 *
 * SlackHelper.esc / TeamsHelper.esc produce a *quoted* JSON string value and
 * must escape " \ \n \r \t and every control char < 0x20 as \\uXXXX.
 * SmtpHelper.esc is an HTML-entity escaper for email bodies.
 *
 * buildPayload(...) on Slack/Teams is package-private + static with no Jenkins
 * dependency, so we drive attacker-controlled text all the way through and prove
 * the result is still well-formed JSON (a breakout would make it unparseable).
 */
public class NotificationEscapingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── reflection helpers ──────────────────────────────────────────────────

    private static String esc(Class<?> clazz, String arg) throws Exception {
        Method m = clazz.getDeclaredMethod("esc", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, arg);
    }

    private static String slackEsc(String s) throws Exception { return esc(SlackHelper.class, s); }
    private static String teamsEsc(String s) throws Exception { return esc(TeamsHelper.class, s); }
    private static String smtpEsc(String s)  throws Exception { return esc(SmtpHelper.class, s); }

    // ─── SlackHelper.esc (JSON string escaping) ──────────────────────────────

    @Test
    public void slackEsc_plainText_isWrappedInQuotes() throws Exception {
        assertEquals("\"hello world\"", slackEsc("hello world"));
    }

    @Test
    public void slackEsc_null_returnsEmptyQuotedString() throws Exception {
        assertEquals("\"\"", slackEsc(null));
    }

    @Test
    public void slackEsc_doubleQuote_isBackslashEscaped() throws Exception {
        // input: one " char  ->  "\""
        assertEquals("\"\\\"\"", slackEsc("\""));
    }

    @Test
    public void slackEsc_backslash_isDoubled() throws Exception {
        // input: one \ char  ->  "\\"
        assertEquals("\"\\\\\"", slackEsc("\\"));
    }

    @Test
    public void slackEsc_newline_isEscapedNotRaw() throws Exception {
        String out = slackEsc("\n");
        assertEquals("\"\\n\"", out);
        assertEquals(-1, out.indexOf('\n'), "no raw LF char in output");
    }

    @Test
    public void slackEsc_carriageReturnAndTab_areEscaped() throws Exception {
        assertEquals("\"\\r\"", slackEsc("\r"));
        assertEquals("\"\\t\"", slackEsc("\t"));
        assertEquals(-1, slackEsc("\r").indexOf('\r'));
        assertEquals(-1, slackEsc("\t").indexOf('\t'));
    }

    @Test
    public void slackEsc_controlCharBelow0x20_isUnicodeEscaped() throws Exception {
        assertEquals("\"\\u0001\"", slackEsc("\u0001"));
        assertEquals("\"\\u001f\"", slackEsc("\u001f"));
    }

    @Test
    public void slackEsc_jsonBreakoutAttempt_isNeutralised() throws Exception {
        // Classic breakout payload: close the string + inject a sibling key.
        String out = slackEsc("\"},{\"evil\":\"x");
        // The only structural quotes are the first and last char; every interior
        // quote must be backslash-escaped, so the value parses back intact.
        JsonNode node = MAPPER.readTree("{\"k\":" + out + "}");
        assertEquals("\"},{\"evil\":\"x", node.get("k").asText());
    }

    // ─── TeamsHelper.esc (identical contract) ────────────────────────────────

    @Test
    public void teamsEsc_matchesJsonEscapingContract() throws Exception {
        assertEquals("\"\"", teamsEsc(null));
        assertEquals("\"\\\"\"", teamsEsc("\""));
        assertEquals("\"\\\\\"", teamsEsc("\\"));
        assertEquals("\"\\n\"", teamsEsc("\n"));
        assertEquals("\"\\r\"", teamsEsc("\r"));
        assertEquals("\"\\t\"", teamsEsc("\t"));
        assertEquals("\"\\u0000\"", teamsEsc("\u0000"));
    }

    @Test
    public void teamsEsc_jsonBreakoutAttempt_isNeutralised() throws Exception {
        String out = teamsEsc("\"}],\"x\":\"pwned");
        JsonNode node = MAPPER.readTree("{\"k\":" + out + "}");
        assertEquals("\"}],\"x\":\"pwned", node.get("k").asText());
    }

    // ─── SmtpHelper.esc (HTML-entity escaping) ───────────────────────────────

    @Test
    public void smtpEsc_null_returnsEmptyString() throws Exception {
        assertEquals("", smtpEsc(null));
    }

    @Test
    public void smtpEsc_ampersandFirst_avoidsDoubleEscaping() throws Exception {
        assertEquals("&amp;", smtpEsc("&"));
    }

    @Test
    public void smtpEsc_angleBrackets_areEscaped() throws Exception {
        assertEquals("&lt;", smtpEsc("<"));
        assertEquals("&gt;", smtpEsc(">"));
    }

    @Test
    public void smtpEsc_quotesAndApostrophe_areEscaped() throws Exception {
        assertEquals("&quot;", smtpEsc("\""));
        assertEquals("&#39;", smtpEsc("'"));
    }

    @Test
    public void smtpEsc_arrowChar_isEscaped() throws Exception {
        assertEquals("&#8594;", smtpEsc("\u2192"));
    }

    @Test
    public void smtpEsc_htmlInjectionAttempt_isFullyNeutralised() throws Exception {
        String out = smtpEsc("<script>alert('x')&foo\"");
        assertEquals("&lt;script&gt;alert(&#39;x&#39;)&amp;foo&quot;", out);
        assertFalse(out.contains("<"));
        assertFalse(out.contains(">"));
    }

    // ─── SlackHelper.buildPayload — structural + escaping safety ─────────────
    //
    // SlackHelper.buildPayload previously emitted STRUCTURALLY MALFORMED JSON (a
    // double comma "}},,{" before the footer/CTA block). That has been fixed (the
    // builder now uses a consistent leading-comma convention), so these tests both
    // assert the payload is valid JSON AND that attacker text is embedded only in
    // escaped form (no JSON breakout).

    @Test
    public void slackBuildPayload_maliciousTitle_isEmbeddedOnlyInEscapedForm() {
        String evilTitle = "pwn\",\"x\":\"y"; // pwn","x":"y  — attempts to close + inject a key
        String json = SlackHelper.buildPayload(evilTitle, "plain body");
        assertTrue(json.contains("pwn\\\",\\\"x\\\":\\\"y"), "escaped form must be present");
        assertFalse(json.contains(evilTitle), "raw unescaped breakout must NOT appear");
    }

    @Test
    public void slackBuildPayload_maliciousBody_isEmbeddedOnlyInEscapedForm() {
        String json = SlackHelper.buildPayload("Event", "Note: a\"}],\"evil\":1");
        assertTrue(json.contains("a\\\"}],\\\"evil\\\":1"), "escaped body value must be present");
        assertFalse(json.contains("a\"}],\"evil\":1"), "raw unescaped body breakout must NOT appear");
    }

    @Test
    public void slackBuildPayload_isWellFormedJson() throws Exception {
        // Regression for the fixed double-comma defect: the payload must parse cleanly
        // and must NOT contain the "}},,{" sequence that previously made it invalid.
        String json = SlackHelper.buildPayload("Access granted", "User: alice\n\nDetails.");
        assertFalse(json.contains("}},,{"), "payload must not contain the double-comma defect");
        assertNotNull(MAPPER.readTree(json), "payload must be valid JSON");
    }

    @Test
    public void slackBuildPayload_withCtaAndActor_isWellFormedJson() throws Exception {
        // The CTA (from a "CTA: label | url" body line) and footer blocks both prepend a
        // comma; make sure a payload carrying the optional CTA plus actor/time fields is
        // still structurally valid.
        String json = SlackHelper.buildPayload("Access granted",
                "changed by: admin\nwhen: 2026-10-16T00:00:00Z\nUser: alice\nCTA: Review | https://jenkins.example/manage");
        assertFalse(json.contains("}},,{"));
        assertNotNull(MAPPER.readTree(json));
    }

    // ─── TeamsHelper.buildPayload — end-to-end injection safety ──────────────

    @Test
    public void teamsBuildPayload_isWellFormedJson() throws Exception {
        String json = TeamsHelper.buildPayload("Account locked",
                "Account: bob\nLocked for: 15 minutes\n\nDetails.");
        assertNotNull(MAPPER.readTree(json));
    }

    @Test
    public void teamsBuildPayload_maliciousContent_staysValidJson() throws Exception {
        String json = TeamsHelper.buildPayload("evt\",\"injected\":true",
                "Requested by: \"},{\"type\":\"TextBlock\"\nreason with \\ backslash and \t tab");
        assertNotNull(MAPPER.readTree(json));
    }
}
