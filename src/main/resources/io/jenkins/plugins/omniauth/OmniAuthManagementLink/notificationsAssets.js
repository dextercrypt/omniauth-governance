/*
 * Notifications settings page behaviour: channel/section toggles, event-checkbox
 * init, test-send calls, and the email-preview modal.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.notificationsAssets"/>
 * Externalised from notifications.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * The dynamic values (Jenkins root URL + saved event lists) are read from a
 * <script type="application/json" id="oauNotificationsData"> island in the page, since a
 * static adjunct is cached and cannot contain Jelly EL expressions.
 * Functions stay global because the jelly's onclick handlers call them.
 */

// Populated on init() from the JSON data island.
var oauRootURL    = '';
var oauSmtpEvents  = '';
var oauSlackEvents = '';
var oauTeamsEvents = '';

function oauInitEventCheckboxes(channelName, savedStr) {
  var saved = savedStr ? savedStr.split(",").map(function(s){return s.trim();}).filter(Boolean) : [];
  var boxes = document.querySelectorAll('[name="' + channelName + '"]');
  boxes.forEach(function(cb) {
    cb.checked = saved.indexOf(cb.value) >= 0;
  });
}

function oauToggleMaster() {
  var on = document.getElementById('notificationsEnabled').checked;
  ['oauChannelsSection', 'oauLogoSection'].forEach(function(id) {
    var el = document.getElementById(id);
    if (el) {
      el.style.opacity       = on ? '1'  : '0.4';
      el.style.pointerEvents = on ? ''   : 'none';
    }
  });
}

function oauToggleWebhook(checkboxId, configId) {
  var on  = document.getElementById(checkboxId).checked;
  var cfg = document.getElementById(configId);
  cfg.style.opacity       = on ? '1'  : '0.4';
  cfg.style.pointerEvents = on ? ''   : 'none';
}

function oauTestWebhook(btn, urlField, endpoint, resultId) {
  var resultEl = document.getElementById(resultId);
  resultEl.style.color = '#888';
  resultEl.textContent = 'Sending…';
  btn.disabled = true;

  var form = btn.closest('form');
  var url  = form.querySelector('[name="' + urlField + '"]').value;
  var head = document.querySelector('head');
  var cf   = head ? head.getAttribute('data-crumb-header') : '';
  var cv   = head ? head.getAttribute('data-crumb-value')  : '';
  var headers = {'Content-Type': 'application/x-www-form-urlencoded'};
  if (cf) headers[cf] = cv;

  fetch(oauRootURL + '/manage/omniauth-management/' + endpoint, {
    method: 'POST', credentials: 'same-origin', headers: headers,
    body: new URLSearchParams([[urlField, url]])
  })
    .then(function(r) { return r.text(); })
    .then(function(t) {
      var d = JSON.parse(t);
      resultEl.style.color = d.ok ? 'var(--oau-success-fg)' : 'var(--error-color)';
      resultEl.textContent = d.msg;
    })
    .catch(function(err) {
      resultEl.style.color = 'var(--error-color)';
      resultEl.textContent = 'Request failed: ' + err.message;
    })
    .finally(function() { btn.disabled = false; });
}

function oauToggleSection(checkboxId, sectionId) {
  var on = document.getElementById(checkboxId).checked;
  var el = document.getElementById(sectionId);
  if (!el) return;
  el.style.opacity       = on ? '1'  : '0.4';
  el.style.pointerEvents = on ? ''   : 'none';
}

function oauToggleCustomLogo(checkbox) {
  var field = document.getElementById('oauCustomLogoField');
  var input = document.getElementById('oauLogoUrlInput');
  if (checkbox.checked) {
    field.style.display = 'block';
  } else {
    field.style.display = 'none';
    input.value = '';
  }
}

