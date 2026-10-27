/*
 * Break Glass (TOTP authenticator) section behaviour.
 * Loaded as a Jenkins adjunct:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.breakGlassSectionAssets"/>
 * Externalised from breakGlassSection.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * The enroll/verify/remove functions stay GLOBAL because the jelly's inline onclick= handlers
 * call them. Only the load-time addEventListener wiring is deferred until the DOM is parsed
 * (adjuncts load in <head>).
 */
var pendingSecret = null;
var pendingRemoveId = null;

function showAddDevice() {
  document.getElementById('enroll-panel').style.display = 'block';
  document.getElementById('enroll-panel').scrollIntoView({behavior:'smooth'});
  document.getElementById('device-name').focus();
}

function generateQr() {
  document.getElementById('step-generate').style.display = 'none';
  document.getElementById('step-loading').style.display = 'block';
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r){ return r.json(); })
    .then(function(cd){
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
          document.getElementById('step-generate').insertAdjacentHTML('afterbegin',
            '<div style="color:var(--error-color);font-size:0.85em;margin-bottom:8px;">Failed to generate QR code. Try again.</div>');
        }
      };
      xhr.onerror = function() {
        document.getElementById('step-loading').style.display = 'none';
        document.getElementById('step-generate').style.display = 'block';
      };
      xhr.send('');
    });
}

function verifyCode() {
  var code = document.getElementById('verify-code').value.trim();
  var deviceName = document.getElementById('device-name').value.trim() || 'My Device';
  var errEl = document.getElementById('verify-err');
  if (!pendingSecret) { errEl.textContent = 'No secret loaded. Refresh and try again.'; errEl.style.display = 'block'; return; }
  if (code.length !== 6) { errEl.textContent = 'Enter the 6-digit code from your app.'; errEl.style.display = 'block'; return; }
  errEl.style.display = 'none';
  var btn = document.getElementById('btn-verify');
  btn.disabled = true; btn.textContent = 'Verifying…';
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r){ return r.json(); })
    .then(function(cd){
      var xhr = new XMLHttpRequest();
      xhr.open('POST', base + '/manage/omniauth-management/breakGlassEnrollVerify', true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
      xhr.onload = function() {
        btn.disabled = false; btn.textContent = 'Confirm & Save';
        try {
          var r = JSON.parse(xhr.responseText);
          if (r.success) {
            document.getElementById('step-qr').style.display = 'none';
            document.getElementById('step-success').style.display = 'block';
          } else {
            errEl.textContent = r.error || 'Verification failed.';
            errEl.style.display = 'block';
            document.getElementById('verify-code').value = '';
            document.getElementById('verify-code').focus();
          }
        } catch(e) {
          errEl.textContent = 'Server error. Try again.';
          errEl.style.display = 'block';
        }
      };
      xhr.onerror = function() {
        btn.disabled = false; btn.textContent = 'Confirm & Save';
        errEl.textContent = 'Network error.'; errEl.style.display = 'block';
      };
      xhr.send('secret=' + encodeURIComponent(pendingSecret) +
               '&code=' + encodeURIComponent(code) +
               '&deviceName=' + encodeURIComponent(deviceName));
    });
}

function cancelEnrollment() {
  pendingSecret = null;
  document.getElementById('enroll-panel').style.display = 'none';
  document.getElementById('step-generate').style.display = 'block';
  document.getElementById('step-loading').style.display = 'none';
  document.getElementById('step-qr').style.display = 'none';
  document.getElementById('step-success').style.display = 'none';
  document.getElementById('verify-code').value = '';
  document.getElementById('device-name').value = '';
  document.getElementById('verify-err').style.display = 'none';
}

function removeDevice(deviceId, deviceName) {
  pendingRemoveId = deviceId;
  document.getElementById('remove-confirm-msg').textContent =
    'Remove "' + deviceName + '"? You will no longer be able to use this device for Emergency Override.';
  document.getElementById('remove-confirm-modal').style.display = 'flex';
}

(function () {
  function init() {
    // ── Remove-device buttons (per-row) ─────────────────────────────────
    document.querySelectorAll('.js-oau-remove-device').forEach(function(el) {
      el.addEventListener('click', function() {
        removeDevice(this.dataset.deviceId, this.dataset.deviceName);
      });
    });

    // ── Add/enroll device buttons ───────────────────────────────────────
    document.querySelectorAll('.js-oau-add-device').forEach(function(el) {
      el.addEventListener('click', showAddDevice);
    });

    // ── Enrollment panel controls ───────────────────────────────────────
    var genBtn = document.getElementById('btn-generate-qr');
    if (genBtn) genBtn.addEventListener('click', generateQr);

    document.querySelectorAll('.js-oau-cancel-enroll').forEach(function(el) {
      el.addEventListener('click', cancelEnrollment);
    });

    var verifyInput = document.getElementById('verify-code');
    if (verifyInput) {
      verifyInput.addEventListener('input', function() {
        this.value = this.value.replace(/[^0-9]/g, '');
      });
    }

    var verifyBtn = document.getElementById('btn-verify');
    if (verifyBtn) verifyBtn.addEventListener('click', verifyCode);

    var doneBtn = document.getElementById('btn-enroll-done');
    if (doneBtn) doneBtn.addEventListener('click', function() { window.location.reload(); });

    document.getElementById('remove-cancel-btn').addEventListener('click', function() {
      document.getElementById('remove-confirm-modal').style.display = 'none';
      pendingRemoveId = null;
    });

    document.getElementById('remove-confirm-btn').addEventListener('click', function() {
      if (!pendingRemoveId) return;
      var deviceId = pendingRemoveId;
      document.getElementById('remove-confirm-modal').style.display = 'none';
      pendingRemoveId = null;
      var base = window.location.pathname.split('/manage/')[0];
      fetch(base + '/crumbIssuer/api/json')
        .then(function(r){ return r.json(); })
        .then(function(cd){
          var xhr = new XMLHttpRequest();
          xhr.open('POST', base + '/manage/omniauth-management/breakGlassEnrollRemove', true);
          xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
          xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
          xhr.onload = function() { window.location.reload(); };
          xhr.send('deviceId=' + encodeURIComponent(deviceId));
        });
    });

    document.addEventListener('keydown', function(e) {
      if (e.key === 'Enter' && document.activeElement === document.getElementById('verify-code')) {
        verifyCode();
      }
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
