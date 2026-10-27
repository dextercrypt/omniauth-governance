/*
 * Backup & Restore page behaviour (import/rollback plan modal).
 * Loaded as a Jenkins adjunct:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.backupAssets"/>
 * Externalised from backup.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * Contains NO server EL — the crumb is read from the page <head> data attributes, as before.
 * Adjuncts load in <head>, so DOM access is deferred until the document is parsed.
 */
(function () {
  function init() {
    // Read the crumb from the page head (Jenkins renders it there) — reliable, unlike
    // interpolating an EL expression into inline script.
    var head = document.head;
    var CRUMB_FIELD = (head && head.getAttribute('data-crumb-header')) || 'Jenkins-Crumb';
    var CRUMB = (head && head.getAttribute('data-crumb-value')) || '';

    var fileInput = document.getElementById('bkFile');
    var configArea = document.getElementById('bkConfig');
    var importForm = document.getElementById('bkImportForm');
    var previewBtn = document.getElementById('bkPreviewBtn');
    var overlay = document.getElementById('bkPlanOverlay');
    var planBody = document.getElementById('bkPlanBody');
    var applyBtn = document.getElementById('bkPlanApply');
    var cancelBtn = document.getElementById('bkPlanCancel');

    // Load a chosen file into the textarea.
    if (fileInput && configArea) {
      fileInput.addEventListener('change', function () {
        var f = fileInput.files && fileInput.files[0];
        if (!f) { return; }
        var reader = new FileReader();
        reader.onload = function (e) { configArea.value = e.target.result; };
        reader.readAsText(f);
      });
    }

    function el(tag, cls, text) {
      var e = document.createElement(tag);
      if (cls) e.className = cls;
      if (text !== undefined) e.textContent = text;
      return e;
    }

    var ADD = 'var(--success-color, #1f883d)';
    var REM = 'var(--error-color, #cf222e)';
    var CHG = 'var(--warning-color, #9a6700)';

    function mkLine(text, color) {
      var li = el('li', null, text);
      li.style.color = color;
      li.style.fontFamily = 'monospace';
      li.style.fontSize = '0.85em';
      li.style.listStyle = 'none';
      li.style.marginBottom = '2px';
      return li;
    }

    function changeList(title, grp) {
      var total = grp.added.length + grp.removed.length + grp.changed.length;
      if (total === 0) return null;
      var wrap = el('div');
      wrap.style.marginBottom = '14px';
      var h = el('div', null, title);
      h.style.fontWeight = '600';
      h.style.marginBottom = '6px';
      wrap.appendChild(h);
      var ul = el('ul');
      ul.style.margin = '0';
      ul.style.paddingLeft = '4px';
      ul.style.listStyle = 'none';
      grp.added.forEach(function (name)   { ul.appendChild(mkLine('+ ' + name, ADD)); });
      grp.removed.forEach(function (name) { ul.appendChild(mkLine('- ' + name, REM)); });
      grp.changed.forEach(function (item) {
        var li = mkLine('~ ' + item.name, CHG);
        if (item.lines && item.lines.length) {
          var sub = el('ul');
          sub.style.margin = '2px 0 4px 0';
          sub.style.paddingLeft = '18px';
          sub.style.listStyle = 'none';
          item.lines.forEach(function (l) {
            var sli = el('li');
            sli.style.fontFamily = 'monospace';
            sli.style.fontSize = '0.8em';
            sli.style.marginBottom = '2px';
            var lab = el('span', null, l.label + ': ');
            lab.style.color = 'var(--text-color-secondary)';
            sli.appendChild(lab);
            (l.segs || []).forEach(function (sg) {
              var sp = el('span', null, sg.text);
              sp.style.color = (sg.tone === 'add') ? ADD
                             : (sg.tone === 'remove') ? REM
                             : 'var(--text-color-secondary)';
              if (sg.tone === 'add' || sg.tone === 'remove') sp.style.fontWeight = '600';
              sli.appendChild(sp);
            });
            sub.appendChild(sli);
          });
          li.appendChild(sub);
        }
        ul.appendChild(li);
      });
      wrap.appendChild(ul);
      return wrap;
    }

    function renderPlan(data) {
      planBody.innerHTML = '';
      if (data.empty) {
        planBody.appendChild(el('p', null,
          'No changes — the live configuration already matches.'));
        return;
      }
      var summary = el('p', null, 'Summary: ' + data.summary);
      summary.style.marginTop = '0';
      summary.style.color = 'var(--text-color-secondary)';
      planBody.appendChild(summary);

      [['Roles', data.roles], ['Groups', data.groups], ['Assignments', data.assignments]]
        .forEach(function (pair) {
          var node = changeList(pair[0], pair[1]);
          if (node) planBody.appendChild(node);
        });

      if (data.settings && data.settings.length) {
        var wrap = el('div');
        var h = el('div', null, 'Settings');
        h.style.fontWeight = '600';
        h.style.marginBottom = '6px';
        wrap.appendChild(h);
        data.settings.forEach(function (s) {
          var li = el('div', null, s.key + ': ' + (s.from || '(empty)') + ' → ' + (s.to || '(empty)'));
          li.style.fontFamily = 'monospace';
          li.style.fontSize = '0.85em';
          li.style.color = 'var(--warning-color, #9a6700)';
          li.style.marginBottom = '2px';
          wrap.appendChild(li);
        });
        planBody.appendChild(wrap);
      }
    }

    function showOverlay() { overlay.style.display = 'flex'; }
    function hideOverlay() { overlay.style.display = 'none'; }

    var titleEl = document.getElementById('bkPlanTitle');
    var subtitleEl = document.getElementById('bkPlanSubtitle');
    var pendingApply = null;

    // Open the plan modal with a rendered diff and a specific apply action.
    function openPlan(data, opts) {
      if (titleEl) titleEl.textContent = opts.title;
      if (subtitleEl) subtitleEl.textContent = opts.subtitle;
      if (applyBtn) applyBtn.textContent = opts.applyLabel;
      renderPlan(data);
      pendingApply = opts.onApply;
      showOverlay();
    }

    // POST to a preview endpoint (crumb as header + body), parse the plan JSON, hand it back.
    function fetchPlan(url, params, onReady, onDone) {
      var body = new URLSearchParams();
      body.append(CRUMB_FIELD, CRUMB);
      Object.keys(params).forEach(function (k) { body.append(k, params[k]); });
      var headers = {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'};
      headers[CRUMB_FIELD] = CRUMB;
      fetch(url, {method: 'POST', credentials: 'same-origin', headers: headers, body: body.toString()})
        .then(function (r) { return r.text().then(function (t) { return {status: r.status, text: t}; }); })
        .then(function (res) {
          if (onDone) onDone();
          var data;
          try { data = JSON.parse(res.text); }
          catch (e) {
            alert('Could not compute the plan (server returned HTTP ' + res.status
                  + '). Try reloading the page and trying again.');
            return;
          }
          if (!data.ok) { alert('Could not read that configuration: ' + (data.error || 'unknown error')); return; }
          onReady(data);
        }).catch(function (err) {
          if (onDone) onDone();
          alert('Could not compute the plan: ' + err);
        });
    }

    if (previewBtn) {
      previewBtn.addEventListener('click', function () {
        if (!configArea.value.trim()) {
          alert('Choose a backup file or paste its contents first.');
          return;
        }
        previewBtn.disabled = true;
        fetchPlan('previewImport', {config: configArea.value}, function (data) {
          openPlan(data, {
            title: 'Import plan',
            subtitle: 'These changes will be applied to the live configuration. A restore point is saved first.',
            applyLabel: 'Apply import',
            onApply: function () { importForm.submit(); }
          });
        }, function () { previewBtn.disabled = false; });
      });
    }

    if (applyBtn) applyBtn.addEventListener('click', function () { if (pendingApply) pendingApply(); });
    if (cancelBtn) cancelBtn.addEventListener('click', hideOverlay);
    if (overlay) overlay.addEventListener('click', function (e) { if (e.target === overlay) hideOverlay(); });

    // Each roll-back previews the restore point first (same modal), then applies on confirm.
    // form.submit() bypasses this submit handler, so Apply won't re-trigger the preview.
    var revertForms = document.querySelectorAll('.bkRevertForm');
    Array.prototype.forEach.call(revertForms, function (form) {
      var btn = form.querySelector('button[type=submit]');
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        var when = form.getAttribute('data-when') || 'this point';
        var snap = form.querySelector('input[name=snapshot]');
        if (btn) btn.disabled = true;
        fetchPlan('previewRevert', {snapshot: snap ? snap.value : ''}, function (data) {
          openPlan(data, {
            title: 'Rollback plan',
            subtitle: 'Roll back to the restore point from ' + when
                      + '. These changes will be applied; a new restore point is saved first.',
            applyLabel: 'Apply rollback',
            onApply: function () { form.submit(); }
          });
        }, function () { if (btn) btn.disabled = false; });
      });
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
