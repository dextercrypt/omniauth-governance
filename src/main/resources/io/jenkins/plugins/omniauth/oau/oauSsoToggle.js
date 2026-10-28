/*
 * Native-first SSO toggle for the OmniAuth security-realm config form.
 *
 * Shows/hides the single-sign-on provider settings off the "Connect a single sign-on provider"
 * checkbox (field="ssoEnabled", rendered by Jenkins as input name "_.ssoEnabled"). The settings
 * block stays in the DOM when hidden — only its visibility changes — so a save while SSO is off
 * still submits and preserves the stored provider settings. Include via:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.oau.oauSsoToggle"/>
 *
 * CSP-safe: external same-origin script, all wiring via addEventListener, no inline on* handlers.
 *
 * The realm config fragment is injected dynamically when the admin selects the OmniAuth radio on
 * "Configure Global Security", and Jenkins de-duplicates adjunct scripts per page — so this cannot
 * rely on running after the checkbox exists. It uses a delegated change listener plus a short-lived
 * observer so it wires up whenever (and however often) the fragment appears.
 */
(function () {
  "use strict";
  if (window.__oauSsoToggleInstalled) {
    return;
  }
  window.__oauSsoToggleInstalled = true;

  var CHECKBOX = 'input[type="checkbox"][name="_.ssoEnabled"]';
  var SETTINGS = '[data-oau-sso-settings]';

  // Apply one checkbox's state to the settings block that follows it in the same realm fragment.
  function sync(checkbox) {
    if (!checkbox) {
      return;
    }
    // The settings div is a later sibling within the same realm config block; fall back to the
    // whole document if the structure ever changes (correctness over precision — never leave it
    // hidden while enabled).
    var scope = checkbox.closest ? checkbox.closest("form, .jenkins-form, body") : null;
    var panel = (scope || document).querySelector(SETTINGS);
    if (!panel) {
      return;
    }
    panel.style.display = checkbox.checked ? "" : "none";
  }

  // Initial pass over every instance currently on the page.
  function syncAll() {
    var boxes = document.querySelectorAll(CHECKBOX);
    for (var i = 0; i < boxes.length; i++) {
      sync(boxes[i]);
    }
  }

  // Delegated change listener — survives dynamic injection and script de-duplication.
  document.addEventListener("change", function (e) {
    var t = e.target;
    if (t && t.matches && t.matches(CHECKBOX)) {
      sync(t);
    }
  });

  // Wire up now (in case the fragment is already present) and whenever new nodes appear.
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", syncAll);
  } else {
    syncAll();
  }

  try {
    var obs = new MutationObserver(function () {
      syncAll();
    });
    obs.observe(document.documentElement, { childList: true, subtree: true });
  } catch (err) {
    // MutationObserver unavailable — the delegated listener and initial pass still cover the
    // common cases; nothing else to do.
  }
})();
