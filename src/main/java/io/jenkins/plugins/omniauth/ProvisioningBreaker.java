package io.jenkins.plugins.omniauth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * Circuit breaker for the <em>anonymous native</em> provisioning request form.
 *
 * <p>Native requests are unauthenticated, so per-IP limits are unreliable (NAT) and per-email
 * limits are spoofable. Instead this is a single global breaker: if more than
 * {@code breakerMax} requests arrive within the last {@code breakerWindowMin} minutes, native
 * intake is paused for {@code breakerCooldownMin} minutes ("try again later") and an admin is
 * alerted. Approval remains the real backstop — the breaker only protects the queue from floods.
 *
 * <p>Entra requests are identity-proven and per-user capped elsewhere, so they are never gated
 * by this breaker.
 *
 * <p>State is in-memory and process-local (resets on restart), which is acceptable for a
 * flood guard whose worst case is a temporarily unavailable optional form.
 */
final class ProvisioningBreaker {

    private static final Logger LOGGER = Logger.getLogger(ProvisioningBreaker.class.getName());

    /** Timestamps (epoch ms) of accepted native requests, pruned to the window on each call. */
    private static final Deque<Long> HITS = new ArrayDeque<>();
    /** Epoch ms until which native intake is paused; 0 = not tripped. */
    private static long trippedUntil = 0L;

    private ProvisioningBreaker() {}

    /** True if native intake is currently paused. */
    static synchronized boolean isTripped() {
        return System.currentTimeMillis() < trippedUntil;
    }

    /** Seconds remaining in the current cooldown, or 0 if not tripped. */
    static synchronized long cooldownSecondsRemaining() {
        long rem = (trippedUntil - System.currentTimeMillis()) / 1000L;
        return Math.max(0, rem);
    }

    /**
     * Record a native request attempt and decide whether to accept it.
     *
     * @return true if the request may proceed; false if the breaker is (or just became) tripped.
     */
    static synchronized boolean tryAcquire(OmniAuthGlobalConfig cfg) {
        long now = System.currentTimeMillis();

        if (now < trippedUntil) {
            return false; // still in cooldown
        }

        int    max        = cfg != null ? cfg.getProvisioningBreakerMax()         : 100;
        long   windowMs    = (cfg != null ? cfg.getProvisioningBreakerWindowMin()   : 10)  * 60_000L;
        long   cooldownMs   = (cfg != null ? cfg.getProvisioningBreakerCooldownMin() : 30) * 60_000L;

        // Prune hits outside the rolling window.
        long cutoff = now - windowMs;
        while (!HITS.isEmpty() && HITS.peekFirst() < cutoff) {
            HITS.pollFirst();
        }

        HITS.addLast(now);

        if (HITS.size() > max) {
            trippedUntil = now + cooldownMs;
            HITS.clear();
            LOGGER.warning("Provisioning portal circuit breaker tripped: more than " + max
                    + " native requests within " + (windowMs / 60_000L) + " min. Native intake paused for "
                    + (cooldownMs / 60_000L) + " min.");
            return false;
        }
        return true;
    }

    /** Admin manual reset — clears the cooldown and the window. */
    static synchronized void reset() {
        trippedUntil = 0L;
        HITS.clear();
    }
}
