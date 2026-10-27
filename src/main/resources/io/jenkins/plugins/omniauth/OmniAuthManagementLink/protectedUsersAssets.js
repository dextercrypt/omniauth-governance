/*
 * Protected Users page behaviour: row toggle, live badge count, search filter,
 * and relative-timestamp rendering.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.protectedUsersAssets"/>
 * Externalised from protectedUsers.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * oauFilterUsers / oauClearFilter are referenced by on* handlers in the jelly, so they are
 * exposed on window. Adjuncts load in <head>, so DOM access is deferred until DOMContentLoaded.
 */
(function () {
  function init() {

    function applyState(row, on) {
      var track = row.querySelector('.oau-track');
      var thumb = row.querySelector('.oau-thumb');
      var label = row.querySelector('.oau-tlabel');
      row.dataset.protected = on ? 'true' : 'false';
      if (on) {
        row.style.background = 'var(--oau-info-bg)';
        row.classList.add('oau-user-row--on');
        track.style.background = 'var(--blue)';
        thumb.style.left = '22px';
        label.textContent = 'Protected';
        label.style.color = 'var(--blue)';
      } else {
        row.style.background = '';
        row.classList.remove('oau-user-row--on');
        track.style.background = 'var(--oau-card-border)';
        thumb.style.left = '2px';
        label.textContent = 'Protected';
        label.style.color = 'transparent';
      }
    }

    document.querySelectorAll('.oau-user-row').forEach(function (row) {
      row.addEventListener('click', function (e) {
        e.preventDefault(); // stop label from natively re-toggling the checkbox
        var chk = row.querySelector('.oau-chk');
        chk.checked = !chk.checked;
        applyState(row, chk.checked);
        refreshBadge();
        document.getElementById('changeIndicator').style.display = '';
      });
    });

    function refreshBadge() {
      var checked = document.querySelectorAll('.oau-chk:checked').length;
      var total   = document.querySelectorAll('.oau-chk').length;
      var el = document.getElementById('protectedBadge');
      if (el) el.textContent = checked + ' of ' + total + ' protected';
    }

    window.oauFilterUsers = function (query) {
      var q = query.toLowerCase().trim();
      var rows = document.querySelectorAll('#userList .oau-user-row');
      var visible = 0;
      rows.forEach(function (row) {
        var match = !q || (row.dataset.uid || '').toLowerCase().indexOf(q) >= 0
                        || (row.dataset.name || '').toLowerCase().indexOf(q) >= 0;
        row.style.display = match ? '' : 'none';
        if (match) visible++;
      });
      document.getElementById('noMatch').style.display = (visible === 0 && q.length > 0) ? '' : 'none';
      document.getElementById('clearFilter').style.display = q.length > 0 ? '' : 'none';
    };

    window.oauClearFilter = function () {
      document.getElementById('userFilter').value = '';
      oauFilterUsers('');
    };

    document.querySelectorAll('.oau-reltime').forEach(function (el) {
      var iso = el.dataset.iso;
      if (!iso) return;
      try {
        var days = Math.floor((Date.now() - new Date(iso)) / 86400000);
        var txt = days === 0 ? 'Today'
                : days === 1 ? 'Yesterday'
                : days < 30  ? days + 'd ago'
                : days < 365 ? Math.floor(days / 30) + 'mo ago'
                :              Math.floor(days / 365) + 'y ago';
        el.textContent = 'Last login: ' + txt;
      } catch (_) {}
    });

    refreshBadge();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
