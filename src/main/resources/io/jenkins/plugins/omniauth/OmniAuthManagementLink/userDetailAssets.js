/*
 * userDetail.jelly behaviour: temp-password copy, Grant/Edit access drawer, custom
 * permission picker, approver chips, and the scope-picker modal.
 * Externalised from two inline <script> blocks for CSP compliance (Jenkins hosting).
 * Loaded via <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.userDetailAssets"/>.
 * Functions below are referenced by inline on* handlers in the jelly, so they stay global.
 * Server values that used to be inlined via Jelly EL (global-admin flag, suggest-approvers
 * URL, scope-item JSON) now come from #udxBootstrap data-* attributes, read in init();
 * other load-time DOM work also runs in init() (adjuncts load in <head>).
 */

// ── Temp-password modal copy button ─────────────────────────────────
function copyTmpPwd(e) {
  var btn = e.currentTarget;
  var pwd = document.getElementById('tmpPwdVal').textContent.trim();
  navigator.clipboard.writeText(pwd).then(function() {
    btn.textContent = 'Copied!';
    setTimeout(function() { btn.textContent = 'Copy'; }, 2000);
  });
}

// ── Grant / Edit access drawer ──────────────────────────────────────
var udxUserIsGlobalAdmin = false;  // set from #udxBootstrap in init()
var udxConflictConfirmed = false;
var udxIsEdit    = false;   // is the drawer in edit (vs add) mode
var udxGrantWhat = 'role';  // 'role' | 'custom'
var udxPermMeta  = {};      // permId -> {name, group, short}

function udxEsc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function udxConflictCancel() {
  document.getElementById('udxConflictModal').style.display = 'none';
}
function udxConflictProceed() {
  document.getElementById('udxConflictModal').style.display = 'none';
  udxConflictConfirmed = true;
  document.getElementById('udxGrantForm').submit();
}

// ── open / close ────────────────────────────────────────────────────
function udxOpenDrawer() {
  document.getElementById('udxDrawer').setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';
  document.addEventListener('keydown', udxDrawerKey);
  udxUpdateSummary();
}
function udxCloseDrawer() {
  document.getElementById('udxDrawer').setAttribute('aria-hidden', 'true');
  document.body.style.overflow = '';
  document.removeEventListener('keydown', udxDrawerKey);
}
function udxDrawerKey(e) { if (e.key === 'Escape') udxCloseDrawer(); }

// ── ADD ─────────────────────────────────────────────────────────────
function udxShowAdd() {
  udxIsEdit = false;
  udxConflictConfirmed = false;
  document.getElementById('udxGrantForm').action = 'grantAssignment';
  document.getElementById('udxDrawerTitle').textContent = 'Grant access';
  document.getElementById('udxSubmitBtn').textContent   = 'Grant access';

  document.getElementById('udxScopeChoose').style.display = '';
  document.getElementById('udxScopeFixed').style.display  = 'none';
  document.querySelectorAll('input[name="scopeType"]').forEach(function(r){ r.checked = r.value === 'global'; });
  udxScopeClearFn();
  udxScopeChange('global');

  var hasRoles = document.getElementById('udxRoleSelect').querySelector('option:not([hidden])') != null;
  udxSetMode(hasRoles ? 'role' : 'custom');
  document.querySelectorAll('#udxCustomPerms .udx-perm-cb').forEach(function(cb){ cb.checked = false; });
  udxPermChanged();

  document.getElementById('udxExpiresAt').value = '';
  udxUpdateExpiryHint('', 'udxExpiryHint');

  document.querySelectorAll('input[name="accessType"]').forEach(function(r){ r.checked = r.value === 'STANDING'; });
  udxClearChips('udxApproverChips', 'udxApprovers');
  document.getElementById('udxMaxDuration').value = 4;
  document.getElementById('udxApprovalTimeout').value = 4;
  udxAccessTypeChange('STANDING');

  udxOpenDrawer();
}

// ── EDIT ────────────────────────────────────────────────────────────
function udxShowEdit(scope, scopeDisplay, roleId, customPermsArr, expiresAt, accessType, approverGroup, maxDuration, approvalTimeout) {
  udxIsEdit = true;
  udxConflictConfirmed = true; // edits never trip the global-admin conflict guard
  document.getElementById('udxGrantForm').action = 'editAssignment';
  document.getElementById('udxDrawerTitle').textContent = 'Edit access';
  document.getElementById('udxSubmitBtn').textContent   = 'Save changes';

  // Scope is fixed in edit mode.
  document.getElementById('udxScopeChoose').style.display = 'none';
  document.getElementById('udxScopeFixed').style.display  = '';
  document.getElementById('udxEditScopeDisplay').textContent = scopeDisplay || scope;
  var sv = document.getElementById('udxScopeValue');
  sv.value = scope; sv.setAttribute('name', 'scope');
  document.getElementById('udxScopeGlobal').removeAttribute('name');
  document.getElementById('udxAccessTypeSection').style.display = ''; // edit = scoped item

  var isCustom = (roleId === 'CUSTOM');
  if (isCustom) {
    udxSetMode('custom');
    document.querySelectorAll('#udxCustomPerms .udx-perm-cb').forEach(function(cb){
      cb.checked = Array.isArray(customPermsArr) && customPermsArr.indexOf(cb.value) >= 0;
    });
    udxPermChanged();
  } else {
    udxSetMode('role');
    var sel = document.getElementById('udxRoleSelect');
    for (var i = 0; i < sel.options.length; i++) {
      if (sel.options[i].value === roleId) { sel.selectedIndex = i; break; }
    }
    udxRoleChange();
  }

  var dateInput = document.getElementById('udxExpiresAt');
  dateInput.value = expiresAt ? expiresAt.substring(0, 10) : '';
  udxUpdateExpiryHint(dateInput.value, 'udxExpiryHint');

  var isJit = accessType === 'JIT';
  document.querySelectorAll('input[name="accessType"]').forEach(function(r){ r.checked = r.value === (isJit ? 'JIT' : 'STANDING'); });
  udxClearChips('udxApproverChips', 'udxApprovers');
  if (approverGroup) {
    approverGroup.split(',').forEach(function(a){ var t = a.trim(); if (t) udxChipAdd(t, 'udxApproverChips', 'udxApprovers'); });
  }
  document.getElementById('udxMaxDuration').value = maxDuration || 4;
  document.getElementById('udxApprovalTimeout').value = approvalTimeout || 4;
  udxAccessTypeChange(isJit ? 'JIT' : 'STANDING');

  udxOpenDrawer();
}

