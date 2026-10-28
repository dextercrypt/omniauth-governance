package io.jenkins.plugins.omniauth;

import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the native-first SSO toggle imports through JCasC: the {@code ssoEnabled} switch applies
 * from YAML and drives the realm's active state, in both the "SSO on" and the "preserved but
 * dormant" (toggle off with a provider still configured) states.
 *
 * <p>Scope note: this pins JCasC <em>import</em> only, which is the direction OmniAuth relies on
 * (declarative config applied from YAML). The realm is deliberately not round-tripped through
 * {@code casc export} — the Entra client secret cannot be emitted in plaintext, so the security
 * realm is excluded from export just as it is from the plugin's own backup/restore. The
 * authoritative on-disk persistence is {@code config.xml} (XStream), which stores {@code ssoEnabled}
 * explicitly; see {@link OmniAuthSecurityRealmTest} for the toggle's behavioural coverage.
 */
@WithJenkinsConfiguredWithCode
public class JCasCRealmSsoToggleTest {

    private JenkinsConfiguredWithCodeRule j;

    @BeforeEach
    void setUp(JenkinsConfiguredWithCodeRule r) {
        j = r;
    }

    private OmniAuthSecurityRealm realm() {
        assertInstanceOf(OmniAuthSecurityRealm.class, j.jenkins.getSecurityRealm(),
                "security realm should be OmniAuth");
        return (OmniAuthSecurityRealm) j.jenkins.getSecurityRealm();
    }

    @Test
    @ConfiguredWithCode("jcasc-realm-sso-on.yaml")
    public void ssoEnabledTrue_importsAsActive() {
        OmniAuthSecurityRealm realm = realm();
        assertTrue(realm.isSsoEnabled(), "ssoEnabled: true should import");
        assertNotNull(realm.getEntraConfig(), "provider should import");
        assertTrue(realm.isSsoActive(), "SSO should be active");
        assertEquals("tenant-123", realm.getEntraConfig().getTenantId());
    }

    @Test
    @ConfiguredWithCode("jcasc-realm-sso-off.yaml")
    public void ssoEnabledFalse_importsAsDormantButPreserved() {
        OmniAuthSecurityRealm realm = realm();
        assertFalse(realm.isSsoEnabled(), "ssoEnabled: false should import");
        assertFalse(realm.isSsoActive(), "SSO must be inactive");
        assertNotNull(realm.getEntraConfig(), "provider settings must be preserved while dormant");
        assertEquals("tenant-123", realm.getEntraConfig().getTenantId());
    }
}
