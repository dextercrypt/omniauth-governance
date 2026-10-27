/*
 * Notification-log page behaviour: client-side pagination of the log table.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.notificationLogAssets"/>
 * Externalised from notificationLog.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * goPage() stays global because the jelly's onclick handlers call it. Row collection
 * runs on DOMContentLoaded since this adjunct loads in <head> before the table exists.
 */
var PAGE_SIZE   = 25;
var currentPage = 1;
var rows        = [];
var totalPages  = 0;

function goPage(p) {
  if (p < 1) p = 1;
  if (p > totalPages) p = totalPages;
  currentPage = p;

  var start = (p - 1) * PAGE_SIZE;
  var end   = start + PAGE_SIZE;
  rows.forEach(function(r, i) {
    r.style.display = (i >= start && i < end) ? '' : 'none';
  });

  document.getElementById('pageInfo').textContent =
    'Showing ' + (start + 1) + '–' + Math.min(end, rows.length) + ' of ' + rows.length;

  // Page number buttons — show window of 5 around current
  var btns = document.getElementById('pageButtons');
  btns.innerHTML = '';
  var wStart = Math.max(1, p - 2);
  var wEnd   = Math.min(totalPages, wStart + 4);
  if (wEnd - wStart < 4) wStart = Math.max(1, wEnd - 4);
  for (var i = wStart; i <= wEnd; i++) {
    (function(pg) {
      var b = document.createElement('button');
      b.textContent = pg;
      b.className   = 'jenkins-button';
      b.style.cssText = 'font-size:0.82em; padding:4px 10px;'
        + (pg === p ? ' background:var(--blue,var(--blue)); color:#fff; border-color:var(--blue,var(--blue));' : '');
      b.onclick = function() { goPage(pg); };
      btns.appendChild(b);
    })(i);
  }

  document.getElementById('btnFirst').disabled = (p === 1);
  document.getElementById('btnPrev').disabled  = (p === 1);
  document.getElementById('btnNext').disabled  = (p === totalPages);
  document.getElementById('btnLast').disabled  = (p === totalPages);

  // Hide pagination entirely if only one page
  document.getElementById('pagination').style.display =
    totalPages <= 1 ? 'none' : '';
}

function initNotificationLog() {
  rows       = Array.from(document.querySelectorAll('.log-row'));
  totalPages = Math.ceil(rows.length / PAGE_SIZE);
  if (rows.length > 0) goPage(1);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initNotificationLog);
} else {
  initNotificationLog();
}
