/*
 * accessManagement.jelly behaviour: tabs, user search/filter, Add-User drawer, role form.
 * Externalised from the inline <script> for CSP compliance (Jenkins hosting).
 * Loaded via <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.accessManagementAssets"/>.
 * Functions below are referenced by inline on* handlers in the jelly, so they stay global;
 * only the load-time setup runs inside the DOMContentLoaded guard (adjuncts load in <head>).
 */
// HTML-escape a value before embedding it in innerHTML (user-controlled names etc.)
function oamEscHtml(s) { var d = document.createElement('div'); d.textContent = (s == null ? '' : s); return d.innerHTML; }
// ── Tabs ──────────────────────────────────────────────────────────────
function switchTab(tab) {
  document.getElementById('tabPanelUsers').style.display = tab === 'users' ? '' : 'none';
  document.getElementById('tabPanelRoles').style.display = tab === 'roles' ? '' : 'none';
  document.getElementById('tabBtnUsers').classList.toggle('oam-tab-active', tab === 'users');
  document.getElementById('tabBtnRoles').classList.toggle('oam-tab-active', tab === 'roles');
}

function gotoDetail(rowEl) {
  if (rowEl.dataset.type === 'GROUP') {
    window.location.href = 'groupDetail?oid=' + encodeURIComponent(rowEl.dataset.sid);
  } else {
    window.location.href = 'checkAccess?sid=' + encodeURIComponent(rowEl.dataset.sid) +
                            '&type=' + encodeURIComponent(rowEl.dataset.type);
  }
}

function filterUsers(q) {
  q = (q || '').toLowerCase();
  document.querySelectorAll('.user-row').forEach(function(r) {
    var sid = (r.dataset.sid || '').toLowerCase();
    var dn  = (r.dataset.display || '').toLowerCase();
    r.style.display = (!q || sid.includes(q) || dn.includes(q)) ? '' : 'none';
  });
}

// ── Add User Drawer ───────────────────────────────────────────────────
var auCheckTimer = null;
var auCurrentType = 'NATIVE';
var auUserFound = false;

function openAddUserDrawer() {
  var d = document.getElementById('addUserDrawer');
  d.style.display = 'flex';
  setTimeout(function() { d.style.right = '0'; }, 10);
  document.getElementById('drawerBackdrop').style.display = '';
  document.body.style.overflow = 'hidden';
  auReset();
  setTimeout(function() { document.getElementById('auSid').focus(); }, 120);
}

function closeAddUserDrawer() {
  var d = document.getElementById('addUserDrawer');
  d.style.right = '-500px';
  setTimeout(function() {
    d.style.display = 'none';
    document.getElementById('drawerBackdrop').style.display = 'none';
  }, 270);
  document.body.style.overflow = '';
}

function auReset() {
  if (auCurrentType !== 'GROUP') {
    document.getElementById('auSid').value = '';
  }
  document.getElementById('auStatus').style.display = 'none';
  document.getElementById('auCreateForm').style.display = 'none';
  document.getElementById('auEntraInfo').style.display = auCurrentType === 'ENTRA' ? '' : 'none';
  document.getElementById('auGroupFields').style.display = auCurrentType === 'GROUP' ? '' : 'none';
  document.getElementById('auSidLabel').parentElement.style.display = auCurrentType === 'GROUP' ? 'none' : '';
  document.getElementById('auAction').value = 'existing';
  document.getElementById('auSubmitBtn').textContent = auCurrentType === 'GROUP' ? 'Add Group' : 'Continue';
  document.getElementById('auDrawerTitle').textContent = auCurrentType === 'GROUP' ? 'Add Entra Group' : 'Add User';
  auUserFound = false;
}

