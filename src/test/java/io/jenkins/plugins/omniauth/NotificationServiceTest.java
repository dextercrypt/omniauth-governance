package io.jenkins.plugins.omniauth;

import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Event-gating tests for {@link NotificationService} / the channel helpers.
 *
 * We cannot (and must not) make real Slack/Teams/SMTP calls, so Slack/Teams are
 * pointed at a loopback {@link HttpServer} that returns 200 instantly — letting
 * us observe, without the network, exactly WHICH events reach a channel and that
 * the persisted log records the redacted webhook label, never the URL.
 *
 * Gating rules under test:
 *   - master switch off  => nothing dispatches
 *   - channel disabled   => that channel gets nothing
 *   - event not subscribed for a channel => that channel gets nothing
 *   - empty webhook URL  => Slack/Teams send is a no-op
 *   - subscribed + enabled + URL set => the channel is hit and logged
 *   - cfg == null        => no-op (no throw)
 *
 * OmniAuthGlobalConfig has no public setters for these fields, so we set the
 * private fields on the REAL instance (never a mock, per Mockito caution).
 */
public class NotificationServiceTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthGlobalConfig cfg;
    private HttpServer server;
    private String webhookUrl;
    private AtomicInteger hits;
    private volatile CountDownLatch hitLatch;

    private static final String EVENT = "userDeleted"; // the event sendUserDeleted dispatches

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void setEvents(String field, String... events) throws Exception {
        setField(cfg, field, new ArrayList<>(Arrays.asList(events)));
    }

    @Before
    public void setUp() throws Exception {
        cfg = OmniAuthGlobalConfig.get();
        NotificationLog.get().clear();

        hits = new AtomicInteger(0);
        hitLatch = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            hits.incrementAndGet();
            hitLatch.countDown();
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();
        webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        // Baseline: master ON; both webhook channels disabled with the webhook wired.
        // SMTP stays disabled (default) so SmtpHelper.send never spawns a thread.
        setField(cfg, "notificationsEnabled", true);
        setField(cfg, "smtpEnabled", false);
        setField(cfg, "slackEnabled", false);
        setField(cfg, "teamsEnabled", false);
        setField(cfg, "slackWebhookUrl", Secret.fromString(webhookUrl));
        setField(cfg, "teamsWebhookUrl", Secret.fromString(webhookUrl));
        setEvents("smtpEvents");  // nothing to SMTP
        setEvents("slackEvents");
        setEvents("teamsEvents");
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private void fireUserDeleted() {
        NotificationService.sendUserDeleted(cfg, "victim@example.com", "admin");
    }

    private void assertChannelHit() throws Exception {
        assertTrue("channel should have been contacted", hitLatch.await(10, TimeUnit.SECONDS));
    }

    private void assertNoChannelHit() throws Exception {
        assertFalse("no channel contact expected", hitLatch.await(900, TimeUnit.MILLISECONDS));
        assertEquals(0, hits.get());
    }

    /** Polls the notification log for an entry whose description starts with the given prefix. */
    private NotificationLogEntry awaitLogEntry(String descPrefix, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (NotificationLogEntry e : NotificationLog.get().getEntries()) {
                if (e.getDescription().startsWith(descPrefix)) return e;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private boolean anyLogEntry(String descPrefix) {
        for (NotificationLogEntry e : NotificationLog.get().getEntries()) {
            if (e.getDescription().startsWith(descPrefix)) return true;
        }
        return false;
    }

    // ─── gating: master switch ───────────────────────────────────────────────

    @Test
    public void notificationsDisabled_dispatchesToNoChannel() throws Exception {
        setField(cfg, "notificationsEnabled", false);
        setField(cfg, "slackEnabled", true);
        setEvents("slackEvents", EVENT);

        fireUserDeleted();
        assertNoChannelHit();
        assertFalse(anyLogEntry("[Slack] "));
    }

    @Test
    public void cfgNull_isNoOp() throws Exception {
        NotificationService.sendUserDeleted(null, "victim@example.com", "admin");
        assertNoChannelHit();
    }

    // ─── gating: channel enable flag ───────────────────────────────────────────

    @Test
    public void slackDisabled_evenWhenSubscribed_dispatchesNothingToSlack() throws Exception {
        setField(cfg, "slackEnabled", false);
        setEvents("slackEvents", EVENT);

        fireUserDeleted();
        assertNoChannelHit();
        assertFalse(anyLogEntry("[Slack] "));
    }

    // ─── gating: per-channel event subscription ────────────────────────────────

    @Test
    public void slackEnabledButEventNotSubscribed_dispatchesNothingToSlack() throws Exception {
        setField(cfg, "slackEnabled", true);
        setEvents("slackEvents", "bruteForce"); // subscribed to a DIFFERENT event, not userDeleted

        fireUserDeleted();
        assertNoChannelHit();
        assertFalse(anyLogEntry("[Slack] "));
    }

    // ─── gating: empty webhook URL ─────────────────────────────────────────────

    @Test
    public void slackSubscribedButWebhookUrlEmpty_isNoOp() throws Exception {
        setField(cfg, "slackEnabled", true);
        setField(cfg, "slackWebhookUrl", Secret.fromString("   "));
        setEvents("slackEvents", EVENT);

        fireUserDeleted();
        assertNoChannelHit();
        assertFalse(anyLogEntry("[Slack] "));
    }

    // ─── positive dispatch + redacted recipient ────────────────────────────────

    @Test
    public void slackSubscribed_dispatchesAndLogsRedactedRecipient() throws Exception {
        setField(cfg, "slackEnabled", true);
        setEvents("slackEvents", EVENT);

        fireUserDeleted();
        assertChannelHit();

        NotificationLogEntry e = awaitLogEntry("[Slack] ", 10_000);
        assertNotNull("a Slack log entry should have been written", e);
        assertTrue("send should be recorded as success", e.isSuccess());
        assertEquals("recipient must be the redacted label", "Slack webhook", e.getRecipients());
        assertFalse("webhook URL must never appear in the log recipient",
                e.getRecipients().contains(webhookUrl));
        assertFalse(e.getRecipients().contains("127.0.0.1"));
    }

    @Test
    public void teamsSubscribed_dispatchesAndLogsRedactedRecipient() throws Exception {
        setField(cfg, "teamsEnabled", true);
        setEvents("teamsEvents", EVENT);

        fireUserDeleted();
        assertChannelHit();

        NotificationLogEntry e = awaitLogEntry("[Teams] ", 10_000);
        assertNotNull("a Teams log entry should have been written", e);
        assertTrue(e.isSuccess());
        assertEquals("Teams webhook", e.getRecipients());
        assertFalse(e.getRecipients().contains(webhookUrl));
        assertFalse(e.getRecipients().contains("127.0.0.1"));
    }

    // ─── SMTP channel stays off => no email logged ─────────────────────────────

    @Test
    public void smtpDisabled_producesNoEmailLogEntry() throws Exception {
        // SMTP subscribed to the event, but the channel flag is off.
        setEvents("smtpEvents", EVENT);
        setField(cfg, "smtpEnabled", false);

        fireUserDeleted();
        // Give any (erroneously) spawned sender thread a chance; none should exist.
        Thread.sleep(900);
        assertFalse("no [Email] entry when SMTP is disabled", anyLogEntry("[Email] "));
    }

    // ─── both channels off, master on => total silence ──────────────────────────

    @Test
    public void allChannelsOff_dispatchesNothing() throws Exception {
        // Baseline already has slack/teams/smtp disabled.
        fireUserDeleted();
        assertNoChannelHit();
        assertTrue(NotificationLog.get().getEntries().isEmpty());
    }
}