// ── what to grant: role vs custom ──────────────────────────────────
function udxSetMode(mode) {
  udxGrantWhat = mode;
  var sel = document.getElementById('udxRoleSelect');
  document.getElementById('udxModeRole').classList.toggle('is-active', mode === 'role');
  document.getElementById('udxModeCustom').classList.toggle('is-active', mode === 'custom');
  if (mode === 'custom') {
    sel.value = 'CUSTOM';
    document.getElementById('udxRoleRow').style.display = 'none';
    document.getElementById('udxCustomPerms').style.display = '';
    udxPermChanged();
  } else {
    if (sel.value === 'CUSTOM') {
      var first = sel.querySelector('option:not([hidden])');
      if (first) sel.value = first.value;
    }
    document.getElementById('udxRoleRow').style.display = '';
    document.getElementById('udxCustomPerms').style.display = 'none';
    udxRoleChange();
  }
  udxUpdateSummary();
}

// ── role preview ───────────────────────────────────────────────────
function udxRoleChange() {
  var sel = document.getElementById('udxRoleSelect');
  var opt = sel.options[sel.selectedIndex];
  var box = document.getElementById('udxRolePreview');
  box.innerHTML = '';
  if (!opt || opt.value === 'CUSTOM') { udxUpdateSummary(); return; }
  var ids = [];
  try { ids = JSON.parse(opt.getAttribute('data-perms') || '[]'); } catch (e) { ids = []; }
  if (!ids.length) {
    box.innerHTML = '<div class="udx-pv-empty">This role has no permissions.</div>';
    udxUpdateSummary(); return;
  }
  var groups = {}, order = [];
  ids.forEach(function(id) {
    var m = udxPermMeta[id];
    var g  = m ? (m.group || 'Other') : 'Other';
    var nm = m ? m.name : id;
    if (!groups[g]) { groups[g] = []; order.push(g); }
    groups[g].push(nm);
  });
  var head = document.createElement('div');
  head.className = 'udx-pv-gname';
  head.textContent = 'This role grants ' + ids.length + ' permission' + (ids.length === 1 ? '' : 's');
  box.appendChild(head);
  order.forEach(function(g) {
    var wrap  = document.createElement('div'); wrap.className = 'udx-pv-grp';
    var gn    = document.createElement('div'); gn.className = 'udx-pv-gname'; gn.textContent = g;
    var chips = document.createElement('div'); chips.className = 'udx-pv-chips';
    groups[g].forEach(function(nm) {
      var c = document.createElement('span'); c.className = 'udx-pv-chip'; c.textContent = nm; chips.appendChild(c);
    });
    wrap.appendChild(gn); wrap.appendChild(chips); box.appendChild(wrap);
  });
  udxUpdateSummary();
}

// ── custom permission picker ───────────────────────────────────────
function udxPermFilterFn(q) {
  var lq = (q || '').toLowerCase().trim();
  document.querySelectorAll('#udxPickBox .udx-pgroup').forEach(function(grp) {
    var any = false;
    grp.querySelectorAll('.udx-perm').forEach(function(lbl) {
      var cb = lbl.querySelector('.udx-perm-cb');
      var hay = ((cb.getAttribute('data-name') || '') + ' ' + (cb.getAttribute('data-short') || '') + ' ' + cb.value).toLowerCase();
      var match = !lq || hay.indexOf(lq) >= 0;
      lbl.classList.toggle('udx-nomatch', !match);
      if (match) any = true;
    });
    grp.classList.toggle('udx-nomatch', !any);
    if (lq && any) grp.setAttribute('open', 'open');
  });
}
function udxToggleGroup(ev, el) {
  ev.preventDefault(); ev.stopPropagation();
  var grp = el.closest('.udx-pgroup');
  var cbs = Array.prototype.slice.call(grp.querySelectorAll('.udx-perm:not(.udx-nomatch) .udx-perm-cb'));
  var allOn = cbs.length > 0 && cbs.every(function(cb){ return cb.checked; });
  cbs.forEach(function(cb){ cb.checked = !allOn; });
  udxPermChanged();
}
function udxPermChanged() {
  var checked = Array.prototype.slice.call(document.querySelectorAll('#udxCustomPerms .udx-perm-cb:checked'));
  var countEl = document.getElementById('udxPermCount');
  if (countEl) countEl.textContent = checked.length + ' selected';
  var strip = document.getElementById('udxSelChips');
  if (strip) {
    strip.innerHTML = '';
    if (!checked.length) {
      strip.style.display = 'none';
    } else {
      strip.style.display = '';
      checked.forEach(function(cb) {
        var chip = document.createElement('span'); chip.className = 'udx-selchip';
        var lbl  = document.createElement('span'); lbl.textContent = cb.getAttribute('data-name') || cb.value;
        var x    = document.createElement('button'); x.type = 'button'; x.innerHTML = '&#10005;';
        x.onclick = function(){ cb.checked = false; udxPermChanged(); };
        chip.appendChild(lbl); chip.appendChild(x); strip.appendChild(chip);
      });
    }
  }
  document.querySelectorAll('#udxPickBox .udx-pgroup').forEach(function(grp){
    var cbs   = Array.prototype.slice.call(grp.querySelectorAll('.udx-perm-cb'));
    var allOn = cbs.length > 0 && cbs.every(function(c){ return c.checked; });
    var sa    = grp.querySelector('.udx-selall');
    if (sa) sa.textContent = allOn ? 'Clear all' : 'Select all';
  });
  udxUpdateSummary();
}

