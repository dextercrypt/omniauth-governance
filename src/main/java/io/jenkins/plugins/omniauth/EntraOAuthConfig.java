package io.jenkins.plugins.omniauth;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IClientSecret;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.Collections;

/**
 * Holds Azure AD / Microsoft Entra configuration:
 * tenant ID, client ID, client secret, scopes, and optional group sync flag.
 */
public class EntraOAuthConfig extends AbstractDescribableImpl<EntraOAuthConfig> {

    private final String tenantId;
    private final String clientId;
    private final Secret clientSecret;
    private boolean enableGroupSync;
    private String allowedDomains = "";   // comma/space separated; empty = allow all

    @DataBoundConstructor
    public EntraOAuthConfig(String tenantId, String clientId, Secret clientSecret) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public Secret getClientSecret() {
        return clientSecret;
    }

    public boolean isEnableGroupSync() {
        return enableGroupSync;
    }

    @DataBoundSetter
    public void setEnableGroupSync(boolean enableGroupSync) {
        this.enableGroupSync = enableGroupSync;
    }

    /**
     * Comma/space-separated list of email domains permitted to sign in via SSO (e.g.
     * {@code example.com}). Empty means allow any domain. This is an authentication-time gate,
     * distinct from the provisioning portal's domain allowlist (which governs who may request
     * access). Enforced in the login callback against the Microsoft-verified UPN/email.
     */
    public String getAllowedDomains() {
        return allowedDomains != null ? allowedDomains : "";
    }

    @DataBoundSetter
    public void setAllowedDomains(String allowedDomains) {
        this.allowedDomains = allowedDomains != null ? allowedDomains.trim() : "";
    }

    /**
     * Returns the OAuth2 scopes to request. Always includes openid, profile, email.
     * Adds the Graph API scope whenever group information may be needed — i.e. group sync
     * is enabled OR any GROUP entity exists in Access Management. (Otherwise group-based
     * access would silently break: the token would lack the Graph scope, the /me/memberOf
     * call would 403, and every group-gated user would be rejected at login.)
     */
    public String getScope() {
        if (enableGroupSync || groupsConfigured()) {
            return "openid profile email https://graph.microsoft.com/.default";
        }
        return "openid profile email";
    }

    private static boolean groupsConfigured() {
        OmniAuthAssignmentConfig c = OmniAuthAssignmentConfig.get();
        return c != null && c.hasAnyGroups();
    }

    /**
     * Returns the Azure AD authority URL for this tenant.
     */
    public String getAuthority() {
        return "https://login.microsoftonline.com/" + tenantId;
    }

    @Extension
    @Symbol("entraConfig")
    public static class DescriptorImpl extends Descriptor<EntraOAuthConfig> {

        @Override
        public String getDisplayName() {
            return "Microsoft Entra (Azure AD) Configuration";
        }

        /**
         * The full redirect URI to register in Azure, built from the configured Jenkins URL — so it
         * can be copied straight into the App Registration. Falls back to a placeholder if the
         * Jenkins URL isn't set yet.
         */
        public String getRedirectUri() {
            String root = jenkins.model.Jenkins.get().getRootUrl();
            if (root == null) return "<set your Jenkins URL in Manage Jenkins → System>/omniauth/finishLogin";
            if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
            return root + "/omniauth/finishLogin";
        }

        public FormValidation doCheckTenantId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Tenant ID is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckClientId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Client ID is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckClientSecret(@QueryParameter Secret value) {
            if (value == null || Secret.toString(value).trim().isEmpty()) {
                return FormValidation.error("Client Secret is required");
            }
            return FormValidation.ok();
        }

