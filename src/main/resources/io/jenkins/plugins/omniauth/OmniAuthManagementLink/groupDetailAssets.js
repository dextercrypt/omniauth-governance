/*
 * Group-detail page behaviour: add/edit access panels, expiry hints, and the
 * scope-picker modal (tree + search).
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.groupDetailAssets"/>
 * Externalised from groupDetail.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * The one dynamic value (the scope list JSON) is read from a
 * <script type="application/json" id="udxScopeData"> island in the page, since a
 * static adjunct is cached and cannot contain Jelly EL expressions.
 * Functions stay global because the jelly's onclick handlers call them.
 */
function udxShowAdd() {
  document.getElementById('udxEditPanel').style.display = 'none';
  document.getElementById('udxAddPanel').style.display = '';
  document.getElementById('udxAddPanel').scrollIntoView({ behavior:'smooth', block:'nearest' });
  var sel = document.getElementById('udxRoleSelect');
  if (sel) udxRoleChange(sel.value);
}
function udxHideAdd() { document.getElementById('udxAddPanel').style.display = 'none'; }
function udxShowEdit(scope, scopeDisplay, roleId, customPermsArr, expiresAt) {
  document.getElementById('udxAddPanel').style.display = 'none';
  document.getElementById('udxEditScope').value = scope;
  document.getElementById('udxEditScopeDisplay').textContent = scopeDisplay || scope;
  var sel = document.getElementById('udxEditRoleSelect');
  for (var i = 0; i < sel.options.length; i++) {
    if (sel.options[i].value === roleId) { sel.selectedIndex = i; break; }
  }
  udxEditRoleChange(roleId);
  document.querySelectorAll('.udx-edit-perm-cb').forEach(function(cb) {
    cb.checked = Array.isArray(customPermsArr) && customPermsArr.indexOf(cb.value) >= 0;
  });
  var dateInput = document.getElementById('udxEditExpiresAt');
  dateInput.value = expiresAt ? expiresAt.substring(0,10) : '';
  udxUpdateExpiryHint(dateInput.value, 'udxEditExpiryHint');
  document.getElementById('udxEditPanel').style.display = '';
  document.getElementById('udxEditPanel').scrollIntoView({ behavior:'smooth', block:'nearest' });
}
function udxHideEdit() { document.getElementById('udxEditPanel').style.display = 'none'; }
function udxEditRoleChange(val) {
  var p = document.getElementById('udxEditCustomPerms');
  p.style.display = val === 'CUSTOM' ? '' : 'none';
  if (val !== 'CUSTOM') document.querySelectorAll('.udx-edit-perm-cb').forEach(function(cb){ cb.checked=false; });
}
function udxRoleChange(val) {
  var p = document.getElementById('udxCustomPerms');
  p.style.display = val === 'CUSTOM' ? '' : 'none';
  if (val !== 'CUSTOM') p.querySelectorAll('input[type=checkbox]').forEach(function(cb){ cb.checked=false; });
}
function udxUpdateExpiryHint(val, hintId) {
  var hint = document.getElementById(hintId); if (!hint) return;
  if (!val) { hint.textContent='Access will be automatically revoked at end of this day.'; hint.style.color='#999'; return; }
  var d = new Date(val+'T00:00:00');
  hint.textContent='Access will be revoked after '+d.toLocaleDateString(undefined,{year:'numeric',month:'long',day:'numeric'})+'.';
  hint.style.color='var(--warning-color)';
}
function udxScopeChange(val) {
  var itemDiv=document.getElementById('udxItemInput');
  var scopeVal=document.getElementById('udxScopeValue');
  var globalHid=document.getElementById('udxScopeGlobal');
  if (val==='item') { itemDiv.style.display=''; scopeVal.setAttribute('name','scope'); globalHid.removeAttribute('name'); }
  else { itemDiv.style.display='none'; globalHid.setAttribute('name','scope'); scopeVal.removeAttribute('name'); }
}