// ── live summary line ──────────────────────────────────────────────
function udxUpdateSummary() {
  var el = document.getElementById('udxSummary');
  if (!el) return;
  var scopeTxt;
  if (udxIsEdit) {
    scopeTxt = document.getElementById('udxEditScopeDisplay').textContent || 'this item';
  } else {
    var st = document.querySelector('input[name="scopeType"]:checked');
    if (st && st.value === 'item') {
      var sp = document.getElementById('udxScopeValue').value;
      scopeTxt = sp ? sp : '(no item selected yet)';
    } else {
      scopeTxt = 'all of Jenkins';
    }
  }
  var whatTxt;
  if (udxGrantWhat === 'custom') {
    var n = document.querySelectorAll('#udxCustomPerms .udx-perm-cb:checked').length;
    whatTxt = '<b>' + n + '</b> custom permission' + (n === 1 ? '' : 's');
  } else {
    var sel = document.getElementById('udxRoleSelect');
    var opt = sel.options[sel.selectedIndex];
    whatTxt = (opt && opt.value !== 'CUSTOM') ? ('the <b>' + udxEsc(opt.value) + '</b> role') : 'a role';
  }
  var atTxt = '';
  var st2 = document.querySelector('input[name="scopeType"]:checked');
  var scoped = udxIsEdit || (st2 && st2.value === 'item');
  if (scoped) {
    var at = document.querySelector('input[name="accessType"]:checked');
    atTxt = (at && at.value === 'JIT') ? ', <b>JIT</b>' : ', standing';
  }
  var exp = document.getElementById('udxExpiresAt').value;
  var expTxt = exp ? (', expires ' + udxEsc(exp)) : ', no expiry';
  el.innerHTML = 'Granting ' + whatTxt + ' on <b>' + udxEsc(scopeTxt) + '</b>' + atTxt + expTxt + '.';
}

function udxAccessTypeChange(val) {
  document.getElementById('udxJitFields').style.display = val === 'JIT' ? '' : 'none';
  udxUpdateSummary();
}

// ── Approver chip input ──────────────────────────────────────────────
var udxApproverXhr = null;
var udxSuggestBase = '';  // set from #udxBootstrap in init()

function udxChipAdd(value, chipsId, hiddenId) {
  if (!value || !value.trim()) return;
  var v = value.trim();
  var hidden = document.getElementById(hiddenId);
  var existing = hidden.value ? hidden.value.split(',').map(function(s){return s.trim();}) : [];
  if (existing.indexOf(v) >= 0) return; // no duplicates
  existing.push(v);
  hidden.value = existing.join(',');
  var chips = document.getElementById(chipsId);
  var chip = document.createElement('span');
  chip.style.cssText = 'display:inline-flex;align-items:center;gap:4px;padding:2px 8px 2px 10px;background:var(--oau-info-bg);color:var(--oau-info-fg);border-radius:12px;font-size:0.78em;font-weight:600;';
  // Build with DOM APIs (textContent / addEventListener), never innerHTML+onclick, so a
  // chip value containing quotes or angle brackets can't inject markup or break out of
  // the handler attribute.
  var chipLabel = document.createElement('span');
  chipLabel.textContent = v;
  var chipBtn = document.createElement('button');
  chipBtn.type = 'button';
  chipBtn.style.cssText = 'border:none;background:none;cursor:pointer;color:var(--oau-info-fg);font-size:0.9em;padding:0 0 0 2px;line-height:1;';
  chipBtn.innerHTML = '&#10005;';
  chipBtn.addEventListener('click', function() { udxChipRemove(v, chipsId, hiddenId); });
  chip.appendChild(chipLabel);
  chip.appendChild(chipBtn);
  chips.appendChild(chip);
  udxUpdateChipValidation(chipsId, hiddenId);
}

function udxChipRemove(value, chipsId, hiddenId) {
  var hidden = document.getElementById(hiddenId);
  var existing = hidden.value ? hidden.value.split(',').map(function(s){return s.trim();}) : [];
  hidden.value = existing.filter(function(s){return s !== value;}).join(',');
  var chips = document.getElementById(chipsId);
  chips.innerHTML = '';
  hidden.value.split(',').forEach(function(v) { if (v.trim()) udxChipAdd(v.trim(), chipsId, hiddenId); });
  // Re-add chips (simpler than finding the right chip element)
  // Actually, we already cleared chips, so re-render from hidden value
  // But we just added them above in the forEach... wait, udxChipAdd also sets hidden.value.
  // The trick: clear hidden first, then re-add.
  hidden.value = '';
  chips.innerHTML = '';
  existing.filter(function(s){return s.trim() && s.trim() !== value;}).forEach(function(v) {
    udxChipAdd(v.trim(), chipsId, hiddenId);
  });
  udxUpdateChipValidation(chipsId, hiddenId);
}

