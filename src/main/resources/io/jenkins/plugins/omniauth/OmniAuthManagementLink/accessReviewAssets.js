/*
 * Access-review page behaviour: keep-all / revoke confirmations and row hover.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.accessReviewAssets"/>
 * Externalised from accessReview.jelly's inline on* handlers for CSP compliance (Jenkins hosting).
 * Per-row dynamic confirm messages are carried on data-confirm attributes (no EL in this cached adjunct).
 */
(function() {
  function init() {
    // Keep-All — confirm guards submit
    document.querySelectorAll('.js-ar-keepall').forEach(function(el) {
      el.addEventListener('click', function(e) { if (!confirm(this.dataset.confirm)) e.preventDefault(); });
    });
    // Row hover highlight (preserves former onmouseover/onmouseout behaviour)
    document.querySelectorAll('.js-ar-row').forEach(function(el) {
      el.addEventListener('mouseover', function() { this.style.background = 'var(--oau-neutral-bg)'; });
      el.addEventListener('mouseout', function() { this.style.background = ''; });
    });
    // Revoke-from-review — confirm guards submit
    document.querySelectorAll('.js-ar-revoke').forEach(function(el) {
      el.addEventListener('submit', function(e) { if (!confirm(this.dataset.confirm)) e.preventDefault(); });
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
