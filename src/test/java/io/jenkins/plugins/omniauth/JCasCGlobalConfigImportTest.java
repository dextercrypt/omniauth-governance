package io.jenkins.plugins.omniauth;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies an {@code omniAuthGlobalConfig} section imports. The class mixes a setter-backed field
 * ({@code bruteForceLockoutMinutes}) with getter-only fields ({@code provisioningEnabled}); JCasC
 * applies both (the latter via field injection), so the plugin's global settings can be driven
 * from {@code jenkins.yaml}, not only the security realm / roles / assignments.
 */
public class JCasCGlobalConfigImportTest {

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    public void globalConfigSectionImports() throws Exception {
        ConfigurationAsCode.get().configure(
                getClass().getResource("jcasc-globalconfig.yaml").toString());
        OmniAuthGlobalConfig cfg = OmniAuthGlobalConfig.get();
        assertEquals(30, cfg.getBruteForceLockoutMinutes());
        assertTrue(cfg.isProvisioningEnabled());
    }
}