function udxClearChips(chipsId, hiddenId) {
  document.getElementById(chipsId).innerHTML = '';
  document.getElementById(hiddenId).value = '';
  udxUpdateChipValidation(chipsId, hiddenId);
}

function udxUpdateChipValidation(chipsId, hiddenId) {
  var hidden = document.getElementById(hiddenId);
  var count = hidden.value ? hidden.value.split(',').filter(function(s){return s.trim();}).length : 0;
  var validId = hiddenId.replace('Approvers','ApproverValidation');
  var valid = document.getElementById(validId);
  if (valid) valid.style.display = count < 2 && count > 0 ? '' : 'none';
}

function udxChipKeydown(event, inputId, dropId, chipsId, hiddenId) {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault();
    var input = document.getElementById(inputId);
    var v = input.value.trim().replace(/,$/, '');
    if (v) { udxChipAdd(v, chipsId, hiddenId); input.value = ''; }
    var drop = document.getElementById(dropId);
    if (drop) drop.style.display = 'none';
  }
}

function udxChipSearch(q, dropId, chipsId, hiddenId) {
  var drop = document.getElementById(dropId);
  if (!q.trim()) { drop.style.display = 'none'; return; }
  if (udxApproverXhr) udxApproverXhr.abort();
  udxApproverXhr = new XMLHttpRequest();
  var url = udxSuggestBase + '?q=' + encodeURIComponent(q);
  udxApproverXhr.open('GET', url, true);
  udxApproverXhr.onload = function() {
    if (udxApproverXhr.status !== 200) { drop.style.display = 'none'; return; }
    var users;
    try { users = JSON.parse(udxApproverXhr.responseText); } catch(e) { drop.style.display = 'none'; return; }
    if (!users || !users.length) { drop.style.display = 'none'; return; }
    drop.innerHTML = '';
    users.forEach(function(u) {
      var isGroup = u.type === 'group';
      var row = document.createElement('div');
      row.className = 'udx-suggest-row';
      var icon = document.createElement('span');
      icon.className = 'udx-suggest-icon';
      icon.innerHTML = isGroup
        ? '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>'
        : '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>';
      icon.style.color = isGroup ? '#7c3aed' : 'var(--blue)';
      icon.style.flexShrink = '0';
      row.appendChild(icon);
      var text = document.createElement('div');
      text.style.cssText = 'display:flex;flex-direction:column;gap:1px;min-width:0;';
      var nameEl = document.createElement('span');
      nameEl.className = 'udx-suggest-id';
      nameEl.textContent = u.id;
      text.appendChild(nameEl);
      if (u.displayName) {
        var dn = document.createElement('span');
        dn.className = 'udx-suggest-dn';
        dn.textContent = u.displayName;
        text.appendChild(dn);
      }
      row.appendChild(text);
      var chipEl = document.createElement('span');
      chipEl.className = 'udx-suggest-chip';
      chipEl.textContent = isGroup ? 'group' : 'user';
      chipEl.style.background = isGroup ? 'color-mix(in srgb, #7c3aed 13%, transparent)' : 'var(--oau-info-bg)';
      chipEl.style.color      = isGroup ? '#7c3aed' : 'var(--oau-info-fg)';
      row.appendChild(chipEl);
      row.onmousedown = function() {
        var inputEl = drop.previousElementSibling;
        udxChipAdd(u.id, chipsId, hiddenId);
        if (inputEl) inputEl.value = '';
        drop.style.display = 'none';
      };
      drop.appendChild(row);
    });
    drop.style.display = '';
  };
  udxApproverXhr.send();
}
function udxUpdateExpiryHint(val, hintId) {
  var hint = document.getElementById(hintId);
  if (!hint) return;
  if (!val) { hint.textContent = 'No expiry — access stays until revoked.'; hint.style.color = ''; return; }
  var d = new Date(val + 'T00:00:00');
  var opts = { year:'numeric', month:'long', day:'numeric' };
  hint.textContent = 'Access will be revoked after ' + d.toLocaleDateString(undefined, opts) + '.';
  hint.style.color = '';
}

function udxScopeChange(val) {
  var itemDiv    = document.getElementById('udxItemInput');
  var scopeVal   = document.getElementById('udxScopeValue');
  var globalHid  = document.getElementById('udxScopeGlobal');
  var atSection  = document.getElementById('udxAccessTypeSection');
  if (val === 'item') {
    itemDiv.style.display = '';
    scopeVal.setAttribute('name', 'scope');
    globalHid.removeAttribute('name');
    if (atSection) atSection.style.display = '';
  } else {
    itemDiv.style.display = 'none';
    globalHid.setAttribute('name', 'scope');
    scopeVal.removeAttribute('name');
    if (atSection) atSection.style.display = 'none';
    // Reset to STANDING when switching to global
    document.querySelectorAll('input[name="accessType"]').forEach(function(r) { r.checked = r.value === 'STANDING'; });
    udxAccessTypeChange('STANDING');
  }
  udxUpdateSummary();
}

// --- Scope picker modal ---
var udxScopeItems = [];  // [{path, type}, ...]  set from #udxBootstrap in init()
var udxScopeTree  = null;
var udxPending    = null;
var udxArrowIdx   = -1;
// House-style inline SVG icons for the scope picker (replace emoji; currentColor = theme-aware).
var UDX_IC_FOLDER = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:block"><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>';
var UDX_IC_JOB    = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:block"><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>';

function udxBuildTree(items) {
  var root = {};
  items.forEach(function(item) {
    var parts = item.path.split('/');
    var node = root;
    var cur = '';
    parts.forEach(function(part) {
      cur = cur ? cur + '/' + part : part;
      if (!node[part]) node[part] = { _path: cur, _type: item.type, _children: {} };
      node = node[part]._children;
    });
  });
  return root;
}

