/*
 * Set-password page behaviour: strength meter, match check, show/hide password toggles.
 * Loaded as a Jenkins adjunct:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthRootAction.changePasswordAssets"/>
 * Externalised from changePassword.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * checkStrength/validateForm stay GLOBAL because the jelly's inline oninput=/onsubmit= handlers
 * call them. Load-time addEventListener wiring is deferred until the DOM is parsed
 * (adjuncts load in <head>).
 */
function checkStrength(val) {
  var fill  = document.getElementById('strengthFill');
  var label = document.getElementById('strengthLabel');
  var score = 0;
  if (val.length >= 8)  score++;
  if (val.length >= 12) score++;
  if (/[A-Z]/.test(val)) score++;
  if (/[0-9]/.test(val)) score++;
  if (/[^A-Za-z0-9]/.test(val)) score++;
  var colors = ['#e53e3e','#e53e3e','#dd6b20','#38a169','#22c55e'];
  var labels = ['','Too weak','Weak','Good','Strong','Very strong'];
  fill.style.width = (score / 5 * 100) + '%';
  fill.style.background = colors[Math.max(0, score - 1)] || '#1d2a44';
  label.textContent = val.length > 0 ? (labels[score] || '') : '';
  label.style.color = colors[Math.max(0, score - 1)] || '#8ea3c4';
}

function validateForm() {
  var p1 = document.getElementById('newPassword').value;
  var p2 = document.getElementById('confirmPassword').value;
  if (p1.length < 8) { alert('Password must be at least 8 characters.'); return false; }
  if (p1 !== p2) { alert('Passwords do not match.'); return false; }
  return true;
}

(function () {
  function init() {
    document.getElementById('confirmPassword').addEventListener('input', function () {
      var msg = document.getElementById('matchMsg');
      var p1  = document.getElementById('newPassword').value;
      if (!this.value) { msg.textContent = ''; return; }
      if (this.value === p1) {
        msg.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px;margin-right:4px;"><path d="M20 6 9 17l-5-5"/></svg>Passwords match'; msg.style.color = '#22c55e';
      } else {
        msg.innerHTML = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px;margin-right:4px;"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>Passwords do not match'; msg.style.color = '#fca5a5';
      }
    });

    var toggles = document.querySelectorAll('.pw-toggle');
    for (var i = 0; i < toggles.length; i++) {
      toggles[i].addEventListener('click', function () {
        var input = document.getElementById(this.getAttribute('data-target'));
        if (!input) { return; }
        var reveal = input.type === 'password';
        input.type = reveal ? 'text' : 'password';
        this.classList.toggle('revealed', reveal);
        this.setAttribute('aria-label', reveal ? 'Hide password' : 'Show password');
        input.focus();
      });
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
