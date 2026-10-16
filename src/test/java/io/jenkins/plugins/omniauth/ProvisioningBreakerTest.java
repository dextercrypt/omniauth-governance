package io.jenkins.plugins.omniauth;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ProvisioningBreaker}, the global flood guard for the anonymous native
 * provisioning form. State is static/process-local, so it is reset before and after each test.
 *
 * <p>Uses a real {@link OmniAuthGlobalConfig} (obtained via JenkinsRule) with a small breaker
 * threshold so the breaker can be tripped without issuing a hundred attempts. We deliberately do
 * NOT mock the config: it is a Jenkins {@code GlobalConfiguration} (a deep Describable hierarchy),
 * and the inline mock maker cannot reliably instrument it under recent JDKs — the real singleton
 * with its setters is both simpler and faithful to production.
 */
public class ProvisioningBreakerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private OmniAuthGlobalConfig cfg;

    @Before
    public void setUp() {
        ProvisioningBreaker.reset();
        cfg = OmniAuthGlobalConfig.get();
        cfg.setProvisioningBreakerMax(3);
        cfg.setProvisioningBreakerWindowMin(10);
        cfg.setProvisioningBreakerCooldownMin(30);
    }

    @After
    public void tearDown() {
        ProvisioningBreaker.reset();
    }

    @Test
    public void notTrippedInitially() {
        assertFalse(ProvisioningBreaker.isTripped());
        assertTrue(ProvisioningBreaker.cooldownSecondsRemaining() == 0);
    }

    @Test
    public void acceptsUpToThreshold() {
        // max = 3: the first three attempts are accepted (size 1,2,3, not > max).
        assertTrue(ProvisioningBreaker.tryAcquire(cfg));
        assertTrue(ProvisioningBreaker.tryAcquire(cfg));
        assertTrue(ProvisioningBreaker.tryAcquire(cfg));
        assertFalse(ProvisioningBreaker.isTripped());
    }

    @Test
    public void tripsOnceThresholdExceeded() {
        for (int i = 0; i < 3; i++) {
            assertTrue(ProvisioningBreaker.tryAcquire(cfg));
        }
        // The 4th attempt exceeds max -> breaker trips and this attempt is rejected.
        assertFalse(ProvisioningBreaker.tryAcquire(cfg));
        assertTrue(ProvisioningBreaker.isTripped());
    }

    @Test
    public void whileTrippedFurtherAcquiresRejected() {
        for (int i = 0; i < 4; i++) {
            ProvisioningBreaker.tryAcquire(cfg);
        }
        assertTrue(ProvisioningBreaker.isTripped());
        assertFalse(ProvisioningBreaker.tryAcquire(cfg));
        assertFalse(ProvisioningBreaker.tryAcquire(cfg));
    }

    @Test
    public void cooldownRemainingPositiveAfterTrip() {
        for (int i = 0; i < 4; i++) {
            ProvisioningBreaker.tryAcquire(cfg);
        }
        long rem = ProvisioningBreaker.cooldownSecondsRemaining();
        // 30 min cooldown -> up to 1800s; must be positive and within the configured ceiling.
        assertTrue("cooldown should be positive", rem > 0);
        assertTrue("cooldown within 30 min", rem <= 30 * 60);
    }

    @Test
    public void resetClearsTrippedState() {
        for (int i = 0; i < 4; i++) {
            ProvisioningBreaker.tryAcquire(cfg);
        }
        assertTrue(ProvisioningBreaker.isTripped());
        ProvisioningBreaker.reset();
        assertFalse(ProvisioningBreaker.isTripped());
        assertTrue(ProvisioningBreaker.cooldownSecondsRemaining() == 0);
        // After reset the window is clear, so intake flows again.
        assertTrue(ProvisioningBreaker.tryAcquire(cfg));
    }

    @Test
    public void nullConfigUsesDefaultThresholdOfHundred() {
        // With defaults (max 100), 100 attempts are accepted and the 101st trips.
        for (int i = 0; i < 100; i++) {
            assertTrue("attempt " + i, ProvisioningBreaker.tryAcquire(null));
        }
        assertFalse(ProvisioningBreaker.tryAcquire(null));
        assertTrue(ProvisioningBreaker.isTripped());
    }
}
