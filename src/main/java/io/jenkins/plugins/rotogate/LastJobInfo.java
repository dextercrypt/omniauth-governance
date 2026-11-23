package io.jenkins.plugins.rotogate;


final class LastJobInfo {
    final String jobName;
    final String triggeredAt;
    LastJobInfo(String jobName, String triggeredAt) {
        this.jobName     = jobName;
        this.triggeredAt = triggeredAt;
    }
}