var udxScopeItems = [];
var udxScopeTree=null; var udxPending=null; var udxArrowIdx=-1;
// House-style inline SVG icons for the scope picker (replace emoji; currentColor = theme-aware).
var UDX_IC_FOLDER = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:block"><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>';
var UDX_IC_JOB    = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:block"><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>';
function udxBuildTree(items) {
  var root={};
  items.forEach(function(item){ var parts=item.path.split('/'); var node=root; var cur=''; parts.forEach(function(part){ cur=cur?cur+'/'+part:part; if(!node[part])node[part]={_path:cur,_type:item.type,_children:{}}; node=node[part]._children; }); });
  return root;
}
function udxModalOpen() {
  if(!udxScopeTree) udxScopeTree=udxBuildTree(udxScopeItems);
  udxPending=document.getElementById('udxScopeValue').value||null; udxArrowIdx=-1;
  var modal=document.getElementById('udxScopeModal'); modal.style.opacity='1'; modal.style.pointerEvents='auto';
  var box=modal.querySelector('[data-modal-box]');
  if(box){ var btn=document.getElementById('udxScopeBtn'); if(btn){var r=btn.getBoundingClientRect();box.style.setProperty('--genie-dx',(r.left+r.width/2-window.innerWidth/2).toFixed(1)+'px');box.style.setProperty('--genie-dy',(r.top+r.height/2-window.innerHeight/2).toFixed(1)+'px');} box.style.animation='none';box.offsetHeight;box.style.animation='udxModalIn 0.45s cubic-bezier(0.34,1.56,0.64,1)'; }
  var search=document.getElementById('udxModalSearch'); search.value=''; udxModalFilter(''); udxModalFooter();
  setTimeout(function(){search.focus();},60); document.addEventListener('keydown',udxModalKey);
}
function udxModalClose() {
  var modal=document.getElementById('udxScopeModal'); var box=modal.querySelector('[data-modal-box]');
  document.removeEventListener('keydown',udxModalKey); udxArrowIdx=-1; modal.style.pointerEvents='none';
  if(box){var btn=document.getElementById('udxScopeBtn');if(btn){var r=btn.getBoundingClientRect();box.style.setProperty('--genie-dx',(r.left+r.width/2-window.innerWidth/2).toFixed(1)+'px');box.style.setProperty('--genie-dy',(r.top+r.height/2-window.innerHeight/2).toFixed(1)+'px');}box.style.animation='udxModalOut 0.28s ease-in forwards';setTimeout(function(){modal.style.opacity='0';},240);}else{modal.style.opacity='0';}
}
function udxModalConfirm() {
  if(!udxPending) return;
  var selItem=udxScopeItems.find(function(i){return i.path===udxPending;});
  document.getElementById('udxScopeValue').value=udxPending;
  document.getElementById('udxScopeLbl').textContent=udxPending; document.getElementById('udxScopeLbl').style.color='inherit';
  document.getElementById('udxScopeIcon').innerHTML=(selItem&&selItem.type==='folder')?UDX_IC_FOLDER:UDX_IC_JOB;
  document.getElementById('udxScopeClear').style.display='inline-flex'; udxModalClose();
}
function udxScopeClearFn() {
  document.getElementById('udxScopeValue').value=''; document.getElementById('udxScopeLbl').textContent='Select a job or folder…'; document.getElementById('udxScopeLbl').style.color='#aaa'; document.getElementById('udxScopeIcon').innerHTML=UDX_IC_FOLDER; document.getElementById('udxScopeClear').style.display='none'; udxPending=null;
}
function udxModalKey(e) {
  if(e.key==='Escape'){udxModalClose();return;}
  var rows=Array.from(document.querySelectorAll('.udx-mrow')); if(!rows.length) return;
  if(e.key==='ArrowDown'){e.preventDefault();udxArrowIdx=Math.min(udxArrowIdx+1,rows.length-1);udxArrowFocus(rows);}
  else if(e.key==='ArrowUp'){e.preventDefault();udxArrowIdx=Math.max(udxArrowIdx-1,0);udxArrowFocus(rows);}
  else if(e.key==='Enter'){if(udxArrowIdx>=0&&rows[udxArrowIdx])udxModalPick(rows[udxArrowIdx].getAttribute('data-path'));else if(udxPending)udxModalConfirm();}
}
function udxArrowFocus(rows){ rows.forEach(function(r,i){var f=i===udxArrowIdx;var s=r.getAttribute('data-path')===udxPending;r.style.background=f?'var(--oau-info-bg)':(s?'var(--oau-info-bg)':'');r.style.outline=f?'2px solid var(--blue)':'none';}); if(rows[udxArrowIdx])rows[udxArrowIdx].scrollIntoView({block:'nearest'}); }
function udxModalPick(path){ udxPending=path;udxArrowIdx=-1;udxModalFooter();document.querySelectorAll('.udx-mrow').forEach(function(r){var s=r.getAttribute('data-path')===path;r.style.background=s?'var(--oau-info-bg)':'';r.style.fontWeight=s?'600':'';r.style.color=s?'var(--oau-info-fg)':'';r.style.outline='none';r.style.borderLeftColor=s?'var(--blue)':'transparent';}); }
function udxModalFooter(){ var lbl=document.getElementById('udxModalLbl');var btn=document.getElementById('udxModalOk'); if(udxPending){lbl.textContent=udxPending;lbl.style.color='var(--text-color)';btn.removeAttribute('disabled');}else{lbl.textContent='Nothing selected';lbl.style.color='#aaa';btn.setAttribute('disabled','disabled');} }
function udxModalFilter(q) {
  var body=document.getElementById('udxModalBody');body.innerHTML='';udxArrowIdx=-1;
  if(!q){udxModalRenderTree(udxScopeTree,body,0);return;}
  var lq=q.toLowerCase(); var hits=udxScopeItems.filter(function(i){return i.path.toLowerCase().indexOf(lq)>=0;});
  if(!hits.length){body.innerHTML='<div style="padding:48px;text-align:center;color:#ccc;font-size:0.9em;">No matching jobs or folders</div>';return;}
  hits.forEach(function(item){
    var path=item.path;var parts=path.split('/');var name=parts[parts.length-1];var parent=parts.length>1?parts.slice(0,-1).join('/'):'';var sel=path===udxPending;
    var row=document.createElement('div');row.className='udx-mrow';row.setAttribute('data-path',path);
    row.style.cssText='display:flex;align-items:center;gap:12px;padding:10px 24px;cursor:pointer;border-left:4px solid transparent;transition:background 0.12s;'+(sel?'background:var(--oau-info-bg);border-left-color:var(--blue);':'');
    row.onmouseenter=function(){if(this.getAttribute('data-path')!==udxPending)this.style.background='var(--oau-neutral-bg)';};
    row.onmouseleave=function(){if(this.getAttribute('data-path')!==udxPending)this.style.background='';};
    var iconEl=document.createElement('span');iconEl.style.cssText='flex-shrink:0;display:inline-flex;';
    iconEl.innerHTML=item.type==='folder'?'<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--warning-color)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>':'<svg width="18" height="18" viewBox="0 0 24 24" fill="var(--blue)" stroke="none"><polygon points="6,3 20,12 6,21"/></svg>';
    var textEl=document.createElement('div');textEl.style.cssText='display:flex;flex-direction:column;gap:2px;min-width:0;';
    var nameEl=document.createElement('div');nameEl.style.cssText='font-size:0.93em;font-weight:'+(sel?'700':'500')+';color:'+(sel?'var(--oau-info-fg)':'var(--text-color)')+';white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
    var lo=name.toLowerCase().indexOf(lq);
    if(lo>=0)nameEl.innerHTML=name.substring(0,lo)+'<mark style="background:rgba(26,115,232,0.15);color:var(--oau-info-fg);border-radius:2px;padding:0 2px;">'+name.substring(lo,lo+q.length)+'</mark>'+name.substring(lo+q.length);
    else nameEl.textContent=name;
    if(parent){var pe=document.createElement('div');pe.style.cssText='font-size:0.78em;color:#bbb;font-family:monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';pe.textContent=parent;textEl.appendChild(nameEl);textEl.appendChild(pe);}else textEl.appendChild(nameEl);
    row.appendChild(iconEl);row.appendChild(textEl);row.onclick=function(){udxModalPick(path);};body.appendChild(row);
  });
}
function udxModalRenderTree(nodeMap,container,depth) {
  Object.keys(nodeMap).sort(function(a,b){return a.toLowerCase().localeCompare(b.toLowerCase());}).forEach(function(key){
    var n=nodeMap[key];var hasKids=Object.keys(n._children).length>0;var sel=n._path===udxPending;var pad=20+depth*20;
    var row=document.createElement('div');row.className='udx-mrow';row.setAttribute('data-path',n._path);
    row.style.cssText='display:flex;align-items:center;gap:8px;padding:10px 20px 10px '+pad+'px;cursor:pointer;font-size:0.93em;border-left:4px solid transparent;transition:background 0.12s;'+(sel?'background:var(--oau-info-bg);font-weight:600;color:var(--oau-info-fg);border-left-color:var(--blue);':'');
    row.onmouseenter=function(){if(this.getAttribute('data-path')!==udxPending)this.style.background='var(--oau-neutral-bg)';};
    row.onmouseleave=function(){if(this.getAttribute('data-path')!==udxPending)this.style.background=sel?'var(--oau-info-bg)':'';};
    if(hasKids){
      var arr=document.createElement('span');arr.style.cssText='width:18px;height:18px;flex-shrink:0;display:inline-flex;align-items:center;justify-content:center;cursor:pointer;transition:transform 0.15s;color:#bbb;';arr.innerHTML='<svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M3 2l4 3-4 3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
      var kids=document.createElement('div');var se=udxPending&&(udxPending===n._path||udxPending.indexOf(n._path+'/')===0);kids.style.display=se?'':'none';if(se)arr.style.transform='rotate(90deg)';
      arr.onclick=function(e){e.stopPropagation();var open=kids.style.display!=='none';kids.style.display=open?'none':'';arr.style.transform=open?'':'rotate(90deg)';};
      var icon=document.createElement('span');icon.innerHTML='<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--warning-color)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>';icon.style.cssText='flex-shrink:0;display:inline-flex;';
      var lbl=document.createElement('span');lbl.textContent=key;lbl.style.cssText='min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
      row.appendChild(arr);row.appendChild(icon);row.appendChild(lbl);row.onclick=function(){udxModalPick(n._path);};container.appendChild(row);udxModalRenderTree(n._children,kids,depth+1);container.appendChild(kids);
    }else{
      var spacer=document.createElement('span');spacer.style.cssText='width:18px;flex-shrink:0;';
      var icon=document.createElement('span');icon.innerHTML='<svg width="16" height="16" viewBox="0 0 24 24" fill="var(--blue)" stroke="none"><polygon points="6,3 20,12 6,21"/></svg>';icon.style.cssText='flex-shrink:0;display:inline-flex;';
      var lbl=document.createElement('span');lbl.textContent=key;lbl.style.cssText='min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
      row.appendChild(spacer);row.appendChild(icon);row.appendChild(lbl);row.onclick=function(){udxModalPick(n._path);};container.appendChild(row);
    }
  });
}

