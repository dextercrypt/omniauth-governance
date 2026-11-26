package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Weekly worker that scans all Rotogate assignments and sends a digest email
 * listing those that have not been reviewed within the configured threshold.
 */
@Extension
public class AccessReviewWork extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AccessReviewWork.class.getName());

    public AccessReviewWork() {
        super("Rotogate Access Review Digest");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN; // checked every minute; actual firing gated by cron
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        RotogateGlobalConfig config = RotogateGlobalConfig.get();
        if (config == null || !config.isAccessReviewEnabled()) return;
        // Fire weekly on Monday at 09:00 - reuse stale warning cron mechanism
        if (!StaleUserCleanupWork.cronMatches("0 9 * * 1")) return;
        runDigest(config);
    }

    public static void runDigest(RotogateGlobalConfig config) {
        RotogateAssignmentConfig assignmentConfig = RotogateAssignmentConfig.get();
        if (assignmentConfig == null) return;

        int thresholdDays = config.getAccessReviewThresholdDays();
        List<RotogateAssignment> overdue = new ArrayList<>();

        for (RotogateAssignment a : assignmentConfig.getAssignments()) {
            if (a.isReviewDue(thresholdDays)) overdue.add(a);
        }

        LOGGER.info("Access review scan: " + overdue.size() + " overdue assignment(s)");
        if (!overdue.isEmpty()) {
            NotificationService.sendAccessReviewDigest(config, overdue, thresholdDays);
        }
    }
}
