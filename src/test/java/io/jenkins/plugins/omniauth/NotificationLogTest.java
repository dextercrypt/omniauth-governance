package io.jenkins.plugins.omniauth;

import hudson.XmlFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NotificationLog}: the in-memory + persisted record of every
 * notification attempt. Needs JenkinsRule because get()/persist() resolve the
 * log file under {@code Jenkins.get().getRootDir()}.
 *
 * The singleton INSTANCE is static and survives across tests in the same JVM,
 * so every test clears it first.
 *
 * Priorities covered: getEntries() must be a defensive copy (no CME when the
 * returned list is iterated while async senders append), the capacity cap, and
 * the redacted webhook recipient label surviving persistence.
 */
@WithJenkins
public class NotificationLogTest {

    private JenkinsRule j;

    private NotificationLog log;

    @BeforeEach
    public void freshLog(JenkinsRule rule) {
        j = rule;
        log = NotificationLog.get();
        log.clear();
    }

    // ─── basic add / read ────────────────────────────────────────────────────

    @Test
    public void addEntry_isVisibleInGetEntries() {
        log.addEntry("[Slack] test subject", "Slack webhook", true, null);

        List<NotificationLogEntry> entries = log.getEntries();
        assertEquals(1, entries.size());
        NotificationLogEntry e = entries.get(0);
        assertEquals("[Slack] test subject", e.getDescription());
        assertEquals("Slack webhook", e.getRecipients());
        assertTrue(e.isSuccess());
        assertNull(e.getErrorMessage());
    }

    @Test
    public void addEntry_stripsJenkinsOmniAuthPrefixFromDescription() {
        log.addEntry("[Jenkins OmniAuth] Something happened", "to@x.com", true, null);
        assertEquals("Something happened", log.getEntries().get(0).getDescription());
    }

    @Test
    public void addEntry_insertsMostRecentFirst() {
        log.addEntry("[Email] first", "a@x.com", true, null);
        log.addEntry("[Email] second", "b@x.com", true, null);

        List<NotificationLogEntry> entries = log.getEntries();
        assertEquals("[Email] second", entries.get(0).getDescription());
        assertEquals("[Email] first", entries.get(1).getDescription());
    }

    @Test
    public void addEntry_recordsFailureWithErrorMessage() {
        log.addEntry("[Teams] boom", "Teams webhook", false, "HTTP 500: upstream error");

        NotificationLogEntry e = log.getEntries().get(0);
        assertFalse(e.isSuccess());
        assertEquals("HTTP 500: upstream error", e.getErrorMessage());
    }

    // ─── defensive copy / CME-safety ──────────────────────────────────────────

    @Test
    public void getEntries_returnsNewListInstanceEachCall() {
        log.addEntry("[Slack] x", "Slack webhook", true, null);
        assertNotSame(log.getEntries(), log.getEntries());
    }

    @Test
    public void getEntries_mutatingReturnedList_doesNotAffectLog() {
        log.addEntry("[Slack] x", "Slack webhook", true, null);

        List<NotificationLogEntry> copy = log.getEntries();
        copy.clear();
        copy.add(new NotificationLogEntry("ts", "injected", "nobody", false, null));

        // Internal state is untouched: still exactly the one real entry.
        List<NotificationLogEntry> fresh = log.getEntries();
        assertEquals(1, fresh.size());
        assertEquals("[Slack] x", fresh.get(0).getDescription());
    }

    @Test
    public void getEntries_iterationIsCmeSafeWhileConcurrentlyAppending() throws Exception {
        for (int i = 0; i < 20; i++) log.addEntry("[Email] seed " + i, "a@x.com", true, null);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicBoolean writerFailed = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 2000 && !stop.get(); i++) {
                    log.addEntry("[Email] concurrent " + i, "a@x.com", true, null);
                }
            } catch (RuntimeException ex) {
                writerFailed.set(true);
            }
        });
        writer.start();

        try {
            // Iterate snapshots repeatedly while the writer hammers addEntry.
            // A live (non-copying) getEntries() would throw ConcurrentModificationException.
            for (int round = 0; round < 500; round++) {
                for (Iterator<NotificationLogEntry> it = log.getEntries().iterator(); it.hasNext(); ) {
                    assertNotNull(it.next().getDescription());
                }
            }
        } finally {
            stop.set(true);
            writer.join(10_000);
        }
        assertFalse(writerFailed.get(), "concurrent writer must not have thrown");
    }

    // ─── capacity cap ─────────────────────────────────────────────────────────

    @Test
    public void addEntry_capsAtMaxEntries() {
        for (int i = 0; i < 250; i++) log.addEntry("[Email] msg " + i, "a@x.com", true, null);

        assertEquals(200, log.getEntries().size(), "log must cap at MAX_ENTRIES (200)");
        // Most-recent-first + cap => the newest entry is retained, the oldest dropped.
        assertEquals("[Email] msg 249", log.getEntries().get(0).getDescription());
    }

    // ─── lastForChannel helpers ────────────────────────────────────────────────

    @Test
    public void lastSlackEntry_returnsMostRecentSlackEntryOnly() {
        log.addEntry("[Email] e1", "a@x.com", true, null);
        log.addEntry("[Slack] s1", "Slack webhook", true, null);
        log.addEntry("[Teams] t1", "Teams webhook", true, null);
        log.addEntry("[Slack] s2", "Slack webhook", false, "err");

        NotificationLogEntry slack = log.lastSlackEntry();
        assertNotNull(slack);
        assertEquals("[Slack] s2", slack.getDescription());

        assertEquals("[Email] e1", log.lastSmtpEntry().getDescription());
        assertEquals("[Teams] t1", log.lastTeamsEntry().getDescription());
    }

    @Test
    public void lastSlackEntry_whenNoneLogged_returnsNull() {
        log.addEntry("[Email] only", "a@x.com", true, null);
        assertNull(log.lastSlackEntry());
    }

    // ─── clear ──────────────────────────────────────────────────────────────────

    @Test
    public void clear_emptiesTheLog() {
        log.addEntry("[Slack] x", "Slack webhook", true, null);
        log.clear();
        assertTrue(log.getEntries().isEmpty());
    }

    // ─── redaction + persistence ──────────────────────────────────────────────

    @Test
    public void webhookRecipientLabel_isStoredLiterally_notTheUrl() {
        // A sender MUST pass the literal label, never the secret webhook URL.
        log.addEntry("[Slack] Access granted", "Slack webhook", true, null);

        NotificationLogEntry e = log.getEntries().get(0);
        assertEquals("Slack webhook", e.getRecipients());
        assertFalse(e.getRecipients().contains("http"), "recipient must not leak a URL");
        assertFalse(e.getRecipients().contains("hooks."), "recipient must not leak a host");
    }

    @Test
    public void redactedRecipientLabel_survivesPersistenceRoundTrip() throws Exception {
        log.addEntry("[Teams] Account locked", "Teams webhook", false, "HTTP 403");

        // Re-read the persisted XML straight off disk (same path get()/persist() use).
        XmlFile f = new XmlFile(new File(j.jenkins.getRootDir(), "omniauth-notification-log.xml"));
        assertTrue(f.exists(), "log must have been persisted");
        NotificationLog reloaded = (NotificationLog) f.read();

        List<NotificationLogEntry> entries = reloaded.getEntries();
        assertEquals(1, entries.size());
        NotificationLogEntry e = entries.get(0);
        assertEquals("Teams webhook", e.getRecipients());
        assertFalse(e.getRecipients().contains("http"));
        assertEquals("[Teams] Account locked", e.getDescription());
        assertEquals("HTTP 403", e.getErrorMessage());
    }
}
