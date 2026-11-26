package io.jenkins.plugins.rotogate.config;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A single recurring login-page announcement rule: a message shown automatically during a recurring
 * weekly window (e.g. "No production deployments" every Friday, "No deployments" every evening).
 *
 * <p>Rules are stored as a JSON array string on {@link LoginBrandingConfig} (so they round-trip
 * trivially through XStream and JCasC without a Describable), and evaluated at render time against
 * server local time by {@link #isActiveAt}. Days use ISO numbering (1 = Monday … 7 = Sunday); an
 * empty day list means "every day". Times are minutes-from-midnight; a window whose start is after
 * its end wraps past midnight (e.g. 22:00–02:00).
 */
public class ScheduledAnnouncement {

    private String message = "";
    private List<Integer> days = new ArrayList<>(); // ISO 1..7; empty = every day
    private boolean allDay = true;
    private int startMinute = 0;      // inclusive, 0..1439
    private int endMinute = 1439;     // inclusive, 0..1439
    private int priority = 0;         // higher shows first
    private boolean enabled = true;

    public ScheduledAnnouncement() { }

    public String getMessage() { return message != null ? message : ""; }
    public void setMessage(String v) { this.message = v != null ? v.trim() : ""; }
    public List<Integer> getDays() { return days; }
    public void setDays(List<Integer> v) { this.days = v != null ? v : new ArrayList<>(); }
    public boolean isAllDay() { return allDay; }
    public void setAllDay(boolean v) { this.allDay = v; }
    public int getStartMinute() { return startMinute; }
    public void setStartMinute(int v) { this.startMinute = clampMinute(v); }
    public int getEndMinute() { return endMinute; }
    public void setEndMinute(int v) { this.endMinute = clampMinute(v); }
    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    private static int clampMinute(int v) {
        if (v < 0) return 0;
        if (v > 1439) return 1439;
        return v;
    }

    /** True when this rule should be showing at {@code now} (server local time). */
    public boolean isActiveAt(LocalDateTime now) {
        if (!enabled || getMessage().isBlank() || now == null) return false;
        int dow = now.getDayOfWeek().getValue(); // 1..7
        if (days != null && !days.isEmpty() && !days.contains(dow)) return false;
        if (allDay) return true;
        int minute = now.getHour() * 60 + now.getMinute();
        if (startMinute <= endMinute) {
            return minute >= startMinute && minute <= endMinute;
        }
        // window wraps past midnight
        return minute >= startMinute || minute <= endMinute;
    }

    // ── JSON round-trip (the on-disk form is a JSON array string) ──────────────

    /** Parse a JSON array string into rules; never throws — a malformed blob yields an empty list. */
    public static List<ScheduledAnnouncement> parseList(String json) {
        List<ScheduledAnnouncement> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JSONArray arr = JSONArray.fromObject(json.trim());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ScheduledAnnouncement a = new ScheduledAnnouncement();
                a.setMessage(o.optString("message", ""));
                List<Integer> d = new ArrayList<>();
                JSONArray da = o.optJSONArray("days");
                if (da != null) {
                    for (int k = 0; k < da.size(); k++) {
                        int dv = da.optInt(k, 0);
                        if (dv >= 1 && dv <= 7 && !d.contains(dv)) d.add(dv);
                    }
                }
                a.setDays(d);
                a.setAllDay(o.optBoolean("allDay", true));
                a.setStartMinute(o.optInt("start", 0));
                a.setEndMinute(o.optInt("end", 1439));
                a.setPriority(o.optInt("priority", 0));
                a.setEnabled(o.optBoolean("enabled", true));
                out.add(a);
            }
        } catch (Exception ignore) {
            // malformed JSON → treat as no rules, never break the page
        }
        return out;
    }

    /** Serialise rules back to a canonical JSON array string (used to normalise what we store). */
    public static String toJson(List<ScheduledAnnouncement> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (ScheduledAnnouncement a : list) {
                JSONObject o = new JSONObject();
                o.put("message", a.getMessage());
                JSONArray dayArr = new JSONArray();
                dayArr.addAll(a.getDays());
                o.put("days", dayArr);
                o.put("allDay", a.isAllDay());
                o.put("start", a.getStartMinute());
                o.put("end", a.getEndMinute());
                o.put("priority", a.getPriority());
                o.put("enabled", a.isEnabled());
                arr.add(o);
            }
        }
        return arr.toString();
    }

    /** Parse then re-serialise, dropping malformed entries — the canonical on-disk form. */
    public static String normalise(String json) {
        return toJson(parseList(json));
    }
}
