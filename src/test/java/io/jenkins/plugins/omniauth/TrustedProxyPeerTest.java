package io.jenkins.plugins.omniauth;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for {@link OmniAuthAuditLog#isTrustedProxyPeer(String)} — the gate that
 * decides whether an X-Forwarded-For header may be believed. It must return {@code true}
 * ONLY when the direct TCP peer is a loopback / private / link-local / IPv6-ULA address
 * (a reverse proxy we control), and {@code false} for any public address or garbage input.
 *
 * <p>This locks down the just-fixed MEDIUM: without the gate, an authenticated user on a
 * public network could forge the IP recorded against their own audit events by setting XFF.
 *
 * <p>No JenkinsRule — the method is a package-private static pure function. Inputs that
 * contain a ':' are parsed as IPv6 literals and fail fast on error (no DNS); garbage inputs
 * are therefore chosen to contain ':' so the test never performs a network lookup.
 */
public class TrustedProxyPeerTest {

    private static void assertTrusted(String addr) {
        assertTrue("expected TRUSTED (private/loopback) peer: " + addr,
                OmniAuthAuditLog.isTrustedProxyPeer(addr));
    }

    private static void assertUntrusted(String addr) {
        assertFalse("expected UNTRUSTED peer: " + addr,
                OmniAuthAuditLog.isTrustedProxyPeer(addr));
    }

    // ── Loopback ──────────────────────────────────────────────────────────────

    @Test public void ipv4Loopback_isTrusted()          { assertTrusted("127.0.0.1"); }
    @Test public void ipv4LoopbackRange_isTrusted()      { assertTrusted("127.1.2.3"); }
    @Test public void ipv6Loopback_isTrusted()           { assertTrusted("::1"); }
    @Test public void ipv6LoopbackExpanded_isTrusted()   { assertTrusted("0:0:0:0:0:0:0:1"); }
    @Test public void ipv6LoopbackBracketed_isTrusted()  { assertTrusted("[::1]"); }

    // ── Any-local (wildcard) ────────────────────────────────────────────────────

    @Test public void ipv4AnyLocal_isTrusted()           { assertTrusted("0.0.0.0"); }

    // ── IPv4 private / site-local ranges ────────────────────────────────────────

    @Test public void private10_low_isTrusted()          { assertTrusted("10.0.0.1"); }
    @Test public void private10_high_isTrusted()          { assertTrusted("10.255.255.255"); }
    @Test public void private192_168_isTrusted()          { assertTrusted("192.168.1.1"); }
    @Test public void private172_16_low_isTrusted()       { assertTrusted("172.16.0.1"); }
    @Test public void private172_16_high_isTrusted()      { assertTrusted("172.31.255.254"); }

    // ── Link-local ──────────────────────────────────────────────────────────────

    @Test public void ipv4LinkLocal_isTrusted()           { assertTrusted("169.254.10.10"); }
    @Test public void ipv6LinkLocal_isTrusted()           { assertTrusted("fe80::1"); }

    // ── IPv6 unique-local (fc00::/7) ────────────────────────────────────────────

    @Test public void ipv6UlaFc00_isTrusted()             { assertTrusted("fc00::1"); }
    @Test public void ipv6UlaFd_isTrusted()               { assertTrusted("fd12:3456:789a::1"); }

    // ── Public addresses must NOT be trusted ────────────────────────────────────

    @Test public void publicGoogleDns_isUntrusted()       { assertUntrusted("8.8.8.8"); }
    @Test public void publicCloudflareDns_isUntrusted()   { assertUntrusted("1.1.1.1"); }
    @Test public void publicArbitrary_isUntrusted()       { assertUntrusted("203.0.113.5"); }
    // Just outside the 172.16.0.0/12 block — must be treated as public.
    @Test public void justAbove172Block_isUntrusted()     { assertUntrusted("172.32.0.1"); }
    @Test public void justBelow172Block_isUntrusted()     { assertUntrusted("172.15.255.255"); }
    @Test public void publicIpv6Google_isUntrusted()      { assertUntrusted("2001:4860:4860::8888"); }
    @Test public void publicIpv6Cloudflare_isUntrusted()  { assertUntrusted("2606:4700:4700::1111"); }

    // ── Null / blank / garbage must fail closed to UNTRUSTED (no XFF trust) ──────

    @Test public void nullAddr_isUntrusted()              { assertUntrusted(null); }
    @Test public void emptyAddr_isUntrusted()             { assertUntrusted(""); }
    @Test public void blankAddr_isUntrusted()             { assertUntrusted("   "); }
    // Contains ':' → parsed as an (invalid) IPv6 literal and fails fast, no DNS lookup.
    @Test public void garbageColon_isUntrusted()          { assertUntrusted("not:a:valid:addr"); }
    @Test public void garbageBadHex_isUntrusted()         { assertUntrusted("zzzz::zzzz"); }
    @Test public void garbageMalformedV6_isUntrusted()    { assertUntrusted("::1::2::3"); }
}