function udxModalOpen() {
  if (!udxScopeTree) udxScopeTree = udxBuildTree(udxScopeItems);
  udxPending  = document.getElementById('udxScopeValue').value || null;
  udxArrowIdx = -1;
  var modal = document.getElementById('udxScopeModal');
  modal.style.opacity = '1';
  modal.style.pointerEvents = 'auto';
  var box = modal.querySelector('[data-modal-box]');
  if (box) {
    var btn = document.getElementById('udxScopeBtn');
    if (btn) {
      var r = btn.getBoundingClientRect();
      var dx = (r.left + r.width  / 2) - window.innerWidth  / 2;
      var dy = (r.top  + r.height / 2) - window.innerHeight / 2;
      box.style.setProperty('--genie-dx', dx.toFixed(1) + 'px');
      box.style.setProperty('--genie-dy', dy.toFixed(1) + 'px');
    }
    box.style.animation = 'none'; box.offsetHeight; box.style.animation = 'udxModalIn 0.45s cubic-bezier(0.34,1.56,0.64,1)';
  }
  var search = document.getElementById('udxModalSearch');
  search.value = '';
  udxModalFilter('');
  udxModalFooter();
  if (udxPending) {
    setTimeout(function() {
      var rows = document.querySelectorAll('.udx-mrow');
      for (var i = 0; i < rows.length; i++) {
        if (rows[i].getAttribute('data-path') === udxPending) {
          rows[i].scrollIntoView({ block: 'center' });
          break;
        }
      }
    }, 30);
  }
  setTimeout(function(){ search.focus(); }, 60);
  document.addEventListener('keydown', udxModalKey);
}

function udxModalClose() {
  var modal = document.getElementById('udxScopeModal');
  var box   = modal.querySelector('[data-modal-box]');
  document.removeEventListener('keydown', udxModalKey);
  udxArrowIdx = -1;
  modal.style.pointerEvents = 'none';
  if (box) {
    var btn = document.getElementById('udxScopeBtn');
    if (btn) {
      var r = btn.getBoundingClientRect();
      var dx = (r.left + r.width  / 2) - window.innerWidth  / 2;
      var dy = (r.top  + r.height / 2) - window.innerHeight / 2;
      box.style.setProperty('--genie-dx', dx.toFixed(1) + 'px');
      box.style.setProperty('--genie-dy', dy.toFixed(1) + 'px');
    }
    box.style.animation = 'udxModalOut 0.28s ease-in forwards';
    setTimeout(function() { modal.style.opacity = '0'; }, 240);
  } else {
    modal.style.opacity = '0';
  }
}

function udxModalConfirm() {
  if (!udxPending) return;
  var selItem = udxScopeItems.find(function(i){ return i.path === udxPending; });
  document.getElementById('udxScopeValue').value = udxPending;
  var lbl  = document.getElementById('udxScopeLbl');
  var icon = document.getElementById('udxScopeIcon');
  lbl.textContent  = udxPending;
  lbl.style.color  = 'inherit';
  icon.innerHTML = (selItem && selItem.type === 'folder') ? UDX_IC_FOLDER : UDX_IC_JOB;
  document.getElementById('udxScopeClear').style.display = 'inline-flex';
  udxModalClose();
  udxUpdateSummary();
}

function udxScopeClearFn() {
  document.getElementById('udxScopeValue').value = '';
  var lbl  = document.getElementById('udxScopeLbl');
  var icon = document.getElementById('udxScopeIcon');
  lbl.textContent  = 'Select a job or folder…';
  lbl.style.color  = '';
  icon.innerHTML = UDX_IC_FOLDER;
  document.getElementById('udxScopeClear').style.display = 'none';
  udxPending = null;
  udxUpdateSummary();
}

function udxModalKey(e) {
  if (e.key === 'Escape') { udxModalClose(); return; }
  var rows = Array.from(document.querySelectorAll('.udx-mrow'));
  if (!rows.length) return;
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    udxArrowIdx = Math.min(udxArrowIdx + 1, rows.length - 1);
    udxArrowFocus(rows);
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    udxArrowIdx = Math.max(udxArrowIdx - 1, 0);
    udxArrowFocus(rows);
  } else if (e.key === 'Enter') {
    if (udxArrowIdx >= 0 && rows[udxArrowIdx]) {
      udxModalPick(rows[udxArrowIdx].getAttribute('data-path'));
    } else if (udxPending) {
      udxModalConfirm();
    }
  }
}

function udxArrowFocus(rows) {
  rows.forEach(function(r, i) {
    var isFocus = i === udxArrowIdx;
    var isSel   = r.getAttribute('data-path') === udxPending;
    r.style.background = isFocus ? 'var(--oau-info-bg)' : (isSel ? 'var(--oau-info-bg)' : '');
    r.style.outline    = isFocus ? '2px solid var(--blue)' : 'none';
  });
  if (rows[udxArrowIdx]) rows[udxArrowIdx].scrollIntoView({ block: 'nearest' });
}

function udxModalPick(path) {
  udxPending  = path;
  udxArrowIdx = -1;
  udxModalFooter();
  document.querySelectorAll('.udx-mrow').forEach(function(r) {
    var sel = r.getAttribute('data-path') === path;
    r.style.background = sel ? 'var(--oau-info-bg)' : '';
    r.style.fontWeight = sel ? '600'     : '';
    r.style.color      = sel ? 'var(--oau-info-fg)' : '';
    r.style.outline    = 'none';
    r.style.borderLeftColor = sel ? 'var(--blue)' : 'transparent';
  });
}

