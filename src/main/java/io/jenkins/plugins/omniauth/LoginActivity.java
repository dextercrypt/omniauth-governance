package io.jenkins.plugins.omniauth;

import java.util.List;

public class LoginActivity {
    private final List<DayActivity> days;
    private final int max;
    LoginActivity(List<DayActivity> days, int max) { this.days = days; this.max = max; }
    public List<DayActivity> getDays() { return days; }
    public int getMax() { return max; }           // never 0 (floored at 1) so bar math is safe
}