// Event label tooltips — set on the <label> so hover always works
var oauEventTitles = {
  cleanup:              'Report after each automated cleanup run (dry-run or live).',
  userDeleted:          'Alert when an admin manually deletes a user via the OmniAuth UI.',
  configChanged:        'Sent when OmniAuth settings are saved.',
  protectedListChanged: 'Alert when users are added to or removed from the protected list.',
  graphApiFailure:      'Alert when Microsoft Graph API calls fail, breaking Entra group sync.',
  adminGranted:         'Alert when a user is granted Jenkins ADMINISTER permission.',
  bruteForce:           'Alert when consecutive login failures hit the configured threshold.',
  staleWarning:         'Periodic digest of users approaching the stale threshold.',
  jitRequest:           'Sent to approvers when a user submits a JIT access request.',
  jitApproved:          'Sent to the requester when their JIT request is approved.',
  jitDenied:            'Sent to the requester when their JIT request is denied or times out.'
};

function oauPreviewUrl() {
  var type    = document.getElementById('oauPreviewEventType').value;
  var logoEl  = document.getElementById('oauLogoUrlInput');
  var noteEl  = document.querySelector('[name="notificationFooterNote"]');
  var logoVal = logoEl ? logoEl.value.trim() : '';
  var noteVal = noteEl ? noteEl.value.trim() : '';
  var url = oauRootURL + '/manage/omniauth-management/previewEmail?eventType=' + type;
  if (logoVal) { url = url + '\x26logoUrl='    + encodeURIComponent(logoVal); }
  url = url + '\x26footerNote=' + encodeURIComponent(noteVal);
  return url;
}

