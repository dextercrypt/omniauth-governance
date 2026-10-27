/*
 * Audit Log page behaviour: timestamp formatting, filtering, and the purge
 * confirmation modal.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.auditLogAssets"/>
 * Externalised from auditLog.jelly's inline <script> blocks for CSP compliance (Jenkins hosting).
 * The one dynamic value (whether the current admin is SSO) is read from the
 * data-sso attribute on #alxPurgeModal instead of being inlined as EL.
 * Functions called from inline on* handlers are declared at top level (global).
 */

// Format timestamps
document.addEventListener('DOMContentLoaded', function() {
  document.querySelectorAll('.alx-ts').forEach(function(el) {
    var ts = el.getAttribute('data-ts');
    if (!ts) return;
    try {
      var d = new Date(ts);
      el.textContent = d.toLocaleString(undefined, {
        year:'numeric', month:'short', day:'numeric',
        hour:'2-digit', minute:'2-digit'
      });
    } catch(e) {}
  });
  // Pre-fill filters from URL params (e.g. arriving from the dashboard chart: ?from&to&action)
  try {
    var qp = new URLSearchParams(window.location.search);
    if (qp.get('from'))   document.getElementById('alxDateFrom').value   = qp.get('from');
    if (qp.get('to'))     document.getElementById('alxDateTo').value     = qp.get('to');
    if (qp.get('action')) document.getElementById('alxActionFilter').value = qp.get('action');
  } catch(e) {}
  alxFilter();
});

function alxFilter() {
  var q       = document.getElementById('alxSearch').value.toLowerCase();
  var action  = document.getElementById('alxActionFilter').value;
  var ip      = document.getElementById('alxIpFilter').value.toLowerCase();
  var fromVal = document.getElementById('alxDateFrom').value;
  var toVal   = document.getElementById('alxDateTo').value;
  // Parse both bounds as UTC day edges so they line up with the event timestamps (stored UTC)
  // and with the dashboard chart (bucketed UTC). Parsing 'to' as local time dropped same-day
  // events logged after the local end-of-day in timezones ahead of UTC.
  var fromTs  = fromVal ? new Date(fromVal + 'T00:00:00Z').getTime() : null;
  var toTs    = toVal   ? new Date(toVal + 'T23:59:59Z').getTime() : null;
  var rows    = document.querySelectorAll('.alx-row');
  var shown   = 0;
  rows.forEach(function(row) {
    var text   = (row.getAttribute('data-text') || '').toLowerCase();
    var act    = row.getAttribute('data-action') || '';
    var rowIp  = (row.getAttribute('data-ip') || '').toLowerCase();
    var rowTs  = row.getAttribute('data-ts');
    var rowTime = rowTs ? new Date(rowTs).getTime() : null;
    var match  = (!q      || text.indexOf(q) >= 0)
              && (!action || act === action)
              && (!ip     || rowIp.indexOf(ip) >= 0)
              && (!fromTs || (rowTime !== null && rowTime >= fromTs))
              && (!toTs   || (rowTime !== null && rowTime <= toTs));
    row.classList.toggle('alx-hidden', !match);
    if (match) shown++;
  });
  var count = document.getElementById('alxCount');
  if (count) count.textContent = shown + ' event' + (shown === 1 ? '' : 's');
}

function alxClearFilters() {
  document.getElementById('alxSearch').value      = '';
  document.getElementById('alxActionFilter').value = '';
  document.getElementById('alxIpFilter').value    = '';
  document.getElementById('alxDateFrom').value    = '';
  document.getElementById('alxDateTo').value      = '';
  alxFilter();
}

function alxPurgeOpen() {
  var modal = document.getElementById('alxPurgeModal');
  modal.style.display = 'flex';
  document.getElementById('alxPurgePwd').value = '';
  document.getElementById('alxPurgeBtn').disabled = true;
  document.getElementById('alxPurgeErr').style.display = 'none';
  document.getElementById('alxPurgeBox').classList.remove('alx-shake');
  setTimeout(function(){ document.getElementById('alxPurgePwd').focus(); }, 60);
  document.addEventListener('keydown', alxPurgeKey);
}
function alxPurgeClose() {
  document.getElementById('alxPurgeModal').style.display = 'none';
  document.removeEventListener('keydown', alxPurgeKey);
}
function alxPurgeKey(e) {
  if (e.key === 'Escape') alxPurgeClose();
}
function alxPurgeSubmit() {
  var pwd = document.getElementById('alxPurgePwd').value;
  if (!pwd) return;
  var btn = document.getElementById('alxPurgeBtn');
  btn.disabled = true;
  btn.textContent = 'Verifying…';

  // Current admin signs in via SSO (no local password) → confirm purge with a typed phrase.
  var modalEl = document.getElementById('alxPurgeModal');
  var isSso = !!(modalEl && modalEl.getAttribute('data-sso') === 'true');

  var body = new URLSearchParams();
  // SSO admins confirm with a typed phrase; native users with their password.
  body.append(isSso ? 'confirmPhrase' : 'confirmPassword', pwd);
  var head = document.head;
  body.append(head.dataset.crumbHeader || 'Jenkins-Crumb', head.dataset.crumbValue || '');

  fetch('purgeAuditLog', { method:'POST', body: body })
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (data.error) {
        var box = document.getElementById('alxPurgeBox');
        box.classList.remove('alx-shake');
        void box.offsetWidth;
        box.classList.add('alx-shake');
        document.getElementById('alxPurgeErr').style.display = '';
        document.getElementById('alxPurgePwd').value = '';
        document.getElementById('alxPurgePwd').focus();
        btn.disabled = true;
        btn.textContent = 'Delete All Logs';
      } else {
        window.location.href = 'auditLog?purged=true';
      }
    })
    .catch(function() {
      btn.disabled = false;
      btn.textContent = 'Delete All Logs';
    });
}
