/*
 * OmniAuth authorization-strategy config — permission-import confirmation gate.
 * Loaded as a Jenkins adjunct:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthAuthorizationStrategy.configAssets"/>
 * Externalised from config.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * Only included on the migration branch that renders the confirmation checkbox, but we still
 * guard on that checkbox's presence so the Save button is never disabled on other views.
 * Adjuncts load in <head>, so DOM access is deferred until the document is parsed.
 */
(function () {
  function init() {
    if (!document.getElementById('omniauth-migration-confirm')) { return; }
    var btn = document.querySelector('[name=Submit]') || document.querySelector('button.jenkins-button--primary');
    if (btn) { btn.disabled = true; btn.style.opacity = '0.5'; }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
