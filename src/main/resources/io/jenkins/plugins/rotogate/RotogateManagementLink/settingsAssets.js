/*
 * Settings page behaviour: SOS deactivation, login-branding logo upload/remove,
 * reset-to-defaults, cleanup field toggling, and cron validation.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.rotogate.RotogateManagementLink.settingsAssets"/>
 * Externalised from settings.jelly's inline <script> blocks for CSP compliance (Jenkins hosting).
 * The Jenkins root URL is read from the data-rooturl attribute on form[name="rgSettingsForm"]
 * instead of being inlined as EL. Functions called from inline on* handlers are
 * declared at top level (global). Adjuncts load in <head>, so DOM-access on load
 * is deferred until the document is parsed.
 */

// Jenkins root URL, sourced from the form's data-rooturl attribute (was inline EL).
function rgRoot() {
  var f = document.querySelector('form[name="rgSettingsForm"]');
  return f ? (f.getAttribute('data-rooturl') || '') : '';
}

function sosDeactivateNow(btn){
  btn.disabled = true; btn.textContent = 'Deactivating...';
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/manage/rotogate-management/sosDeactivate',{method:'POST',headers:crumb.wrap({'Content-Type':'application/x-www-form-urlencoded'}),body:''})
    .then(function(){ window.location.reload(); })
    .catch(function(){ btn.disabled=false; btn.textContent='Deactivate now'; });
}

// Localize the "oldest audit event" timestamp on the retention card.
document.addEventListener('DOMContentLoaded', function() {
  document.querySelectorAll('.rg-ts-abbr[data-ts]').forEach(function(el) {
    var ts = el.getAttribute('data-ts');
    if (!ts) return;
    try {
      el.textContent = new Date(ts).toLocaleDateString(undefined,
        { year:'numeric', month:'short', day:'numeric' });
    } catch(e) {}
  });
});

function rgUploadLoginLogo(input) {
  if (!input.files || !input.files[0]) return;
  var statusEl = document.getElementById('rgLogoUploadStatus');
  statusEl.style.color = '#888';
  statusEl.textContent = 'Uploading…';

  var fd = new FormData();
  fd.append('loginLogo', input.files[0]);

  var headers = crumb.wrap({});

  fetch(rgRoot() + '/manage/rotogate-management/uploadLoginLogo', {
    method: 'POST', credentials: 'same-origin', headers: headers, body: fd
  })
  .then(function(r) { return r.json(); })
  .then(function(d) {
    if (d.ok) {
      statusEl.style.color = 'var(--rg-success-fg)';
      statusEl.textContent = 'Uploaded.';
      var preview = document.getElementById('rgLogoPreview');
      var img     = document.getElementById('rgLogoPreviewImg');
      if (img) img.src = rgRoot() + '/securityRealm/loginLogo?t=' + Date.now();
      if (preview) preview.style.display = 'flex';
    } else {
      statusEl.style.color = 'var(--error-color)';
      statusEl.textContent = d.msg || 'Upload failed.';
    }
  })
  .catch(function(err) {
    statusEl.style.color = 'var(--error-color)';
    statusEl.textContent = 'Upload failed: ' + err.message;
  });
  input.value = '';
}

function rgRemoveLoginLogo(btn) {
  btn.disabled = true;
  var headers = crumb.wrap({'Content-Type': 'application/x-www-form-urlencoded'});

  fetch(rgRoot() + '/manage/rotogate-management/removeLoginLogo', {
    method: 'POST', credentials: 'same-origin', headers: headers, body: ''
  })
  .then(function(r) { return r.json(); })
  .then(function(d) {
    if (d.ok) {
      var preview = document.getElementById('rgLogoPreview');
      if (preview) preview.style.display = 'none';
      var statusEl = document.getElementById('rgLogoUploadStatus');
      if (statusEl) { statusEl.style.color = 'var(--rg-success-fg)'; statusEl.textContent = 'Logo removed.'; }
    }
  })
  .catch(function() {})
  .finally(function() { btn.disabled = false; });
}

function rgResetLoginBranding() {
  // Clear text inputs
  var heading  = document.querySelector('[name="loginHeading"]');
  var tabTitle = document.querySelector('[name="loginTabTitle"]');
  var announce = document.querySelector('[name="loginAnnouncementText"]');
  var footer   = document.querySelector('[name="loginFooterText"]');
  if (heading)  heading.value  = '';
  if (tabTitle) tabTitle.value = '';
  if (announce) announce.value = '';
  if (footer)   footer.value   = '';

  // Reset background to default (Rotogate animation)
  var bg = document.querySelector('[name="loginBackground"]');
  if (bg) bg.value = 'rotogate';

  // Reset butler to default (native Jenkins butler)
  var btl = document.querySelector('[name="loginButler"]');
  if (btl) btl.value = 'default';

  // Reset logo position to default (top left)
  var llp = document.querySelector('[name="loginLogoPosition"]');
  if (llp) llp.value = 'a';

  // Reset logo size to default (40px)
  var lls = document.querySelector('[name="loginLogoSize"]');
  if (lls) lls.value = '40';

  // Remove logo if one is uploaded
  var preview = document.getElementById('rgLogoPreview');
  if (preview && preview.style.display !== 'none') {
    var headers = crumb.wrap({'Content-Type': 'application/x-www-form-urlencoded'});
    fetch(rgRoot() + '/manage/rotogate-management/removeLoginLogo', {
      method: 'POST', credentials: 'same-origin', headers: headers, body: ''
    })
    .then(function(r) { return r.json(); })
    .then(function(d) { if (d.ok) preview.style.display = 'none'; })
    .catch(function() {});
  }

  var statusEl = document.getElementById('rgLogoUploadStatus');
  if (statusEl) {
    statusEl.style.color = '#888';
    statusEl.textContent = 'Defaults restored — click Save Settings to apply.';
  }
}