function udxModalFooter() {
  var lbl = document.getElementById('udxModalLbl');
  var btn = document.getElementById('udxModalOk');
  if (udxPending) {
    lbl.textContent = udxPending;
    lbl.style.color = 'var(--text-color)';
    btn.removeAttribute('disabled');
  } else {
    lbl.textContent = 'Nothing selected';
    lbl.style.color = '#aaa';
    btn.setAttribute('disabled', 'disabled');
  }
}

function udxModalFilter(q) {
  var body = document.getElementById('udxModalBody');
  body.innerHTML = '';
  udxArrowIdx = -1;
  if (!q) {
    udxModalRenderTree(udxScopeTree, body, 0);
    return;
  }
  var lq   = q.toLowerCase();
  var hits = udxScopeItems.filter(function(i){ return i.path.toLowerCase().indexOf(lq) >= 0; });

  // Result count
  var countBar = document.createElement('div');
  countBar.style.cssText = 'padding:8px 24px 4px;font-size:0.75em;color:#bbb;font-weight:600;letter-spacing:0.05em;text-transform:uppercase;';
  countBar.textContent = hits.length + (hits.length === 1 ? ' result' : ' results');
  body.appendChild(countBar);

  if (!hits.length) {
    body.innerHTML = '<div style="padding:48px;text-align:center;color:#ccc;font-size:0.9em;">No matching jobs or folders</div>';
    return;
  }

  hits.forEach(function(item) {
    var path   = item.path;
    var isFolder = item.type === 'folder';
    var parts  = path.split('/');
    var name   = parts[parts.length - 1];
    var parent = parts.length > 1 ? parts.slice(0, -1).join('/') : '';
    var sel    = path === udxPending;

    var row = document.createElement('div');
    row.className = 'udx-mrow';
    row.setAttribute('data-path', path);
    row.style.cssText = 'display:flex;align-items:center;gap:12px;padding:10px 24px;cursor:pointer;'
      + 'border-left:4px solid transparent;transition:background 0.12s;'
      + (sel ? 'background:var(--oau-info-bg);border-left-color:var(--blue);' : '');
    row.onmouseenter = function(){ if(this.getAttribute('data-path')!==udxPending) this.style.background='var(--oau-neutral-bg)'; };
    row.onmouseleave = function(){ if(this.getAttribute('data-path')!==udxPending) this.style.background=''; };

    // Icon
    var iconEl = document.createElement('span');
    iconEl.style.cssText = 'flex-shrink:0;display:inline-flex;';
    iconEl.innerHTML = isFolder
      ? '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--warning-color)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>'
      : '<svg width="18" height="18" viewBox="0 0 24 24" fill="var(--blue)" stroke="none"><polygon points="6,3 20,12 6,21"/></svg>';

    // Text block
    var textEl = document.createElement('div');
    textEl.style.cssText = 'display:flex;flex-direction:column;gap:2px;min-width:0;';

    // Name with match highlight
    var lo = name.toLowerCase().indexOf(lq);
    var nameEl = document.createElement('div');
    nameEl.style.cssText = 'font-size:0.93em;font-weight:' + (sel ? '700' : '500') + ';color:' + (sel ? 'var(--oau-info-fg)' : 'var(--text-color)') + ';white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
    if (lo >= 0) {
      nameEl.innerHTML = name.substring(0,lo)
        + '<mark style="background:rgba(26,115,232,0.15);color:var(--oau-info-fg);border-radius:2px;padding:0 2px;">'
        + name.substring(lo, lo+q.length) + '</mark>'
        + name.substring(lo+q.length);
    } else {
      nameEl.textContent = name;
    }

    // Parent path
    if (parent) {
      var parentEl = document.createElement('div');
      parentEl.style.cssText = 'font-size:0.78em;color:#bbb;font-family:monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
      parentEl.textContent = parent;
      textEl.appendChild(nameEl);
      textEl.appendChild(parentEl);
    } else {
      textEl.appendChild(nameEl);
    }

    row.appendChild(iconEl);
    row.appendChild(textEl);
    row.onclick = function(){ udxModalPick(path); };
    body.appendChild(row);
  });
}

