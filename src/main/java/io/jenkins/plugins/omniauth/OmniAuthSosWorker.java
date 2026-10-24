package io.jenkins.plugins.omniauth;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.util.concurrent.TimeUnit;

/**
 * Keeps {@link OmniAuthSos} state in step with the trigger file on a one-minute cadence:
 * <ul>
 *   <li>announces a freshly armed window ({@code sos_armed}) even if no one has hit
 *       {@code /sos/recover} yet, and</li>
 *   <li>auto-disarms a window whose {@value OmniAuthSos#WINDOW_MINUTES}-minute lifetime has elapsed
 *       ({@code sos_expired}), deleting the stale trigger file.</li>
 * </ul>
 * The recovery endpoint performs the same checks live, so this worker is a backstop that bounds how
 * long a stale trigger can linger and guarantees the arm/expire events are recorded.
 */
@Extension
public class OmniAuthSosWorker extends AsyncPeriodicWork {

    public OmniAuthSosWorker() {
        super("OmniAuth SOS Mode");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    protected void execute(TaskListener listener) {
        if (OmniAuthSos.triggerIsStale()) {
            OmniAuthSos.expire();
        } else {
            OmniAuthSos.noteArmedIfNeeded();
        }
    }
}
