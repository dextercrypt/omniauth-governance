package io.jenkins.plugins.omniauth;

import hudson.util.FormValidation;
import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntraOAuthConfig} round-trip, secret-at-rest, scope/authority, and form-validation
 * tests. JenkinsRule is used because {@code getScope()} (no group sync) consults
 * {@code OmniAuthAssignmentConfig.get()} and the descriptor lives in the Jenkins registry.
 */
@WithJenkins
public class EntraOAuthConfigTest {

    private JenkinsRule j;

    @BeforeEach
    public void __withJenkins(JenkinsRule rule) {
        j = rule;
    }

    private EntraOAuthConfig newConfig() {
        return new EntraOAuthConfig("tenant-123", "client-abc", Secret.fromString("s3cr3t"));
    }

    // ── constructor round-trip ────────────────────────────────────────────────

    @Test
    public void constructor_roundTripsTenantAndClientId() {
        EntraOAuthConfig c = newConfig();
        assertEquals("tenant-123", c.getTenantId());
        assertEquals("client-abc", c.getClientId());
    }

    // ── clientSecret stored as hudson.util.Secret (plaintext never the stored form) ─

    @Test
    public void clientSecret_isStoredAsSecret_getterReturnsPlaintext() {
        EntraOAuthConfig c = newConfig();
        Secret stored = c.getClientSecret();
        assertNotNull(stored, "clientSecret should be a Secret, not raw String");
        assertEquals("s3cr3t", stored.getPlainText(), "plaintext is retrievable from the Secret");
        // The encrypted-at-rest form must differ from the plaintext.
        assertFalse("s3cr3t".equals(stored.getEncryptedValue()), "encrypted value must not equal plaintext");
    }

    // ── enableGroupSync DataBoundSetter round-trip ────────────────────────────

    @Test
    public void enableGroupSync_defaultsFalse_andRoundTrips() {
        EntraOAuthConfig c = newConfig();
        assertFalse(c.isEnableGroupSync(), "group sync defaults off");
        c.setEnableGroupSync(true);
        assertTrue(c.isEnableGroupSync());
    }

    // ── scope / authority ─────────────────────────────────────────────────────

    @Test
    public void getScope_withoutGroupSyncOrGroups_isBaseOidcScope() {
        EntraOAuthConfig c = newConfig(); // group sync off, no groups configured
        assertEquals("openid profile email", c.getScope());
    }

    @Test
    public void getScope_withGroupSync_includesGraphDefaultScope() {
        EntraOAuthConfig c = newConfig();
        c.setEnableGroupSync(true);
        assertEquals("openid profile email https://graph.microsoft.com/.default", c.getScope());
    }

    @Test
    public void getAuthority_isTenantScopedMicrosoftLoginUrl() {
        EntraOAuthConfig c = newConfig();
        assertEquals("https://login.microsoftonline.com/tenant-123", c.getAuthority());
    }

    // ── descriptor form validation ────────────────────────────────────────────

    private EntraOAuthConfig.DescriptorImpl descriptor() {
        return (EntraOAuthConfig.DescriptorImpl) j.jenkins.getDescriptorOrDie(EntraOAuthConfig.class);
    }

    @Test
    public void doCheckTenantId_blank_isError_nonBlank_isOk() {
        EntraOAuthConfig.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTenantId("").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTenantId("   ").kind);
        assertEquals(FormValidation.Kind.OK,    d.doCheckTenantId("tenant-123").kind);
    }

    @Test
    public void doCheckClientId_blank_isError_nonBlank_isOk() {
        EntraOAuthConfig.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckClientId("").kind);
        assertEquals(FormValidation.Kind.OK,    d.doCheckClientId("client-abc").kind);
    }

    @Test
    public void doCheckClientSecret_blank_isError_nonBlank_isOk() {
        EntraOAuthConfig.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckClientSecret(Secret.fromString("")).kind);
        assertEquals(FormValidation.Kind.OK,    d.doCheckClientSecret(Secret.fromString("abc")).kind);
    }

    // ── Jenkins form-binding contract (annotations must stay present) ─────────

    @Test
    public void constructor_isAnnotatedDataBoundConstructor() throws Exception {
        Constructor<EntraOAuthConfig> ctor =
                EntraOAuthConfig.class.getDeclaredConstructor(String.class, String.class, Secret.class);
        assertNotNull(ctor.getAnnotation(DataBoundConstructor.class), "@DataBoundConstructor must remain for form binding");
    }

    @Test
    public void setEnableGroupSync_isAnnotatedDataBoundSetter() throws Exception {
        Method m = EntraOAuthConfig.class.getDeclaredMethod("setEnableGroupSync", boolean.class);
        assertNotNull(m.getAnnotation(DataBoundSetter.class), "@DataBoundSetter must remain for form binding");
    }

    // ── allowedDomains (SSO login domain gate) ────────────────────────────────

    @Test
    public void allowedDomains_defaultsEmpty_andRoundTripsTrimmed() throws Exception {
        EntraOAuthConfig c = newConfig();
        assertEquals("", c.getAllowedDomains(), "defaults to empty (allow all)");
        c.setAllowedDomains("  example.com, example.org  ");
        assertEquals("example.com, example.org", c.getAllowedDomains(), "value is trimmed");
        c.setAllowedDomains(null);
        assertEquals("", c.getAllowedDomains(), "null normalises to empty");

        Method m = EntraOAuthConfig.class.getDeclaredMethod("setAllowedDomains", String.class);
        assertNotNull(m.getAnnotation(DataBoundSetter.class), "@DataBoundSetter must remain for form binding");
    }

    @Test
    public void allowedDomains_enforcedByTheSharedDomainGate() {
        // The login gate reuses ProvisioningPortalAction.domainAllowed with this field: empty = all,
        // otherwise only listed domains pass.
        assertTrue(ProvisioningPortalAction.domainAllowed("anyone@anywhere.com", ""), "empty list allows any domain");
        assertTrue(ProvisioningPortalAction.domainAllowed("alice@example.com", "example.com"), "listed domain passes");
        assertFalse(ProvisioningPortalAction.domainAllowed("mallory@evil.com", "example.com"), "unlisted domain is rejected");
    }

    // ── doTestConnection: required-field guard (no network) ───────────────────

    @Test
    public void doTestConnection_missingFields_isError() {
        EntraOAuthConfig.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doTestConnection("tenant-123", "client-abc", Secret.fromString("")).kind, "blank secret must short-circuit before any network call");
        assertEquals(FormValidation.Kind.ERROR,
                d.doTestConnection("", "client-abc", Secret.fromString("s")).kind);
    }
}