        // Lucide-style stroke icons (matching the rest of the plugin's inline SVGs); stroke is
        // currentColor so each inherits the surrounding text colour (green / red).
        private static final String SVG_ATTRS =
                "width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor'"
              + " stroke-width='2' stroke-linecap='round' stroke-linejoin='round'"
              + " style='flex-shrink:0;margin-top:2px'";
        /** Party-popper (celebration) for a successful test. */
        private static final String ICON_CELEBRATE =
                "<svg " + SVG_ATTRS + ">"
              + "<path d='M5.8 11.3 2 22l10.7-3.79'/><path d='M4 3h.01'/><path d='M22 8h.01'/>"
              + "<path d='M15 2h.01'/><path d='M22 20h.01'/>"
              + "<path d='m22 2-2.24.75a2.9 2.9 0 0 0-1.96 3.12c.1.86-.57 1.63-1.45 1.63h-.38"
              + "c-.86 0-1.6.6-1.76 1.44L12 10'/>"
              + "<path d='m22 13-.82-.33c-.86-.34-1.82.2-1.98 1.11c-.11.7-.72 1.22-1.43 1.22H17'/>"
              + "<path d='m11 2 .33.82c.34.86-.2 1.82-1.11 1.98C9.52 4.9 9 5.52 9 6.23V7'/>"
              + "<path d='M11 13c1.93 1.93 2.83 4.17 2 5-.83.83-3.07-.07-5-2-1.93-1.93-2.83-4.17-2-5"
              + " .83-.83 3.07.07 5 2Z'/></svg>";
        /** Circle-x for a failed test. */
        private static final String ICON_X =
                "<svg " + SVG_ATTRS + ">"
              + "<circle cx='12' cy='12' r='10'/><path d='m15 9-6 6'/><path d='m9 9 6 6'/></svg>";

        private static String statusHtml(String colorVar, String icon, String text) {
            return "<span style=\"display:inline-flex;align-items:flex-start;gap:6px;"
                 + "color:" + colorVar + ";font-weight:600;\">"
                 + icon + "<span>" + text + "</span></span>";
        }

        /**
         * Validates the Entra credentials without saving them, so an admin gets immediate
         * feedback instead of discovering a typo at the first SSO sign-in. Performs a real
         * client-credentials token grant against the tenant for the Graph {@code .default}
         * scope: success proves the tenant ID, client ID and client secret are all correct and
         * the app registration can obtain tokens. Admin-only and POST-only (it reaches out to
         * Microsoft with the supplied secret).
         */
        @RequirePOST
        public FormValidation doTestConnection(@QueryParameter String tenantId,
                                               @QueryParameter String clientId,
                                               @QueryParameter Secret clientSecret) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (tenantId == null || tenantId.trim().isEmpty()
                    || clientId == null || clientId.trim().isEmpty()
                    || clientSecret == null || Secret.toString(clientSecret).trim().isEmpty()) {
                return FormValidation.error("Tenant ID, Client ID and Client Secret are all required to test.");
            }

            try {
                IClientSecret credential = ClientCredentialFactory.createFromSecret(clientSecret.getPlainText());
                ConfidentialClientApplication app = ConfidentialClientApplication.builder(clientId.trim(), credential)
                        .authority("https://login.microsoftonline.com/" + tenantId.trim())
                        .validateAuthority(true)
                        .build();
                ClientCredentialParameters params = ClientCredentialParameters
                        .builder(Collections.singleton("https://graph.microsoft.com/.default"))
                        .build();
                app.acquireToken(params).get();
                return FormValidation.okWithMarkup(statusHtml(
                        "var(--success-color, #2e7d32)", ICON_CELEBRATE,
                        "Connection successful — Microsoft Entra accepted these credentials."));
            } catch (Exception e) {
                // MSAL wraps the AADSTS error (bad secret, unknown client, wrong tenant, …) in the
                // cause; surface the clearest message we have so the admin knows which field to fix.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String msg = cause.getMessage();
                if (msg == null || msg.isBlank()) msg = e.toString();
                // msg is from Microsoft — escape it before embedding in markup.
                return FormValidation.errorWithMarkup(statusHtml(
                        "var(--error-color, #c0392b)", ICON_X,
                        "Connection failed: " + Util.escape(msg)));
            }
        }
    }
}