function udxModalRenderTree(nodeMap, container, depth) {
  Object.keys(nodeMap).sort(function(a,b){ return a.toLowerCase().localeCompare(b.toLowerCase()); })
    .forEach(function(key) {
      var n       = nodeMap[key];
      var hasKids = Object.keys(n._children).length > 0;
      var sel     = n._path === udxPending;
      var pad     = 20 + depth * 20;

      var row = document.createElement('div');
      row.className = 'udx-mrow';
      row.setAttribute('data-path', n._path);
      row.style.cssText = 'display:flex;align-items:center;gap:8px;padding:10px 20px 10px ' + pad + 'px;'
        + 'cursor:pointer;font-size:0.93em;border-left:4px solid transparent;transition:background 0.12s;'
        + (sel ? 'background:var(--oau-info-bg);font-weight:600;color:var(--oau-info-fg);border-left-color:var(--blue);' : '');
      row.onmouseenter = function(){ if(this.getAttribute('data-path')!==udxPending) this.style.background='var(--oau-neutral-bg)'; };
      row.onmouseleave = function(){ if(this.getAttribute('data-path')!==udxPending) this.style.background = sel ? 'var(--oau-info-bg)' : ''; };

      if (hasKids) {
        var arr = document.createElement('span');
        arr.style.cssText = 'width:18px;height:18px;flex-shrink:0;display:inline-flex;align-items:center;'
          + 'justify-content:center;cursor:pointer;transition:transform 0.15s;color:#bbb;';
        arr.innerHTML = '<svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M3 2l4 3-4 3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        var kids = document.createElement('div');
        var shouldExpand = udxPending && (udxPending === n._path || udxPending.indexOf(n._path + '/') === 0);
        kids.style.display = shouldExpand ? '' : 'none';
        if (shouldExpand) arr.style.transform = 'rotate(90deg)';
        arr.onclick = function(e) {
          e.stopPropagation();
          var open = kids.style.display !== 'none';
          kids.style.display = open ? 'none' : '';
          arr.style.transform = open ? '' : 'rotate(90deg)';
        };
        var icon = document.createElement('span');
        icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--warning-color)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>';
        icon.style.cssText = 'flex-shrink:0;display:inline-flex;';
        var lbl = document.createElement('span'); lbl.textContent = key;
        lbl.style.cssText = 'min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        row.appendChild(arr); row.appendChild(icon); row.appendChild(lbl);
        row.onclick = function(){ udxModalPick(n._path); };
        container.appendChild(row);
        udxModalRenderTree(n._children, kids, depth + 1);
        container.appendChild(kids);
      } else {
        var spacer = document.createElement('span'); spacer.style.cssText = 'width:18px;flex-shrink:0;';
        var icon = document.createElement('span');
        icon.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="var(--blue)" stroke="none"><polygon points="6,3 20,12 6,21"/></svg>';
        icon.style.cssText = 'flex-shrink:0;display:inline-flex;';
        var lbl = document.createElement('span'); lbl.textContent = key;
        lbl.style.cssText = 'min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        row.appendChild(spacer); row.appendChild(icon); row.appendChild(lbl);
        row.onclick = function(){ udxModalPick(n._path); };
        container.appendChild(row);
      }
    });
}

