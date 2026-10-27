/*
 * userStatus.jelly behaviour: search/filter, kebab menus, delete/mark modals, tooltips, stats.
 * Externalised from the inline <script> for CSP compliance (Jenkins hosting).
 * Loaded via <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.userStatusAssets"/>.
 * Functions below are referenced by inline on* handlers in the jelly, so they stay global;
 * only the load-time setup runs inside the DOMContentLoaded guard (adjuncts load in <head>).
 */
var _ft = 'all', _fs = 'all';
var _openMenu = null;

// ── Filter ──────────────────────────────────────────────────────────
function usFilter() {
  var q = (document.getElementById('usQ').value || '').toLowerCase();
  var rows = document.querySelectorAll('#usTbody .us-row');
  var n = 0;
  rows.forEach(function(r) {
    var dsMatch = _fs === 'all'
               || (_fs === 'pending'  && r.dataset.pending === 'true')
               || (_fs === 'stale'    && r.dataset.stale === 'true')
               || (_fs !== 'stale' && _fs !== 'pending' && r.dataset.ds === _fs);
    var show = (!q || (r.dataset.q || '').toLowerCase().includes(q))
            && (_ft === 'all' || r.dataset.prov === _ft)
            && dsMatch;
    r.style.display = show ? '' : 'none';
    if (show) n++;
  });
  document.getElementById('usEmpty').style.display = n === 0 ? '' : 'none';
}

function usSetFilter(fg, fv, btn) {
  if (fg === 'type')   _ft = fv;
  if (fg === 'status') _fs = fv;
  document.querySelectorAll('[data-fg="' + fg + '"]').forEach(function(b) {
    b.classList.toggle('us-pill-on', b === btn);
  });
  usFilter();
}

// ── Row click → detail ───────────────────────────────────────────────
function usOpen(e, uid) {
  if (e.target.closest('.us-kebab-wrap')) return;
  window.location.href = 'userOverview?userId=' + encodeURIComponent(uid);
}

// ── Kebab menu ───────────────────────────────────────────────────────
function usToggleMenu(e, btn) {
  e.stopPropagation();
  var menu = btn.nextElementSibling;
  var isOpen = menu.classList.contains('us-open');
  usCloseMenus();
  if (!isOpen) {
    var rect = btn.getBoundingClientRect();
    menu.style.top  = (rect.bottom + 4) + 'px';
    menu.style.left = '0px'; // temp show to measure
    menu.classList.add('us-open');
    btn.classList.add('us-open');
    var mw = menu.offsetWidth;
    var left = rect.right - mw;
    if (left < 4) left = 4;
    menu.style.left = left + 'px';
    _openMenu = menu;
  }
}

function usCloseMenus() {
  document.querySelectorAll('.us-menu.us-open').forEach(function(m) { m.classList.remove('us-open'); });
  document.querySelectorAll('.us-kebab-btn.us-open').forEach(function(b) { b.classList.remove('us-open'); });
  _openMenu = null;
}

// ── Pending-deletion info modal ──────────────────────────────────────
function usPDClose() {
  document.getElementById('usPDModal').style.display = 'none';
}

// ── Delete modal ─────────────────────────────────────────────────────
// ── Mark / Unmark for deletion ───────────────────────────────────────
// Build the hidden form with property assignment (never innerHTML), so a userId
// containing HTML metacharacters can't break out and inject markup/script.
function usPostDeletionForm(action, btn) {
  var f = document.createElement('form');
  f.method = 'post'; f.action = action; f.style.display = 'none';
  function hidden(name, val) {
    var i = document.createElement('input');
    i.type = 'hidden'; i.name = name; i.value = (val == null ? '' : val);
    return i;
  }
  f.appendChild(hidden('userId', btn.dataset.userid));
  f.appendChild(hidden('from', 'userStatus'));
  f.appendChild(hidden(btn.dataset.crumbField, btn.dataset.crumbValue));
  document.body.appendChild(f); f.submit();
}

function usMark(btn)   { usCloseMenus(); usPostDeletionForm('markForDeletion', btn); }
function usUnmark(btn) { usCloseMenus(); usPostDeletionForm('unmarkForDeletion', btn); }

function usDelete(btn) {
  usCloseMenus();
  var uid = btn.dataset.userid;
  document.getElementById('usDName').textContent  = uid;
  document.getElementById('usDHint').textContent  = uid;
  document.getElementById('usDUser').value        = uid;
  document.getElementById('usDCF').name           = btn.dataset.crumbField;
  document.getElementById('usDCF').value          = btn.dataset.crumbField;
  document.getElementById('usDCV').name           = btn.dataset.crumbField;
  document.getElementById('usDCV').value          = btn.dataset.crumbValue;
  document.getElementById('usDInput').value       = '';
  document.getElementById('usDBtn').disabled      = true;
  var modal = document.getElementById('usDModal');
  modal.style.display = 'flex';
  document.getElementById('usDBtn').onclick = function() {
    usCloseD();
    document.getElementById('usDForm').submit();
  };
  setTimeout(function() { document.getElementById('usDInput').focus(); }, 80);
}

function usCloseD() {
  document.getElementById('usDModal').style.display = 'none';
  document.getElementById('usDInput').value  = '';
  document.getElementById('usDBtn').disabled = true;
}

function usDCheck() {
  document.getElementById('usDBtn').disabled =
    document.getElementById('usDInput').value !== document.getElementById('usDUser').value;
}

// ── Init (load-time DOM wiring) ──────────────────────────────────────
function usInit() {
  document.addEventListener('click', usCloseMenus);
  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') { usCloseMenus(); usCloseD(); usPDClose(); }
  });

  // Pending-deletion info modal
  var params = new URLSearchParams(window.location.search);
  if (params.get('info') === 'pendingDeletion') {
    document.getElementById('usPDModal').style.display = 'flex';
  }

  // Tooltip positioning
  document.querySelectorAll('.us-tipwrap').forEach(function(wrap) {
    var icon = wrap.querySelector('.us-tipicon');
    var box  = wrap.querySelector('.us-tipbox');
    wrap.addEventListener('mouseenter', function() {
      box.style.display = 'block';
      var r  = icon.getBoundingClientRect();
      var bw = box.offsetWidth;
      var left = r.left - 10;
      if (left + bw > window.innerWidth - 8) left = window.innerWidth - bw - 8;
      if (left < 4) left = 4;
      box.style.top  = (r.bottom + 6) + 'px';
      box.style.left = left + 'px';
    });
    wrap.addEventListener('mouseleave', function() { box.style.display = 'none'; });
  });

  // Summary counts
  var rows = document.querySelectorAll('#usTbody .us-row');
  var tot = rows.length, act = 0, ina = 0, entra = 0, stale = 0;
  rows.forEach(function(r) {
    var ds = r.dataset.ds || '', p = r.dataset.prov || '';
    if (ds === 'active') act++; else ina++;
    if (p !== 'NATIVE') entra++;
    if (r.dataset.stale === 'true') stale++;
  });
  document.getElementById('sTotal').textContent    = tot;
  document.getElementById('sActive').textContent   = act;
  document.getElementById('sInactive').textContent = ina;
  document.getElementById('sEntra').textContent    = entra;
  document.getElementById('sStale').textContent    = stale;
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', usInit);
} else {
  usInit();
}