// Wire all former inline on* handlers (CSP compliance). Runs once at DOM ready.
function udxWireGroupDetail() {
  // + Add Access buttons (section header + empty-state)
  document.querySelectorAll('.js-udx-add').forEach(function(el) {
    el.addEventListener('click', function() { udxShowAdd(); });
  });
  // Remove-group form confirmation is handled by the shared in-app modal (oau/oauConfirm.js) via the
  // form's data-confirm attribute; no native confirm() here.
  // Per-row Edit buttons
  document.querySelectorAll('.js-udx-edit').forEach(function(el) {
    el.addEventListener('click', function() {
      var perms = [];
      try { perms = JSON.parse(this.dataset.perms); } catch (err) { perms = []; }
      udxShowEdit(this.dataset.scope, this.dataset.scopeDisplay, this.dataset.role, perms, this.dataset.expires);
    });
  });
  // Per-row Revoke buttons — confirmation handled by the shared in-app modal (oau/oauConfirm.js)
  // via each button's data-confirm attribute; no native confirm() here.
  // Scope-type radios
  document.querySelectorAll('.js-udx-scope-radio').forEach(function(el) {
    el.addEventListener('change', function() { udxScopeChange(this.value); });
  });
  var scopeBtn = document.getElementById('udxScopeBtn');
  if (scopeBtn) {
    scopeBtn.addEventListener('click', function() { udxModalOpen(); });
    scopeBtn.addEventListener('mouseenter', function() {
      this.style.borderColor = 'var(--blue)';
      this.style.boxShadow = '0 0 0 3px rgba(26,115,232,0.12)';
    });
    scopeBtn.addEventListener('mouseleave', function() {
      this.style.borderColor = 'var(--input-border,var(--oau-card-border))';
      this.style.boxShadow = 'none';
    });
  }
  var scopeClear = document.getElementById('udxScopeClear');
  if (scopeClear) scopeClear.addEventListener('click', function() { udxScopeClearFn(); });
  var roleSelect = document.getElementById('udxRoleSelect');
  if (roleSelect) roleSelect.addEventListener('change', function() { udxRoleChange(this.value); });
  var expiresAt = document.getElementById('udxExpiresAt');
  if (expiresAt) expiresAt.addEventListener('change', function() { udxUpdateExpiryHint(this.value, 'udxExpiryHint'); });
  var addCancel = document.getElementById('udxAddCancel');
  if (addCancel) addCancel.addEventListener('click', function() { udxHideAdd(); });
  var editRoleSelect = document.getElementById('udxEditRoleSelect');
  if (editRoleSelect) editRoleSelect.addEventListener('change', function() { udxEditRoleChange(this.value); });
  var editExpiresAt = document.getElementById('udxEditExpiresAt');
  if (editExpiresAt) editExpiresAt.addEventListener('change', function() { udxUpdateExpiryHint(this.value, 'udxEditExpiryHint'); });
  var editCancel = document.getElementById('udxEditCancel');
  if (editCancel) editCancel.addEventListener('click', function() { udxHideEdit(); });
  var modal = document.getElementById('udxScopeModal');
  if (modal) modal.addEventListener('click', function(e) { if (e.target === this) udxModalClose(); });
  var modalCloseX = document.getElementById('udxModalCloseX');
  if (modalCloseX) modalCloseX.addEventListener('click', function() { udxModalClose(); });
  var modalSearch = document.getElementById('udxModalSearch');
  if (modalSearch) modalSearch.addEventListener('input', function() { udxModalFilter(this.value); });
  var modalCancel = document.getElementById('udxModalCancel');
  if (modalCancel) modalCancel.addEventListener('click', function() { udxModalClose(); });
  var modalOk = document.getElementById('udxModalOk');
  if (modalOk) modalOk.addEventListener('click', function() { udxModalConfirm(); });
}

function udxInitGroupDetail() {
  // Dynamic scope list is served as a JSON island in the page (no EL in this cached adjunct).
  var dataEl = document.getElementById('udxScopeData');
  if (dataEl) { try { udxScopeItems = JSON.parse(dataEl.textContent); } catch (e) { udxScopeItems = []; } }

  udxWireGroupDetail();

  document.querySelectorAll('.udx-expiry-badge').forEach(function(el) {
    var iso=el.getAttribute('data-expires'); if(!iso) return;
    var exp=new Date(iso); var now=new Date(); var diffDays=Math.ceil((exp-now)/86400000);
    var dateStr=exp.toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'});
    if(diffDays<0){el.textContent='Expired';el.classList.add('udx-expiry-soon');}
    else if(diffDays<=7){el.textContent=diffDays===0?'Expires today':'Expires in '+diffDays+'d';el.classList.add('udx-expiry-soon');}
    else{el.textContent=dateStr;}
  });
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', udxInitGroupDetail);
} else {
  udxInitGroupDetail();
}
