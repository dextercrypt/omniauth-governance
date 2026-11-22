package io.jenkins.plugins.omniauth.sso;

import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.omniauth.OmniAuthSecurityRealm;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Extension point for a single sign-on provider that {@link io.jenkins.plugins.omniauth.OmniAuthSecurityRealm} can delegate to.
 *
 * <p>Today the only implementation is {@link EntraOAuthConfig} (Microsoft Entra / Azure AD), but
 * modelling providers as an extension point means a new provider (Okta, Google, a generic OIDC/SAML
 * one, …) is added by writing a new subclass with its own {@code config.jelly} — the realm's
 * provider dropdown ({@code <f:dropdownDescriptorSelector field="ssoProvider"/>}) then offers it and
 * shows its configuration form, with no change to the realm itself.
 *
 * <p>A provider is a {@link AbstractDescribableImpl} so it carries its own descriptor + view; the
 * descriptor's display name is what the dropdown shows.
 */
public abstract class OmniAuthSsoProvider extends AbstractDescribableImpl<OmniAuthSsoProvider>
        implements ExtensionPoint {

    /**
     * True when this provider holds enough configuration to actually perform a sign-in. The realm
     * keys "SSO is available" off this (together with the admin's on/off toggle), so a half-filled
     * provider never advertises SSO on the login page.
     */
    public abstract boolean isConfigured();

    /**
     * Stable key for this provider, used by {@code login.jelly} to pick the sign-in button's logo
     * (e.g. {@code "microsoft"}). A provider owns its own identity here so the login markup never
     * needs a per-provider branch maintained elsewhere.
     */
    public abstract String getId();

    /** Text for the sign-in button on the login page, e.g. {@code "Sign in with Microsoft"}. */
    public abstract String getButtonLabel();

    /** Text shown on the button while redirecting to the provider, e.g. {@code "Redirecting to Microsoft…"}. */
    public abstract String getLoadingLabel();

    /**
     * True when group memberships should be synced from the provider at login. The realm consults
     * this instead of reaching into a specific provider's config, so group handling stays
     * provider-agnostic.
     */
    public abstract boolean isGroupSyncEnabled();

    /**
     * Step 1 of sign-in: begin the provider's login (e.g. redirect to its authorization endpoint).
     * The realm forwards {@code /omniauth/commenceLogin} here so the provider owns its own login
     * sequence and the realm needs no per-provider branching. The {@code realm} gives access to the
     * shared helpers (token client, redirect URI, pre-provisioning gate, user creation).
     */
    public abstract HttpResponse startLogin(OmniAuthSecurityRealm realm, StaplerRequest2 req) throws Exception;

    /**
     * Step 2 of sign-in: complete the provider's login from the callback (e.g. exchange the code,
     * validate the token, provision the user and establish the session). The realm forwards
     * {@code /omniauth/finishLogin} here.
     */
    public abstract HttpResponse finishLogin(OmniAuthSecurityRealm realm, StaplerRequest2 req) throws Exception;

    /** All registered SSO provider descriptors — backs the provider selector on the realm config. */
    public static DescriptorExtensionList<OmniAuthSsoProvider, Descriptor<OmniAuthSsoProvider>> all() {
        return Jenkins.get().getDescriptorList(OmniAuthSsoProvider.class);
    }
}
