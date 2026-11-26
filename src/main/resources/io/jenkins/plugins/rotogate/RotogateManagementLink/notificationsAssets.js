/*
 * Notifications settings page behaviour: channel/section toggles, event-checkbox
 * init, test-send calls, and the email-preview modal.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.rotogate.RotogateManagementLink.notificationsAssets"/>
 * Externalised from notifications.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * The dynamic values (Jenkins root URL + saved event lists) are read from a
 * <script type="application/json" id="rgNotificationsData"> island in the page, since a
 * static adjunct is cached and cannot contain Jelly EL expressions.
 * Functions stay global because the jelly's onclick handlers call them.
 */

// Populated on init() from the JSON data island.
var rgRootURL    = '';
var rgSmtpEvents  = '';
var rgSlackEvents = '';
var rgTeamsEvents = '';

function rgInitEventCheckboxes(channelName, savedStr) {
  var saved = savedStr ? savedStr.split(",").map(function(s){return s.trim();}).filter(Boolean) : [];
  var boxes = document.querySelectorAll('[name="' + channelName + '"]');
  boxes.forEach(function(cb) {
    cb.checked = saved.indexOf(cb.value) >= 0;
  });
}

function rgToggleMaster() {
  var on = document.getElementById('notificationsEnabled').checked;
  ['rgChannelsSection', 'rgLogoSection'].forEach(function(id) {
    var el = document.getElementById(id);
    if (el) {
      el.style.opacity       = on ? '1'  : '0.4';
      el.style.pointerEvents = on ? ''   : 'none';
    }
  });
}

function rgToggleWebhook(checkboxId, configId) {
  var on  = document.getElementById(checkboxId).checked;
  var cfg = document.getElementById(configId);
  cfg.style.opacity       = on ? '1'  : '0.4';
  cfg.style.pointerEvents = on ? ''   : 'none';
}

function rgTestWebhook(btn, urlField, endpoint, resultId) {
  var resultEl = document.getElementById(resultId);
  resultEl.style.color = '#888';
  resultEl.textContent = 'Sending…';
  btn.disabled = true;

  var form = btn.closest('form');
  var url  = form.querySelector('[name="' + urlField + '"]').value;
  var headers = crumb.wrap({'Content-Type': 'application/x-www-form-urlencoded'});

  fetch(rgRootURL + '/manage/rotogate-management/' + endpoint, {
    method: 'POST', credentials: 'same-origin', headers: headers,
    body: new URLSearchParams([[urlField, url]])
  })
    .then(function(r) { return r.text(); })
    .then(function(t) {
      var d = JSON.parse(t);
      resultEl.style.color = d.ok ? 'var(--rg-success-fg)' : 'var(--error-color)';
      resultEl.textContent = d.msg;
    })
    .catch(function(err) {
      resultEl.style.color = 'var(--error-color)';
      resultEl.textContent = 'Request failed: ' + err.message;
    })
    .finally(function() { btn.disabled = false; });
}

function rgToggleSection(checkboxId, sectionId) {
  var on = document.getElementById(checkboxId).checked;
  var el = document.getElementById(sectionId);
  if (!el) return;
  el.style.opacity       = on ? '1'  : '0.4';
  el.style.pointerEvents = on ? ''   : 'none';
}

function rgToggleCustomLogo(checkbox) {
  var field = document.getElementById('rgCustomLogoField');
  var input = document.getElementById('rgLogoUrlInput');
  if (checkbox.checked) {
    field.style.display = 'block';
  } else {
    field.style.display = 'none';
    input.value = '';
  }
}

function rgPreviewUrl() {
  var type    = document.getElementById('rgPreviewEventType').value;
  var logoEl  = document.getElementById('rgLogoUrlInput');
  var noteEl  = document.querySelector('[name="notificationFooterNote"]');
  var logoVal = logoEl ? logoEl.value.trim() : '';
  var noteVal = noteEl ? noteEl.value.trim() : '';
  var url = rgRootURL + '/manage/rotogate-management/previewEmail?eventType=' + type;
  if (logoVal) { url = url + '\x26logoUrl='    + encodeURIComponent(logoVal); }
  url = url + '\x26footerNote=' + encodeURIComponent(noteVal);
  return url;
}

