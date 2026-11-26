package io.jenkins.plugins.rotogate;


public class DayActivity {
    private final String label;
    private final int ssoSuccess;
    private final int nativeSuccess;
    private final int failure;
    DayActivity(String label, int ssoSuccess, int nativeSuccess, int failure) {
        this.label = label; this.ssoSuccess = ssoSuccess;
        this.nativeSuccess = nativeSuccess; this.failure = failure;
    }
    public String getLabel()       { return label; }
    public int getSsoSuccess()     { return ssoSuccess; }
    public int getNativeSuccess()  { return nativeSuccess; }
    public int getSuccess()        { return ssoSuccess + nativeSuccess; }
    public int getFailure()        { return failure; }
}