function oauDisablePreviewLinks(frame) {
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

function oauPreviewEmail() {
  var modal = document.getElementById('oauPreviewModal');
  var frame = document.getElementById('oauPreviewFrame');
  modal.style.display = 'flex';
  oauDisablePreviewLinks(frame);
  frame.src = oauPreviewUrl();
}

function oauReloadPreview() {
  var frame = document.getElementById('oauPreviewFrame');
  oauDisablePreviewLinks(frame);
  frame.src = oauPreviewUrl();
}

function oauClosePreview() {
  var modal = document.getElementById('oauPreviewModal');
  var frame = document.getElementById('oauPreviewFrame');
  modal.style.display = 'none';
  frame.src = 'about:blank';
}

function oauSendTestEmail(btn) {
  var resultEl = document.getElementById('smtpTestResult');
  resultEl.style.color = '#888';
  resultEl.textContent = 'Sending…';
  btn.disabled = true;

  var form    = btn.closest('form');
  var tlsEl   = form.querySelector('[name="smtpTls"]');
  var headers = {};
  var head = document.querySelector('head');
  var cf = head ? head.getAttribute('data-crumb-header') : '';
  var cv = head ? head.getAttribute('data-crumb-value')  : '';
  if (cf) headers[cf] = cv;
  headers['Content-Type'] = 'application/x-www-form-urlencoded';

  var params = new URLSearchParams({
    smtpHost:        form.querySelector('[name="smtpHost"]').value,
    smtpPort:        form.querySelector('[name="smtpPort"]').value,
    smtpUsername:    form.querySelector('[name="smtpUsername"]').value,
    smtpPassword:    form.querySelector('[name="smtpPassword"]').value,
    smtpTls:         (tlsEl && tlsEl.checked) ? '1' : '',
    smtpFromAddress: form.querySelector('[name="smtpFromAddress"]').value,
    smtpFromName:    form.querySelector('[name="smtpFromName"]').value,
    smtpReplyTo:     form.querySelector('[name="smtpReplyTo"]').value,
    notifyEmails:    form.querySelector('[name="notifyEmails"]').value
  });

  fetch(oauRootURL + '/manage/omniauth-management/sendTestEmail', {
    method:      'POST',
    credentials: 'same-origin',
    headers:     headers,
    body:        params
  })
    .then(function(r) { return r.text(); })
    .then(function(text) {
      var data = JSON.parse(text);
      resultEl.style.color = data.ok ? 'var(--oau-success-fg)' : 'var(--error-color)';
      resultEl.textContent = data.msg;
    })
    .catch(function(err) {
      resultEl.style.color = 'var(--error-color)';
      resultEl.textContent = 'Request failed: ' + err.message;
    })
    .finally(function() { btn.disabled = false; });
}

function oauInitNotifications() {
  // Dynamic values are served as a JSON island in the page (no EL in this cached adjunct).
  var dataEl = document.getElementById('oauNotificationsData');
  if (dataEl) {
    try {
      var data = JSON.parse(dataEl.textContent);
      oauRootURL     = data.rootURL    || '';
      oauSmtpEvents  = data.smtpEvents  || '';
      oauSlackEvents = data.slackEvents || '';
      oauTeamsEvents = data.teamsEvents || '';
    } catch (e) { /* leave defaults */ }
  }

  ['smtpEvents','slackEvents','teamsEvents'].forEach(function(ch) {
    document.querySelectorAll('[name="' + ch + '"]').forEach(function(cb) {
      var lbl = cb.closest('label');
      if (lbl && oauEventTitles[cb.value]) lbl.setAttribute('data-tooltip', oauEventTitles[cb.value]);
    });
  });

  // Init on load
  oauInitEventCheckboxes('smtpEvents',  oauSmtpEvents);
  oauInitEventCheckboxes('slackEvents', oauSlackEvents);
  oauInitEventCheckboxes('teamsEvents', oauTeamsEvents);
  oauToggleMaster();
  oauToggleWebhook('smtpEnabled',  'smtpConfig');
  oauToggleWebhook('slackEnabled', 'slackConfig');
  oauToggleWebhook('teamsEnabled', 'teamsConfig');
  oauToggleSection('staleWarningEnabled', 'staleWarningConfig');

  // --- CSP: wire event handlers (replaces former inline on* attributes) ---
  var master = document.getElementById('notificationsEnabled');
  if (master) master.addEventListener('change', oauToggleMaster);

  var customLogo = document.getElementById('oauUseCustomLogo');
  if (customLogo) customLogo.addEventListener('change', function() { oauToggleCustomLogo(this); });

  var previewBtn = document.getElementById('oauPreviewBtn');
  if (previewBtn) previewBtn.addEventListener('click', oauPreviewEmail);

  var previewEventType = document.getElementById('oauPreviewEventType');
  if (previewEventType) previewEventType.addEventListener('change', oauReloadPreview);

  var previewCloseBtn = document.getElementById('oauPreviewCloseBtn');
  if (previewCloseBtn) previewCloseBtn.addEventListener('click', oauClosePreview);

  var sendTestEmailBtn = document.getElementById('oauSendTestEmailBtn');
  if (sendTestEmailBtn) sendTestEmailBtn.addEventListener('click', function() { oauSendTestEmail(this); });

  document.querySelectorAll('.js-oau-test-webhook').forEach(function(btn) {
    btn.addEventListener('click', function() {
      oauTestWebhook(this, this.dataset.urlField, this.dataset.endpoint, this.dataset.result);
    });
  });

  document.querySelectorAll('.js-oau-webhook-toggle').forEach(function(cb) {
    cb.addEventListener('change', function() { oauToggleWebhook(this.id, this.dataset.config); });
  });

  document.querySelectorAll('.js-oau-section-toggle').forEach(function(cb) {
    cb.addEventListener('change', function() { oauToggleSection(this.id, this.dataset.section); });
  });

  document.querySelectorAll('.js-oau-no-wheel').forEach(function(el) {
    el.addEventListener('wheel', function() { this.blur(); });
  });

  var previewModal = document.getElementById('oauPreviewModal');
  if (previewModal) {
    previewModal.addEventListener('click', function(e) {
      if (e.target === this) oauClosePreview();
    });
  }
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', oauInitNotifications);
} else {
  oauInitNotifications();
}
