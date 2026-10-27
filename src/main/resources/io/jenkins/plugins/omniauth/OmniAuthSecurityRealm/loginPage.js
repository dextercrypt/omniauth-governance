/*
 * Login page behaviour: show/hide password toggle, caps-lock warning, and the
 * SSO button loading state.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthSecurityRealm.loginPage"/>
 * Externalised from login.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * Adjuncts load in <head>, so defer DOM access until the document is parsed.
 */
(function () {
  function init() {
    var pw = document.getElementById('j_password');
    var btn = document.getElementById('togglePw');
    var caps = document.getElementById('capsWarn');
    if (pw && btn) {
      btn.addEventListener('click', function () {
        var reveal = pw.type === 'password';
        pw.type = reveal ? 'text' : 'password';
        btn.classList.toggle('revealed', reveal);
        var label = reveal ? 'Hide password' : 'Show password';
        btn.setAttribute('aria-label', label);
        btn.setAttribute('title', label);
        pw.focus();
      });
    }
    if (pw && caps) {
      var update = function (e) {
        var on = false;
        if (e && typeof e.getModifierState === 'function') {
          on = e.getModifierState('CapsLock');
        }
        caps.classList.toggle('show', on);
      };
      pw.addEventListener('keydown', update);
      pw.addEventListener('keyup', update);
      pw.addEventListener('blur', function () { caps.classList.remove('show'); });
    }
    // Username uses the browser's native autocomplete="username" suggestions.
    // We intentionally do NOT force-prefill the field ourselves.

    // SSO button loading state: on click, show a spinner + "Redirecting…"
    // label so the redirect gap doesn't look dead. The navigation still
    // proceeds (we don't preventDefault). A safety timeout re-enables the
    // button if the redirect stalls without unloading the page.
    var sso = document.getElementById('ssoBtn');
    var ssoLabel = document.getElementById('ssoLabel');
    if (sso) {
      sso.addEventListener('click', function () {
        if (sso.classList.contains('loading')) { return; }
        var orig = ssoLabel ? ssoLabel.textContent : '';
        var loading = sso.getAttribute('data-loading-label');
        sso.classList.add('loading');
        sso.setAttribute('aria-busy', 'true');
        if (ssoLabel && loading) { ssoLabel.textContent = loading; }
        window.setTimeout(function () {
          sso.classList.remove('loading');
          sso.removeAttribute('aria-busy');
          if (ssoLabel) { ssoLabel.textContent = orig; }
        }, 8000);
      });
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