// ── Init (server values + load-time DOM wiring) ─────────────────────
function udxInit() {
  // Server values relocated out of this .js into #udxBootstrap data-* attributes.
  var boot = document.getElementById('udxBootstrap');
  if (boot) {
    udxUserIsGlobalAdmin = boot.getAttribute('data-global-admin') === 'true';
    udxSuggestBase = boot.getAttribute('data-suggest-base') || '';
    try { udxScopeItems = JSON.parse(boot.getAttribute('data-scope-items') || '[]'); }
    catch (e) { udxScopeItems = []; }
  }

  // Build permission metadata from the rendered picker (escaping-safe).
  document.querySelectorAll('#udxCustomPerms .udx-perm-cb').forEach(function(cb) {
    udxPermMeta[cb.value] = {
      name:  cb.getAttribute('data-name')  || cb.value,
      group: cb.getAttribute('data-group') || 'Other',
      short: cb.getAttribute('data-short') || cb.value
    };
    cb.addEventListener('change', udxPermChanged);
  });

  // Block a scoped grant for a user who already has global Admin (add only).
  var form = document.getElementById('udxGrantForm');
  if (form) {
    form.addEventListener('submit', function(e) {
      if (udxConflictConfirmed || udxIsEdit) return;
      var scopeType = document.querySelector('input[name="scopeType"]:checked');
      if (scopeType && scopeType.value === 'item' && udxUserIsGlobalAdmin) {
        e.preventDefault();
        var sidEl = document.querySelector('#udxGrantForm input[name="sid"]');
        document.getElementById('udxConflictSid').textContent = sidEl ? sidEl.value : '';
        document.getElementById('udxConflictModal').style.display = 'flex';
      }
    });
  }

  // Render expiry badges from ISO strings
  document.querySelectorAll('.udx-expiry-badge').forEach(function(el) {
    var iso = el.getAttribute('data-expires');
    if (!iso) return;
    var exp = new Date(iso);
    var now = new Date();
    var diffDays = Math.ceil((exp - now) / 86400000);
    var dateStr = exp.toLocaleDateString(undefined, { month:'short', day:'numeric', year:'numeric' });
    if (diffDays < 0) {
      el.textContent = 'Expired';
      el.classList.add('udx-expiry-soon');
    } else if (diffDays <= 7) {
      el.textContent = diffDays === 0 ? 'Expires today' : 'Expires in ' + diffDays + 'd';
      el.classList.add('udx-expiry-soon');
    } else {
      el.textContent = dateStr;
    }
  });

  // ── Event-handler wiring (CSP: no inline on* attributes) ────────────
  // Each listener reproduces exactly what the old inline handler did.
  function wire(id, evt, fn) {
    var el = document.getElementById(id);
    if (el) el.addEventListener(evt, fn);
  }

  // Temp-password modal
  wire('udxCopyTmpBtn', 'click', copyTmpPwd);
  wire('udxTmpPwdDone', 'click', function() {
    var ov = document.getElementById('tmpPwdOverlay');
    if (ov) ov.style.display = 'none';
  });

  // Edit-details modal: open / close (native accounts only; elements absent otherwise)
  wire('udxEditDetailsBtn', 'click', function() {
    var ov = document.getElementById('udxEditOverlay');
    if (ov) {
      ov.style.display = 'flex';
      var fn = document.getElementById('udxEditFullName');
      if (fn) fn.focus();
    }
  });
  wire('udxEditCancel', 'click', function() {
    var ov = document.getElementById('udxEditOverlay');
    if (ov) ov.style.display = 'none';
  });
  var editOv = document.getElementById('udxEditOverlay');
  if (editOv) {
    editOv.addEventListener('click', function(e) {
      if (e.target === editOv) editOv.style.display = 'none';  // click backdrop to dismiss
    });
  }

  // Reset-password: open the in-app confirm modal (no browser confirm dialog)
  wire('udxResetPwBtn', 'click', function() {
    var ov = document.getElementById('udxResetOverlay');
    if (ov) ov.style.display = 'flex';
  });
  wire('udxResetCancel', 'click', function() {
    var ov = document.getElementById('udxResetOverlay');
    if (ov) ov.style.display = 'none';
  });
  wire('udxResetConfirm', 'click', function() {
    var form = document.getElementById('udxResetPwForm');
    if (form) form.submit();
  });
  var resetOv = document.getElementById('udxResetOverlay');
  if (resetOv) {
    resetOv.addEventListener('click', function(e) {
      if (e.target === resetOv) resetOv.style.display = 'none';  // click backdrop to dismiss
    });
  }

  // Add Access buttons (header + empty state)
  document.querySelectorAll('.js-udx-add').forEach(function(btn) {
    btn.addEventListener('click', udxShowAdd);
  });

  // Edit assignment buttons (per-row; args come from data-* attributes)
  document.querySelectorAll('.js-udx-edit').forEach(function(btn) {
    btn.addEventListener('click', function() {
      var perms = [];
      try { perms = JSON.parse(this.dataset.perms || '[]'); } catch (e) { perms = []; }
      udxShowEdit(this.dataset.scope, this.dataset.scopeDisplay, this.dataset.role, perms,
        this.dataset.expires, this.dataset.accessType, this.dataset.approverGroup,
        this.dataset.maxDuration, this.dataset.approvalTimeout);
    });
  });

  // Revoke assignment buttons (per-row; destructive) — confirmation handled by the shared in-app
  // modal (oau/oauConfirm.js) via each button's data-confirm attribute; no native confirm() here.

  // Drawer open / close
  wire('udxDrawerScrim', 'click', udxCloseDrawer);
  wire('udxDrawerClose', 'click', udxCloseDrawer);
  wire('udxDrawerCancel', 'click', udxCloseDrawer);

  // Scope-type radios (value matches the old literal arg)
  document.querySelectorAll('.js-udx-scopetype').forEach(function(r) {
    r.addEventListener('change', function() { udxScopeChange(this.value); });
  });

  // Scope picker open / clear
  wire('udxScopeBtn', 'click', udxModalOpen);
  wire('udxScopeClear', 'click', udxScopeClearFn);

  // What-to-grant mode cards
  wire('udxModeRole', 'click', function() { udxSetMode('role'); });
  wire('udxModeCustom', 'click', function() { udxSetMode('custom'); });

  // Role select
  wire('udxRoleSelect', 'change', udxRoleChange);

  // Custom permission filter
  wire('udxPermFilter', 'input', function() { udxPermFilterFn(this.value); });

  // Select-all per permission group
  document.querySelectorAll('.udx-selall').forEach(function(sa) {
    sa.addEventListener('click', function(e) { udxToggleGroup(e, this); });
  });

  // Expiry date
  wire('udxExpiresAt', 'change', function() {
    udxUpdateExpiryHint(this.value, 'udxExpiryHint');
    udxUpdateSummary();
  });

  // Access-type radios (value matches the old literal arg)
  document.querySelectorAll('.js-udx-accesstype').forEach(function(r) {
    r.addEventListener('change', function() { udxAccessTypeChange(this.value); });
  });

  // Approver chip input
  wire('udxApproverInput', 'input', function() {
    udxChipSearch(this.value, 'udxApproverDrop', 'udxApproverChips', 'udxApprovers');
  });
  wire('udxApproverInput', 'keydown', function(e) {
    udxChipKeydown(e, 'udxApproverInput', 'udxApproverDrop', 'udxApproverChips', 'udxApprovers');
  });
  wire('udxApproverInput', 'blur', function() {
    setTimeout(function() {
      var d = document.getElementById('udxApproverDrop');
      if (d) d.style.display = 'none';
    }, 200);
  });

  // JIT number inputs: a wheel scroll must not nudge the value
  wire('udxMaxDuration', 'wheel', function() { this.blur(); });
  wire('udxApprovalTimeout', 'wheel', function() { this.blur(); });

  // Global-admin conflict modal
  wire('udxConflictCancelBtn', 'click', udxConflictCancel);
  wire('udxConflictProceedBtn', 'click', udxConflictProceed);

  // Scope picker modal: backdrop click, close (with hover), search, cancel, confirm
  wire('udxScopeModal', 'click', function(e) { if (e.target === this) udxModalClose(); });
  wire('udxScopeModalClose', 'click', udxModalClose);
  var scopeModalCloseBtn = document.getElementById('udxScopeModalClose');
  if (scopeModalCloseBtn) {
    scopeModalCloseBtn.addEventListener('mouseenter', function() {
      this.style.background = 'var(--oau-card-border)'; this.style.color = '#333';
    });
    scopeModalCloseBtn.addEventListener('mouseleave', function() {
      this.style.background = 'none'; this.style.color = '#bbb';
    });
  }
  wire('udxModalSearch', 'input', function() { udxModalFilter(this.value); });
  var modalSearch = document.getElementById('udxModalSearch');
  if (modalSearch) {
    modalSearch.addEventListener('focus', function() {
      this.style.borderColor = 'var(--blue)';
      this.style.boxShadow = '0 0 0 3px rgba(26,115,232,0.12)';
      this.style.background = '#fff';
    });
    modalSearch.addEventListener('blur', function() {
      this.style.borderColor = 'var(--oau-card-border)';
      this.style.boxShadow = 'none';
      this.style.background = 'var(--oau-neutral-bg)';
    });
  }
  wire('udxModalCancel', 'click', udxModalClose);
  wire('udxModalOk', 'click', udxModalConfirm);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', udxInit);
} else {
  udxInit();
}
