/*
 * Break-glass TOTP reminder / enrollment page behaviour.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.totpReminderAssets"/>
 * Externalised from totpReminder.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * skipReminder / generateQr / verifyCode are referenced by on* handlers in the jelly, so they
 * stay global; they only touch the DOM when invoked, so no DOMContentLoaded guard is required.
 */

// ── Skip (reminder mode) ──────────────────────────────────────────────
function skipReminder() {
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r) { return r.json(); })
    .then(function(cd) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', base + '/manage/omniauth-management/totpReminderSkip', true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
      xhr.onload = function() {
        try {
          var r = JSON.parse(xhr.responseText);
          window.location.href = r.redirect || base + '/';
        } catch(e) { window.location.href = base + '/'; }
      };
      xhr.onerror = function() { window.location.href = base + '/'; };
      xhr.send('');
    });
}

// ── Inline enrollment (forced mode) ──────────────────────────────────
var pendingSecret = null;

function generateQr() {
  document.getElementById('step-generate').style.display = 'none';
  document.getElementById('step-loading').style.display  = 'block';
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r) { return r.json(); })
    .then(function(cd) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', base + '/manage/omniauth-management/breakGlassEnrollInit', true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
      xhr.onload = function() {
        document.getElementById('step-loading').style.display = 'none';
        try {
          var r = JSON.parse(xhr.responseText);
          pendingSecret = r.secret;
          document.getElementById('qr-img').src = r.qr;
          document.getElementById('secret-display').textContent = r.secret;
          document.getElementById('step-qr').style.display = 'block';
          document.getElementById('verify-code').focus();
        } catch(e) {
          document.getElementById('step-generate').style.display = 'block';
        }
      };
      xhr.onerror = function() {
        document.getElementById('step-loading').style.display  = 'none';
        document.getElementById('step-generate').style.display = 'block';
      };
      xhr.send('');
    });
}

function verifyCode() {
  var code       = document.getElementById('verify-code').value.trim();
  var deviceName = document.getElementById('device-name').value.trim() || 'My Device';
  var errEl      = document.getElementById('verify-err');
  if (!pendingSecret) { errEl.textContent = 'No secret loaded. Refresh and try again.'; errEl.style.display = 'block'; return; }
  if (code.length !== 6) { errEl.textContent = 'Enter the 6-digit code from your app.'; errEl.style.display = 'block'; return; }
  errEl.style.display = 'none';
  var btn = document.getElementById('btn-verify');
  btn.disabled = true; btn.textContent = 'Verifying…';
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r) { return r.json(); })
    .then(function(cd) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', base + '/manage/omniauth-management/breakGlassEnrollVerify', true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
      xhr.onload = function() {
        btn.disabled = false; btn.textContent = 'Confirm & Save';
        try {
          var r = JSON.parse(xhr.responseText);
          if (r.success) {
            document.getElementById('step-qr').style.display      = 'none';
            document.getElementById('step-success').style.display = 'block';
            requestAnimationFrame(function() {
              requestAnimationFrame(function() {
                document.getElementById('success-bar').style.width = '0%';
              });
            });
            setTimeout(function() { window.location.href = base + '/'; }, 1600);
          } else {
            errEl.textContent = r.error || 'Verification failed.';
            errEl.style.display = 'block';
            document.getElementById('verify-code').value = '';
            document.getElementById('verify-code').focus();
          }
        } catch(e) {
          errEl.textContent = 'Server error. Try again.'; errEl.style.display = 'block';
        }
      };
      xhr.onerror = function() {
        btn.disabled = false; btn.textContent = 'Confirm & Save';
        errEl.textContent = 'Network error.'; errEl.style.display = 'block';
      };
      xhr.send('secret='     + encodeURIComponent(pendingSecret) +
               '&code='      + encodeURIComponent(code)          +
               '&deviceName='+ encodeURIComponent(deviceName));
    });
}

document.addEventListener('keydown', function(e) {
  var vc = document.getElementById('verify-code');
  if (e.key === 'Enter' && vc && document.activeElement === vc) verifyCode();
});