function auTypeChange(type) {
  auCurrentType = type;
  var nativeLabel = document.getElementById('auTypeNativeLabel');
  var entraLabel  = document.getElementById('auTypeEntraLabel');
  var groupLabel  = document.getElementById('auTypeGroupLabel');
  var inactive    = 'var(--oau-card-bg)';
  var inactiveClr = 'var(--text-color,#333)';
  var active      = 'var(--blue)';
  var activeClr   = '#fff';

  nativeLabel.style.background = type === 'NATIVE' ? active : inactive;
  nativeLabel.style.color      = type === 'NATIVE' ? activeClr : inactiveClr;
  entraLabel.style.background  = type === 'ENTRA'  ? active : inactive;
  entraLabel.style.color       = type === 'ENTRA'  ? activeClr : inactiveClr;
  groupLabel.style.background  = type === 'GROUP'  ? active : inactive;
  groupLabel.style.color       = type === 'GROUP'  ? activeClr : inactiveClr;

  var sidLabel = document.getElementById('auSidLabel');
  var sid      = document.getElementById('auSid');
  if (type === 'NATIVE') {
    sidLabel.textContent = 'Username *';
    sid.placeholder = 'e.g. mark';
  } else if (type === 'ENTRA') {
    sidLabel.textContent = 'Email / UPN *';
    sid.placeholder = 'e.g. mark@company.com';
  }

  auReset();
}

function auOnInput(val) {
  if (auCurrentType !== 'NATIVE') return;
  clearTimeout(auCheckTimer);
  if (!val.trim()) {
    document.getElementById('auStatus').style.display = 'none';
    document.getElementById('auCreateForm').style.display = 'none';
    return;
  }
  auCheckTimer = setTimeout(function() { auCheckUser(val); }, 400);
}

function auCheckUser(val) {
  if (auCurrentType !== 'NATIVE' || !val.trim()) return;
  fetch('checkNativeUser?username=' + encodeURIComponent(val.trim()))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      var status     = document.getElementById('auStatus');
      var createForm = document.getElementById('auCreateForm');
      var submitBtn  = document.getElementById('auSubmitBtn');
      var actionEl   = document.getElementById('auAction');

      status.style.display = '';
      if (data.exists) {
        auUserFound = true;
        status.style.background = 'var(--oau-success-bg)';
        status.style.border = '1px solid var(--oau-success-border)';
        status.style.color = 'var(--oau-success-fg)';
        status.innerHTML = '&#10003; Native user found' +
          (data.displayName ? ' — <strong>' + oamEscHtml(data.displayName) + '</strong>' : '') +
          '. Will be added to access management.';
        createForm.style.display = 'none';
        actionEl.value = 'existing';
        submitBtn.textContent = 'Continue';
      } else {
        auUserFound = false;
        status.style.background = 'var(--oau-danger-bg)';
        status.style.border = '1px solid var(--oau-danger-border)';
        status.style.color = 'var(--oau-danger-fg)';
        status.innerHTML = '&#10007; No Jenkins account found for <strong>' + oamEscHtml(val.trim()) + '</strong>.';
        createForm.style.display = '';
        actionEl.value = 'create';
        submitBtn.textContent = 'Create Account & Continue';
      }
    })
    .catch(function() {});
}

// ── Role form ─────────────────────────────────────────────────────────
function showRoleForm(name, desc, permsJson) {
  var panel  = document.getElementById('roleFormPanel');
  var title  = document.getElementById('roleFormTitle');
  var nameEl = document.getElementById('roleNameInput');
  var descEl = document.getElementById('roleDescInput');

  document.querySelectorAll('.perm-cb').forEach(function(cb) { cb.checked = false; });

  if (name) {
    title.textContent = 'Edit Role: ' + name;
    nameEl.value = name;
    descEl.value = desc || '';
    var perms = [];
    try { perms = JSON.parse(permsJson || '[]'); } catch(e) {}
    var permSet = {};
    perms.forEach(function(p) { permSet[p] = true; });
    document.querySelectorAll('.perm-cb').forEach(function(cb) { cb.checked = !!permSet[cb.value]; });
  } else {
    title.textContent = 'Create Role';
    nameEl.value = '';
    descEl.value = '';
  }

  panel.style.display = '';
  panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  setTimeout(function() { nameEl.focus(); }, 50);
}

function hideRoleForm() {
  document.getElementById('roleFormPanel').style.display = 'none';
}

function editRoleFromBtn(btn) {
  showRoleForm(btn.dataset.rname, btn.dataset.rdesc, btn.dataset.rperms);
}

// ── Init ──────────────────────────────────────────────────────────────
function oamInit() {
  var badge = document.getElementById('userCountBadge');
  if (badge) badge.textContent = document.querySelectorAll('.user-row').length;
  var params = new URLSearchParams(window.location.search);
  if (params.get('roleSaved') || params.get('roleDeleted') || params.get('error') === 'missingRoleName') {
    switchTab('roles');
  }
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', oamInit);
} else {
  oamInit();
}
