/*
 * Settings page behaviour: SOS deactivation, login-branding logo upload/remove,
 * reset-to-defaults, cleanup field toggling, and cron validation.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.settingsAssets"/>
 * Externalised from settings.jelly's inline <script> blocks for CSP compliance (Jenkins hosting).
 * The Jenkins root URL is read from the data-rooturl attribute on #oauSettingsForm
 * instead of being inlined as EL. Functions called from inline on* handlers are
 * declared at top level (global). Adjuncts load in <head>, so DOM-access on load
 * is deferred until the document is parsed.
 */

// Jenkins root URL, sourced from the form's data-rooturl attribute (was inline EL).
function oauRoot() {
  var f = document.getElementById('oauSettingsForm');
  return f ? (f.getAttribute('data-rooturl') || '') : '';
}

function sosDeactivateNow(btn){
  btn.disabled = true; btn.textContent = 'Deactivating...';
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r){return r.json();})
    .then(function(cd){
      var h = {'Content-Type':'application/x-www-form-urlencoded'}; h[cd.crumbRequestField]=cd.crumb;
      return fetch(base + '/manage/omniauth-management/sosDeactivate',{method:'POST',headers:h,body:''});
    })
    .then(function(){ window.location.reload(); })
    .catch(function(){ btn.disabled=false; btn.textContent='Deactivate now'; });
}

// Localize the "oldest audit event" timestamp on the retention card.
document.addEventListener('DOMContentLoaded', function() {
  document.querySelectorAll('.oau-ts-abbr[data-ts]').forEach(function(el) {
    var ts = el.getAttribute('data-ts');
    if (!ts) return;
    try {
      el.textContent = new Date(ts).toLocaleDateString(undefined,
        { year:'numeric', month:'short', day:'numeric' });
    } catch(e) {}
  });
});

function oauUploadLoginLogo(input) {
  if (!input.files || !input.files[0]) return;
  var statusEl = document.getElementById('oauLogoUploadStatus');
  statusEl.style.color = '#888';
  statusEl.textContent = 'Uploading…';

  var fd = new FormData();
  fd.append('loginLogo', input.files[0]);

  var head = document.querySelector('head');
  var cf = head ? head.getAttribute('data-crumb-header') : '';
  var cv = head ? head.getAttribute('data-crumb-value')  : '';
  var headers = {};
  if (cf) headers[cf] = cv;

  fetch(oauRoot() + '/manage/omniauth-management/uploadLoginLogo', {
    method: 'POST', credentials: 'same-origin', headers: headers, body: fd
  })
  .then(function(r) { return r.json(); })
  .then(function(d) {
    if (d.ok) {
      statusEl.style.color = 'var(--oau-success-fg)';
      statusEl.textContent = 'Uploaded.';
      var preview = document.getElementById('oauLogoPreview');
      var img     = document.getElementById('oauLogoPreviewImg');
      if (img) img.src = oauRoot() + '/securityRealm/loginLogo?t=' + Date.now();
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

function oauRemoveLoginLogo(btn) {
  btn.disabled = true;
  var head = document.querySelector('head');
  var cf = head ? head.getAttribute('data-crumb-header') : '';
  var cv = head ? head.getAttribute('data-crumb-value')  : '';
  var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
  if (cf) headers[cf] = cv;

  fetch(oauRoot() + '/manage/omniauth-management/removeLoginLogo', {
    method: 'POST', credentials: 'same-origin', headers: headers, body: ''
  })
  .then(function(r) { return r.json(); })
  .then(function(d) {
    if (d.ok) {
      var preview = document.getElementById('oauLogoPreview');
      if (preview) preview.style.display = 'none';
      var statusEl = document.getElementById('oauLogoUploadStatus');
      if (statusEl) { statusEl.style.color = 'var(--oau-success-fg)'; statusEl.textContent = 'Logo removed.'; }
    }
  })
  .catch(function() {})
  .finally(function() { btn.disabled = false; });
}

function oauResetLoginBranding() {
  // Clear text inputs
  var heading  = document.querySelector('[name="loginHeading"]');
  var tabTitle = document.querySelector('[name="loginTabTitle"]');
  var announce = document.querySelector('[name="loginAnnouncementText"]');
  var footer   = document.querySelector('[name="loginFooterText"]');
  if (heading)  heading.value  = '';
  if (tabTitle) tabTitle.value = '';
  if (announce) announce.value = '';
  if (footer)   footer.value   = '';

  // Reset background to default (OmniAuth animation)
  var bg = document.querySelector('[name="loginBackground"]');
  if (bg) bg.value = 'omniauth';

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
  var preview = document.getElementById('oauLogoPreview');
  if (preview && preview.style.display !== 'none') {
    var head = document.querySelector('head');
    var cf = head ? head.getAttribute('data-crumb-header') : '';
    var cv = head ? head.getAttribute('data-crumb-value')  : '';
    var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
    if (cf) headers[cf] = cv;
    fetch(oauRoot() + '/manage/omniauth-management/removeLoginLogo', {
      method: 'POST', credentials: 'same-origin', headers: headers, body: ''
    })
    .then(function(r) { return r.json(); })
    .then(function(d) { if (d.ok) preview.style.display = 'none'; })
    .catch(function() {});
  }

  var statusEl = document.getElementById('oauLogoUploadStatus');
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

// Initial sync — deferred until the DOM is ready (adjunct loads in <head>).
(function() {
  function init() {
    toggleCleanupFields();
    var cronInput = document.getElementById('cleanupCron');
    if (cronInput) validateCron(cronInput);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
