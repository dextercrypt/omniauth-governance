/*
 * Stale Users page behaviour: history drawer toggle, cleanup/delete modals,
 * and relative-timestamp rendering.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.rotogate.RotogateManagementLink.staleUsersAssets"/>
 * Externalised from staleUsers.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * Modal functions are referenced by on* handlers in the jelly, so they stay global.
 * Adjuncts load in <head>, so load-time DOM access is deferred until DOMContentLoaded.
 */

// ── Cleanup modal ──────────────────────────────────────────────────
function rgShowCleanupModal(isDryRun) {
  document.getElementById('rgModalDryRun').style.display = isDryRun ? '' : 'none';
  document.getElementById('rgModalLive').style.display   = isDryRun ? 'none' : '';
  document.getElementById('rgCleanupModal').style.display = 'flex';
}
function rgCloseCleanupModal() {
  document.getElementById('rgCleanupModal').style.display = 'none';
}
function rgSubmitCleanup() {
  rgCloseCleanupModal();
  document.querySelector('form[name="rgCleanupForm"]').submit();
}

// ── Delete modal ───────────────────────────────────────────────────
function rgShowDeleteModal(btn) {
  var userId     = btn.dataset.userid;
  var crumbField = btn.dataset.crumbField;
  var crumbValue = btn.dataset.crumbValue;

  document.getElementById('rgDeleteUserId').textContent     = userId;
  document.getElementById('rgDeleteUserIdHint').textContent  = userId;
  document.getElementById('rgDeleteUserIdInput').value       = userId;
  document.getElementById('rgDeleteCrumbField').name         = crumbField;
  document.getElementById('rgDeleteCrumbField').value        = crumbField;
  document.getElementById('rgDeleteCrumbValue').name         = crumbField;
  document.getElementById('rgDeleteCrumbValue').value        = crumbValue;
  document.getElementById('rgDeleteConfirmInput').value      = '';
  document.getElementById('rgDeleteConfirmBtn').disabled     = true;
  document.getElementById('rgDeleteModal').style.display     = 'flex';
  setTimeout(function(){ document.getElementById('rgDeleteConfirmInput').focus(); }, 100);

  document.getElementById('rgDeleteConfirmBtn').onclick = function() {
    rgCloseDeleteModal();
    document.getElementById('rgDeleteForm').submit();
  };
}
function rgCloseDeleteModal() {
  document.getElementById('rgDeleteModal').style.display = 'none';
  document.getElementById('rgDeleteConfirmInput').value  = '';
  document.getElementById('rgDeleteConfirmBtn').disabled = true;
}
function rgCheckDeleteConfirm() {
  var input    = document.getElementById('rgDeleteConfirmInput').value;
  var expected = document.getElementById('rgDeleteUserIdInput').value;
  document.getElementById('rgDeleteConfirmBtn').disabled = (input !== expected);
}

// Close on Escape
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') { rgCloseCleanupModal(); rgCloseDeleteModal(); }
});

(function () {
  function init() {
    // ── Run-cleanup buttons ─────────────────────────────────────────────
    document.querySelectorAll('.js-rg-cleanup').forEach(function(el) {
      el.addEventListener('click', function() {
        rgShowCleanupModal(this.dataset.dryrun === 'true');
      });
    });

    // ── Delete buttons (per-row) ────────────────────────────────────────
    document.querySelectorAll('.js-rg-delete').forEach(function(el) {
      el.addEventListener('click', function() {
        rgShowDeleteModal(this);
      });
    });

    // ── Cleanup modal controls ──────────────────────────────────────────
    var cleanupModal = document.getElementById('rgCleanupModal');
    if (cleanupModal) {
      cleanupModal.addEventListener('click', function(e) {
        if (e.target === this) rgCloseCleanupModal();
      });
    }
    document.querySelectorAll('.js-rg-cleanup-cancel').forEach(function(el) {
      el.addEventListener('click', rgCloseCleanupModal);
    });
    document.querySelectorAll('.js-rg-cleanup-submit').forEach(function(el) {
      el.addEventListener('click', rgSubmitCleanup);
    });

    // ── Delete modal controls ───────────────────────────────────────────
    var deleteModal = document.getElementById('rgDeleteModal');
    if (deleteModal) {
      deleteModal.addEventListener('click', function(e) {
        if (e.target === this) rgCloseDeleteModal();
      });
    }
    var deleteCancel = document.getElementById('rgDeleteCancelBtn');
    if (deleteCancel) deleteCancel.addEventListener('click', rgCloseDeleteModal);
    var deleteConfirmInput = document.getElementById('rgDeleteConfirmInput');
    if (deleteConfirmInput) deleteConfirmInput.addEventListener('input', rgCheckDeleteConfirm);

    // ── History drawer toggle ──────────────────────────────────────────
    (function() {
      var toggle  = document.getElementById('rgHistoryToggle');
      var body    = document.getElementById('rgHistoryBody');
      var chevron = document.getElementById('rgHistoryChevron');
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
    document.querySelectorAll('.rg-reltime').forEach(function(el) {
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
