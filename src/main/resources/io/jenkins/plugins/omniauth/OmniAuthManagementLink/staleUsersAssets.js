/*
 * Stale Users page behaviour: history drawer toggle, cleanup/delete modals,
 * and relative-timestamp rendering.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.staleUsersAssets"/>
 * Externalised from staleUsers.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * Modal functions are referenced by on* handlers in the jelly, so they stay global.
 * Adjuncts load in <head>, so load-time DOM access is deferred until DOMContentLoaded.
 */

// ── Cleanup modal ──────────────────────────────────────────────────
function oauShowCleanupModal(isDryRun) {
  document.getElementById('oauModalDryRun').style.display = isDryRun ? '' : 'none';
  document.getElementById('oauModalLive').style.display   = isDryRun ? 'none' : '';
  document.getElementById('oauCleanupModal').style.display = 'flex';
}
function oauCloseCleanupModal() {
  document.getElementById('oauCleanupModal').style.display = 'none';
}
function oauSubmitCleanup() {
  oauCloseCleanupModal();
  document.getElementById('oauCleanupForm').submit();
}

// ── Delete modal ───────────────────────────────────────────────────
function oauShowDeleteModal(btn) {
  var userId     = btn.dataset.userid;
  var crumbField = btn.dataset.crumbField;
  var crumbValue = btn.dataset.crumbValue;

  document.getElementById('oauDeleteUserId').textContent     = userId;
  document.getElementById('oauDeleteUserIdHint').textContent  = userId;
  document.getElementById('oauDeleteUserIdInput').value       = userId;
  document.getElementById('oauDeleteCrumbField').name         = crumbField;
  document.getElementById('oauDeleteCrumbField').value        = crumbField;
  document.getElementById('oauDeleteCrumbValue').name         = crumbField;
  document.getElementById('oauDeleteCrumbValue').value        = crumbValue;
  document.getElementById('oauDeleteConfirmInput').value      = '';
  document.getElementById('oauDeleteConfirmBtn').disabled     = true;
  document.getElementById('oauDeleteModal').style.display     = 'flex';
  setTimeout(function(){ document.getElementById('oauDeleteConfirmInput').focus(); }, 100);

  document.getElementById('oauDeleteConfirmBtn').onclick = function() {
    oauCloseDeleteModal();
    document.getElementById('oauDeleteForm').submit();
  };
}
function oauCloseDeleteModal() {
  document.getElementById('oauDeleteModal').style.display = 'none';
  document.getElementById('oauDeleteConfirmInput').value  = '';
  document.getElementById('oauDeleteConfirmBtn').disabled = true;
}
function oauCheckDeleteConfirm() {
  var input    = document.getElementById('oauDeleteConfirmInput').value;
  var expected = document.getElementById('oauDeleteUserIdInput').value;
  document.getElementById('oauDeleteConfirmBtn').disabled = (input !== expected);
}

// Close on Escape
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') { oauCloseCleanupModal(); oauCloseDeleteModal(); }
});

(function () {
  function init() {
    // ── History drawer toggle ──────────────────────────────────────────
    (function() {
      var toggle  = document.getElementById('oauHistoryToggle');
      var body    = document.getElementById('oauHistoryBody');
      var chevron = document.getElementById('oauHistoryChevron');
      function doToggle() {
        var open = body.style.display !== 'none';
        body.style.display = open ? 'none' : '';
        chevron.style.transform = open ? '' : 'rotate(180deg)';
      }
      toggle.addEventListener('click', doToggle);
      toggle.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); doToggle(); }
      });
    })();

    // ── Relative timestamps ────────────────────────────────────────────
    document.querySelectorAll('.oau-reltime').forEach(function(el) {
      var iso = el.dataset.iso;
      if (!iso) return;
      try {
        var days = Math.floor((Date.now() - new Date(iso)) / 86400000);
        var txt = days === 0 ? 'Today'
                : days === 1 ? 'Yesterday'
                : days < 30  ? days + 'd ago'
                : days < 365 ? Math.floor(days / 30) + 'mo ago'
                :              Math.floor(days / 365) + 'y ago';
        el.textContent = txt;
        el.title = new Date(iso).toLocaleString();
      } catch(_) {}
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
