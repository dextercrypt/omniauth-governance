package io.jenkins.plugins.omniauth;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Pure unit tests for {@link LoginHistoryProperty}: newest-first ordering, the 10-event
 * capacity cap, and thread-safe reads that hand back a defensive copy (no CME).
 *
 * No Jenkins required — the property's list operations never touch Jenkins core.
 */
public class LoginHistoryPropertyTest {

    private LoginEvent event(String ts) {
        return new LoginEvent(ts, "127.0.0.1", "curl/8", "curl", "macOS", "Native", true);
    }

    // ── ordering ──────────────────────────────────────────────────────────────

    @Test
    public void addEvent_prependsNewestFirst() {
        LoginHistoryProperty p = new LoginHistoryProperty();
        LoginEvent first  = event("2026-01-01T00:00:00Z");
        LoginEvent second = event("2026-01-02T00:00:00Z");

        p.addEvent(first);
        p.addEvent(second);

        List<LoginEvent> events = p.getEvents();
        assertEquals(2, events.size());
        assertSame("Most recently added event must be at index 0", second, events.get(0));
        assertSame(first, events.get(1));
    }

    @Test
    public void getLatestEvent_returnsMostRecentlyAdded() {
        LoginHistoryProperty p = new LoginHistoryProperty();
        p.addEvent(event("2026-01-01T00:00:00Z"));
        LoginEvent newest = event("2026-01-05T00:00:00Z");
        p.addEvent(newest);

        assertSame(newest, p.getLatestEvent());
    }

    @Test
    public void getLatestEvent_whenEmpty_returnsNull() {
        assertNull(new LoginHistoryProperty().getLatestEvent());
    }

    @Test
    public void getEvents_whenEmpty_returnsEmptyList() {
        assertTrue(new LoginHistoryProperty().getEvents().isEmpty());
    }

    // ── capacity cap ────────────────────────────────────────────────────────

    @Test
    public void addEvent_capsAtTenEventsDroppingOldest() {
        LoginHistoryProperty p = new LoginHistoryProperty();
        for (int i = 0; i < 15; i++) {
            p.addEvent(event("evt-" + i));
        }

        List<LoginEvent> events = p.getEvents();
        assertEquals("History must be capped at 10 events", 10, events.size());
        // Newest (evt-14) is kept at the head; the 5 oldest (evt-0..evt-4) are dropped.
        assertEquals("evt-14", events.get(0).getTimestamp());
        assertEquals("evt-5", events.get(9).getTimestamp());
    }

    @Test
    public void addEvent_exactlyTen_keepsAll() {
        LoginHistoryProperty p = new LoginHistoryProperty();
        for (int i = 0; i < 10; i++) p.addEvent(event("evt-" + i));

        assertEquals(10, p.getEvents().size());
        assertEquals("evt-9", p.getLatestEvent().getTimestamp());
    }

    // ── defensive copy / thread-safe read ─────────────────────────────────────

    @Test
    public void getEvents_returnsDefensiveCopy_mutatingItDoesNotAffectInternalState() {
        LoginHistoryProperty p = new LoginHistoryProperty();
        p.addEvent(event("a"));
        p.addEvent(event("b"));

        List<LoginEvent> copy = p.getEvents();
        copy.clear();

        assertEquals("Clearing the returned list must not clear the property's own history",
                2, p.getEvents().size());
    }

    @Test
    public void getEvents_separateCallsReturnDistinctListInstances() {
        LoginHistoryProperty p = new LoginHistoryProperty();
        p.addEvent(event("a"));
        assertNotSame(p.getEvents(), p.getEvents());
    }

    @Test
    public void concurrentAddsAndReads_neverThrowConcurrentModification() throws Exception {
        final LoginHistoryProperty p = new LoginHistoryProperty();
        final AtomicBoolean failed = new AtomicBoolean(false);
        final int iterations = 5000;

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) p.addEvent(event("w-" + i));
            } catch (Throwable t) { failed.set(true); }
        });
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    // Iterating the returned copy must be safe even while the writer mutates.
                    for (LoginEvent e : p.getEvents()) {
                        if (e.getTimestamp() == null) failed.set(true);
                    }
                }
            } catch (Throwable t) { failed.set(true); }
        });

        writer.start();
        reader.start();
        writer.join();
        reader.join();

        assertFalse("Concurrent add/read must not throw (copy returned under the lock)", failed.get());
        assertEquals(10, p.getEvents().size());
    }
}