function rgDisablePreviewLinks(frame) {
  frame.onload = function() {
    try {
      var doc = frame.contentDocument || frame.contentWindow.document;
      doc.querySelectorAll('a, button').forEach(function(el) {
        el.addEventListener('click', function(e) { e.preventDefault(); e.stopPropagation(); });
      });
      doc.querySelectorAll('form').forEach(function(f) {
        f.addEventListener('submit', function(e) { e.preventDefault(); });
      });
    } catch(e) {}
  };
}

function rgPreviewEmail() {
  var modal = document.getElementById('rgPreviewModal');
  var frame = document.getElementById('rgPreviewFrame');
  modal.style.display = 'flex';
  rgDisablePreviewLinks(frame);
  frame.src = rgPreviewUrl();
}

function rgReloadPreview() {
  var frame = document.getElementById('rgPreviewFrame');
  rgDisablePreviewLinks(frame);
  frame.src = rgPreviewUrl();
}

function rgClosePreview() {
  var modal = document.getElementById('rgPreviewModal');
  var frame = document.getElementById('rgPreviewFrame');
  modal.style.display = 'none';
  frame.src = 'about:blank';
}


function rgInitNotifications() {
  // Dynamic values are served as a JSON island in the page (no EL in this cached adjunct).
  var dataEl = document.getElementById('rgNotificationsData');
  if (dataEl) {
    try {
      var data = JSON.parse(dataEl.textContent);
      rgRootURL     = data.rootURL    || '';
      rgSmtpEvents  = data.smtpEvents  || '';
      rgSlackEvents = data.slackEvents || '';
      rgTeamsEvents = data.teamsEvents || '';
    } catch (e) { /* leave defaults */ }
  }

  // Init on load
  rgInitEventCheckboxes('smtpEvents',  rgSmtpEvents);
  rgInitEventCheckboxes('slackEvents', rgSlackEvents);
  rgInitEventCheckboxes('teamsEvents', rgTeamsEvents);
  rgToggleMaster();
  rgToggleWebhook('smtpEnabled',  'smtpConfig');
  rgToggleWebhook('slackEnabled', 'slackConfig');
  rgToggleWebhook('teamsEnabled', 'teamsConfig');
  rgToggleSection('staleWarningEnabled', 'staleWarningConfig');

  // --- CSP: wire event handlers (replaces former inline on* attributes) ---
  var master = document.getElementById('notificationsEnabled');
  if (master) master.addEventListener('change', rgToggleMaster);

  var customLogo = document.getElementById('rgUseCustomLogo');
  if (customLogo) customLogo.addEventListener('change', function() { rgToggleCustomLogo(this); });

  var previewBtn = document.getElementById('rgPreviewBtn');
  if (previewBtn) previewBtn.addEventListener('click', rgPreviewEmail);

  var previewEventType = document.getElementById('rgPreviewEventType');
  if (previewEventType) previewEventType.addEventListener('change', rgReloadPreview);

  var previewCloseBtn = document.getElementById('rgPreviewCloseBtn');
  if (previewCloseBtn) previewCloseBtn.addEventListener('click', rgClosePreview);

  document.querySelectorAll('.js-rg-test-webhook').forEach(function(btn) {
    btn.addEventListener('click', function() {
      rgTestWebhook(this, this.dataset.urlField, this.dataset.endpoint, this.dataset.result);
    });
  });

  document.querySelectorAll('.js-rg-webhook-toggle').forEach(function(cb) {
    cb.addEventListener('change', function() { rgToggleWebhook(this.id, this.dataset.config); });
  });

  document.querySelectorAll('.js-rg-section-toggle').forEach(function(cb) {
    cb.addEventListener('change', function() { rgToggleSection(this.id, this.dataset.section); });
  });

  document.querySelectorAll('.js-rg-no-wheel').forEach(function(el) {
    el.addEventListener('wheel', function() { this.blur(); });
  });

  var previewModal = document.getElementById('rgPreviewModal');
  if (previewModal) {
    previewModal.addEventListener('click', function(e) {
      if (e.target === this) rgClosePreview();
    });
  }
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', rgInitNotifications);
} else {
  rgInitNotifications();
}
