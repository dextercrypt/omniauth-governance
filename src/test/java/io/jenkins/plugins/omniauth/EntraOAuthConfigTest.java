package io.jenkins.plugins.omniauth;

import hudson.util.FormValidation;
import hudson.util.Secret;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link EntraOAuthConfig} round-trip, secret-at-rest, scope/authority, and form-validation
 * tests. JenkinsRule is used because {@code getScope()} (no group sync) consults
 * {@code OmniAuthAssignmentConfig.get()} and the descriptor lives in the Jenkins registry.
 */
public class EntraOAuthConfigTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

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
        assertNotNull("clientSecret should be a Secret, not raw String", stored);
        assertEquals("plaintext is retrievable from the Secret", "s3cr3t", stored.getPlainText());
        // The encrypted-at-rest form must differ from the plaintext.
        assertFalse("encrypted value must not equal plaintext", "s3cr3t".equals(stored.getEncryptedValue()));
    }

    // ── enableGroupSync DataBoundSetter round-trip ────────────────────────────

    @Test
    public void enableGroupSync_defaultsFalse_andRoundTrips() {
        EntraOAuthConfig c = newConfig();
        assertFalse("group sync defaults off", c.isEnableGroupSync());
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
        assertNotNull("@DataBoundConstructor must remain for form binding",
                ctor.getAnnotation(DataBoundConstructor.class));
    }

    @Test
    public void setEnableGroupSync_isAnnotatedDataBoundSetter() throws Exception {
        Method m = EntraOAuthConfig.class.getDeclaredMethod("setEnableGroupSync", boolean.class);
        assertNotNull("@DataBoundSetter must remain for form binding",
                m.getAnnotation(DataBoundSetter.class));
    }

    // ── allowedDomains (SSO login domain gate) ────────────────────────────────

    @Test
    public void allowedDomains_defaultsEmpty_andRoundTripsTrimmed() throws Exception {
        EntraOAuthConfig c = newConfig();
        assertEquals("defaults to empty (allow all)", "", c.getAllowedDomains());
        c.setAllowedDomains("  example.com, example.org  ");
        assertEquals("value is trimmed", "example.com, example.org", c.getAllowedDomains());
        c.setAllowedDomains(null);
        assertEquals("null normalises to empty", "", c.getAllowedDomains());

        Method m = EntraOAuthConfig.class.getDeclaredMethod("setAllowedDomains", String.class);
        assertNotNull("@DataBoundSetter must remain for form binding",
                m.getAnnotation(DataBoundSetter.class));
    }

    @Test
    public void allowedDomains_enforcedByTheSharedDomainGate() {
        // The login gate reuses ProvisioningPortalAction.domainAllowed with this field: empty = all,
        // otherwise only listed domains pass.
        assertTrue("empty list allows any domain",
                ProvisioningPortalAction.domainAllowed("anyone@anywhere.com", ""));
        assertTrue("listed domain passes",
                ProvisioningPortalAction.domainAllowed("alice@example.com", "example.com"));
        assertFalse("unlisted domain is rejected",
                ProvisioningPortalAction.domainAllowed("mallory@evil.com", "example.com"));
    }

    // ── doTestConnection: required-field guard (no network) ───────────────────

    @Test
    public void doTestConnection_missingFields_isError() {
        EntraOAuthConfig.DescriptorImpl d = descriptor();
        assertEquals("blank secret must short-circuit before any network call",
                FormValidation.Kind.ERROR,
                d.doTestConnection("tenant-123", "client-abc", Secret.fromString("")).kind);
        assertEquals(FormValidation.Kind.ERROR,
                d.doTestConnection("", "client-abc", Secret.fromString("s")).kind);
    }
}
