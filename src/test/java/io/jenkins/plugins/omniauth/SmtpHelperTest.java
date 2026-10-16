package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Tests for {@link SmtpHelper#sendTo}'s recipient validation — the guard that
 * stops a requester-supplied email field from fanning out to extra recipients
 * or injecting SMTP headers (CRLF). The address is rejected BEFORE any send.
 *
 * No mail is ever sent: rejected addresses never spawn the sender thread, and
 * the one "valid" control uses an unroutable host on a daemon thread we never
 * join. Rejection is observed through the warning the guard logs.
 *
 * Needs JenkinsRule only to construct a real OmniAuthGlobalConfig (it calls
 * load() in its constructor). Channel/SMTP flags have no public setters, so we
 * set the private fields directly on the real instance (not a mock).
 */
public class SmtpHelperTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthGlobalConfig cfg;
    private Logger smtpLogger;
    private CapturingHandler handler;

    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        @Override public synchronized void publish(LogRecord r) { records.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
        synchronized boolean sawInvalidRecipient() {
            return records.stream().anyMatch(r ->
                    String.valueOf(r.getMessage()).contains("invalid recipient"));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Before
    public void setUp() throws Exception {
        cfg = OmniAuthGlobalConfig.get();
        // Enable notifications + SMTP and make isSmtpConfigured() true, so sendTo
        // reaches the recipient-validation branch instead of short-circuiting earlier.
        setField(cfg, "notificationsEnabled", true);
        setField(cfg, "smtpEnabled", true);
        setField(cfg, "smtpHost", "127.0.0.1");
        setField(cfg, "smtpPort", 1);                       // unroutable — valid control fails fast, async
        setField(cfg, "smtpFromAddress", "from@example.com");
        setField(cfg, "smtpUsername", "user");
        setField(cfg, "smtpPassword", Secret.fromString("pw"));
        assertTrue("precondition: SMTP must be considered configured", cfg.isSmtpConfigured());

        smtpLogger = Logger.getLogger(SmtpHelper.class.getName());
        handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        smtpLogger.addHandler(handler);
        smtpLogger.setLevel(Level.ALL);
    }

    @After
    public void tearDown() {
        if (smtpLogger != null && handler != null) smtpLogger.removeHandler(handler);
    }

    /** Calls sendTo with the given address and asserts the validation guard rejected it. */
    private void assertRejected(String toEmail) {
        handler.records.clear();
        SmtpHelper.sendTo(cfg, toEmail, "subj", "<p>html</p>", "plain");
        assertTrue("address <" + toEmail + "> should have been rejected as invalid",
                handler.sawInvalidRecipient());
    }

    // ─── rejected addresses ────────────────────────────────────────────────────

    @Test public void sendTo_nullAddress_isRejected()             { assertRejected(null); }
    @Test public void sendTo_blankAddress_isRejected()            { assertRejected("   "); }
    @Test public void sendTo_addressWithoutAtSign_isRejected()    { assertRejected("notanemail"); }
    @Test public void sendTo_commaSeparatedList_isRejected()      { assertRejected("a@x.com,b@y.com"); }
    @Test public void sendTo_semicolonSeparatedList_isRejected()  { assertRejected("a@x.com;b@y.com"); }
    @Test public void sendTo_addressWithSpace_isRejected()        { assertRejected("a b@x.com"); }

    @Test public void sendTo_addressWithCarriageReturn_isRejected() {
        assertRejected("a@x.com\r\nBcc: evil@x.com");
    }

    @Test public void sendTo_addressWithLineFeed_isRejected() {
        assertRejected("a@x.com\nBcc: evil@x.com");
    }

    @Test public void sendTo_addressWithTab_isRejected() {
        assertRejected("a@x.com\tevil@x.com");
    }

    // ─── accepted (control) ────────────────────────────────────────────────────

    @Test
    public void sendTo_wellFormedSingleAddress_isNotRejected() {
        handler.records.clear();
        // A clean address passes the guard and spawns the (daemon) sender thread;
        // we only assert it was NOT rejected. No join → no real connection matters.
        SmtpHelper.sendTo(cfg, "good@example.com", "subj", "<p>html</p>", "plain");
        assertFalse("a well-formed single address must not be rejected",
                handler.sawInvalidRecipient());
    }

    // ─── channel-level short circuit still applies ─────────────────────────────

    @Test
    public void sendTo_whenSmtpDisabled_doesNothingEvenForValidAddress() throws Exception {
        setField(cfg, "smtpEnabled", false);
        handler.records.clear();
        SmtpHelper.sendTo(cfg, "good@example.com", "subj", "<p>html</p>", "plain");
        // Disabled => returns before validation; no invalid-recipient warning either way.
        assertFalse(handler.sawInvalidRecipient());
    }
}