function toggleCleanupFields() {
  var enabled  = document.getElementById('cleanupEnabled').checked;
  var fields   = document.getElementById('cleanupFields');
  var dryRunCh = fields.querySelector('input[name="cleanupDryRun"]');
  if (!enabled && dryRunCh) dryRunCh.checked = true;
  fields.style.opacity       = enabled ? '1'  : '0.4';
  fields.style.pointerEvents = enabled ? ''   : 'none';
}

function validateCron(input) {
  var val   = input.value.trim();
  var errEl = document.getElementById('cronError');
  var okEl  = document.getElementById('cronOk');
  if (!val) { errEl.style.display = 'none'; okEl.style.display = 'none'; return; }
  var parts = val.split(/\s+/);
  var valid = parts.length === 5 && parts.every(function(p) {
    return /^(\*|(\d+)(-\d+)?|\*\/\d+)$/.test(p);
  });
  errEl.style.display = valid ? 'none' : 'block';
  okEl.style.display  = valid ? 'block' : 'none';
}

// "Keep me signed in" duration: keep the "Maximum: N <unit>" hint and the input's max in sync
// with the selected unit, and clamp the typed value into [1, per-unit max]. The hard ceiling
// (14 days, in seconds) is rendered onto the input as data-rg-max-seconds.
function rgUpdateRememberMax(clamp) {
  var input = document.getElementById('rgRememberValue');
  var unitSel = document.getElementById('rgRememberUnit');
  var out = document.getElementById('rgRememberMax');
  if (!input || !unitSel || !out) return;
  var maxSeconds = parseInt(input.getAttribute('data-rg-max-seconds'), 10) || 1209600;
  var unit = unitSel.value;
  var per   = unit === 'MINUTES' ? 60 : (unit === 'DAYS' ? 86400 : 3600);
  var label = unit === 'MINUTES' ? 'minutes' : (unit === 'DAYS' ? 'days' : 'hours');
  var maxForUnit = Math.floor(maxSeconds / per);
  input.max = maxForUnit;
  if (clamp) {
    var v = parseInt(input.value, 10);
    if (isNaN(v) || v < 1) input.value = 1;
    else if (v > maxForUnit) input.value = maxForUnit;
  }
  out.textContent = 'Maximum: ' + maxForUnit.toLocaleString() + ' ' + label + ' (14 days)';
}

// Initial sync — deferred until the DOM is ready (adjunct loads in <head>).
(function() {
  function init() {
    toggleCleanupFields();
    var cronInput = document.getElementById('cleanupCron');
    if (cronInput) validateCron(cronInput);

    // ── Event wiring (was inline on* handlers; moved here for CSP) ──────
    // Number inputs: blur on wheel so scrolling the page doesn't change values.
    document.querySelectorAll('form[name="rgSettingsForm"] input[type="number"]').forEach(function(el) {
      el.addEventListener('wheel', function() { this.blur(); });
    });

    var cleanupEnabled = document.getElementById('cleanupEnabled');
    if (cleanupEnabled) cleanupEnabled.addEventListener('change', toggleCleanupFields);

    if (cronInput) cronInput.addEventListener('input', function() { validateCron(this); });

    var sosBtn = document.getElementById('sosDeactivateBtn');
    if (sosBtn) sosBtn.addEventListener('click', function() { sosDeactivateNow(this); });

    var resetBtn = document.getElementById('rgResetBranding');
    if (resetBtn) resetBtn.addEventListener('click', rgResetLoginBranding);

    // "Keep me signed in" duration: default an empty field to 8 (hours), then sync max on load
    // and re-sync + clamp on unit/value change.
    var rmUnit = document.getElementById('rgRememberUnit');
    var rmVal = document.getElementById('rgRememberValue');
    if (rmVal && (rmVal.value === '' || parseInt(rmVal.value, 10) < 1)) rmVal.value = '8';
    if (rmUnit) rmUnit.addEventListener('change', function() { rgUpdateRememberMax(true); });
    if (rmVal) rmVal.addEventListener('change', function() { rgUpdateRememberMax(true); });
    rgUpdateRememberMax(false);

    document.querySelectorAll('.js-rg-remove-logo').forEach(function(btn) {
      btn.addEventListener('click', function() { rgRemoveLoginLogo(this); });
    });

    var logoFile = document.getElementById('rgLoginLogoFile');
    if (logoFile) logoFile.addEventListener('change', function() { rgUploadLoginLogo(this); });

    var uploadBtn = document.getElementById('rgUploadBtn');
    if (uploadBtn) uploadBtn.addEventListener('click', function() {
      var f = document.getElementById('rgLoginLogoFile');
      if (f) f.click();
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
