package io.jenkins.plugins.omniauth;

import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * Extension point for a single sign-on provider that {@link OmniAuthSecurityRealm} can delegate to.
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

    /** All registered SSO provider descriptors — backs the provider selector on the realm config. */
    public static DescriptorExtensionList<OmniAuthSsoProvider, Descriptor<OmniAuthSsoProvider>> all() {
        return Jenkins.get().getDescriptorList(OmniAuthSsoProvider.class);
    }
}
