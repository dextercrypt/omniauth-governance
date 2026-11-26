package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;

public class RotogateForcePasswordProperty extends UserProperty {

    private boolean forcePasswordChange;

    public RotogateForcePasswordProperty(boolean forcePasswordChange) {
        this.forcePasswordChange = forcePasswordChange;
    }

    public boolean isForcePasswordChange() { return forcePasswordChange; }
    public void setForcePasswordChange(boolean v) { this.forcePasswordChange = v; }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {
        @Override public String getDisplayName() { return "Rotogate Force Password Change"; }
        @Override public boolean isEnabled() { return false; }
        @Override public UserProperty newInstance(User user) { return null; }
    }
}
