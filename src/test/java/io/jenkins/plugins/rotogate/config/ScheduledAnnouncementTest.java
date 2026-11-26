package io.jenkins.plugins.rotogate.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link ScheduledAnnouncement}: the active-window evaluation (days, time window,
 * midnight wrap, disabled/blank) and the JSON round-trip used for persistence. Pure POJO — no Jenkins
 * rule needed (net.sf.json is on the classpath).
 *
 * <p>2026-11-27 is a Friday (ISO day 5); 2026-11-26 is a Thursday (used as the "other day").
 */
class ScheduledAnnouncementTest {

    private static final LocalDateTime FRI_10 = LocalDateTime.of(2026, 11, 27, 10, 0);
    private static final LocalDateTime FRI_20 = LocalDateTime.of(2026, 11, 27, 20, 0);
    private static final LocalDateTime THU_10 = LocalDateTime.of(2026, 11, 26, 10, 0);

    private static ScheduledAnnouncement rule(String msg, List<Integer> days, boolean allDay, int start, int end) {
        ScheduledAnnouncement a = new ScheduledAnnouncement();
        a.setMessage(msg);
        a.setDays(days);
        a.setAllDay(allDay);
        a.setStartMinute(start);
        a.setEndMinute(end);
        a.setEnabled(true);
        return a;
    }

    @Test
    void allDay_matchesOnListedDayOnly() {
        ScheduledAnnouncement fri = rule("No prod deploys", List.of(5), true, 0, 1439);
        assertTrue(fri.isActiveAt(FRI_10), "active on Friday");
        assertTrue(fri.isActiveAt(FRI_20), "active all day Friday");
        assertFalse(fri.isActiveAt(THU_10), "not active on Thursday");
    }

    @Test
    void emptyDays_meansEveryDay() {
        ScheduledAnnouncement everyDay = rule("Notice", List.of(), true, 0, 1439);
        assertTrue(everyDay.isActiveAt(FRI_10));
        assertTrue(everyDay.isActiveAt(THU_10));
    }

    @Test
    void timeWindow_insideAndOutside() {
        // every evening 18:00–23:59
        ScheduledAnnouncement evening = rule("No deployments", List.of(), false, 18 * 60, 23 * 60 + 59);
        assertTrue(evening.isActiveAt(FRI_20), "20:00 is inside the evening window");
        assertFalse(evening.isActiveAt(FRI_10), "10:00 is outside the evening window");
    }

    @Test
    void timeWindow_wrapsPastMidnight() {
        // 22:00 → 02:00
        ScheduledAnnouncement overnight = rule("Maintenance", List.of(), false, 22 * 60, 2 * 60);
        assertTrue(overnight.isActiveAt(LocalDateTime.of(2026, 11, 27, 23, 0)), "23:00 inside wrap window");
        assertTrue(overnight.isActiveAt(LocalDateTime.of(2026, 11, 27, 1, 0)), "01:00 inside wrap window");
        assertFalse(overnight.isActiveAt(LocalDateTime.of(2026, 11, 27, 12, 0)), "12:00 outside wrap window");
    }

    @Test
    void disabledOrBlank_neverActive() {
        ScheduledAnnouncement disabled = rule("x", List.of(), true, 0, 1439);
        disabled.setEnabled(false);
        assertFalse(disabled.isActiveAt(FRI_10), "disabled rule is never active");

        ScheduledAnnouncement blank = rule("   ", List.of(), true, 0, 1439);
        assertFalse(blank.isActiveAt(FRI_10), "blank message is never active");
    }

    @Test
    void parseList_roundTripsFields() {
        String json = "[{\"message\":\"No prod deploys\",\"days\":[5],\"allDay\":true,"
                + "\"start\":0,\"end\":1439,\"priority\":10,\"enabled\":true}]";
        List<ScheduledAnnouncement> list = ScheduledAnnouncement.parseList(json);
        assertEquals(1, list.size());
        ScheduledAnnouncement a = list.get(0);
        assertEquals("No prod deploys", a.getMessage());
        assertEquals(List.of(5), a.getDays());
        assertTrue(a.isAllDay());
        assertEquals(10, a.getPriority());
        assertTrue(a.isEnabled());
    }

    @Test
    void parseList_malformedYieldsEmpty() {
        assertTrue(ScheduledAnnouncement.parseList("not json").isEmpty());
        assertTrue(ScheduledAnnouncement.parseList("").isEmpty());
        assertTrue(ScheduledAnnouncement.parseList(null).isEmpty());
    }

    @Test
    void normalise_reserialisesCanonically() {
        String json = "[{\"message\":\"hi\",\"days\":[2,99,2],\"allDay\":false,\"start\":600,\"end\":720,\"priority\":3,\"enabled\":false}]";
        String norm = ScheduledAnnouncement.normalise(json);
        List<ScheduledAnnouncement> list = ScheduledAnnouncement.parseList(norm);
        assertEquals(1, list.size());
        assertEquals(List.of(2), list.get(0).getDays(), "invalid day 99 dropped, duplicate collapsed");
        assertFalse(list.get(0).isAllDay());
        assertEquals(600, list.get(0).getStartMinute());
        assertFalse(list.get(0).isEnabled());
    }
}
