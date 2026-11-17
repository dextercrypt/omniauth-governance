package io.jenkins.plugins.omniauth;

import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link OmniAuthSsoProvider} extension point: Entra is registered as a provider (so the
 * realm's provider dropdown offers it), and the realm holds the provider polymorphically while still
 * narrowing it for the Entra-specific login path.
 */
@WithJenkins
public class OmniAuthSsoProviderTest {

    @Test
    public void entraIsRegisteredAsAProvider(JenkinsRule j) {
        boolean hasEntra = OmniAuthSsoProvider.all().stream()
                .anyMatch(d -> d.clazz.equals(EntraOAuthConfig.class));
        assertTrue(hasEntra, "Entra should be registered as an OmniAuthSsoProvider");
    }

    @Test
    public void realmHoldsProviderPolymorphicallyAndNarrows(JenkinsRule j) {
        EntraOAuthConfig entra = new EntraOAuthConfig("tenant", "client", Secret.fromString("secret"));
        OmniAuthSecurityRealm realm = new OmniAuthSecurityRealm(false, entra);
        assertSame(entra, realm.getProvider(), "provider is kept as the extension-point type");
        assertSame(entra, realm.getEntraConfig(), "getEntraConfig narrows the provider for Entra login");
        assertTrue(realm.getProvider().isConfigured());

        OmniAuthSecurityRealm none = new OmniAuthSecurityRealm(false, null);
        assertNull(none.getProvider());
        assertNull(none.getEntraConfig(), "narrowing a null provider must not crash");
    }
}
