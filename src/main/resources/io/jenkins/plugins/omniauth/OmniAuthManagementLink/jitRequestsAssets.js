/*
 * JIT-requests page behaviour: the deny-reason modal.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.jitRequestsAssets"/>
 * Externalised from jitRequests.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * Functions stay global because the jelly's onclick handlers call them. They only
 * touch the DOM when invoked (user interaction), so no load-time guard is needed.
 */
function openDenyModal(requestId, userId, scope) {
  document.getElementById('jitDenyRequestId').value = requestId;
  document.getElementById('jitDenyMeta').textContent = userId + ' → ' + scope;
  document.getElementById('jitDenyModal').style.display = '';
  document.getElementById('jitDenyBackdrop').style.display = '';
}
function closeDenyModal() {
  document.getElementById('jitDenyModal').style.display = 'none';
  document.getElementById('jitDenyBackdrop').style.display = 'none';
}

// Wire former inline on* handlers (CSP compliance). Runs once at DOM ready.
function jitInit() {
  document.querySelectorAll('.js-jit-deny').forEach(function(el) {
    el.addEventListener('click', function() {
      openDenyModal(this.dataset.requestId, this.dataset.requesterId, this.dataset.scope);
    });
  });
  // Revoke active JIT — confirm guards submit
  document.querySelectorAll('.js-jit-revoke').forEach(function(el) {
    el.addEventListener('click', function(e) { if (!confirm(this.dataset.confirm)) e.preventDefault(); });
  });
  var backdrop = document.getElementById('jitDenyBackdrop');
  if (backdrop) backdrop.addEventListener('click', function() { closeDenyModal(); });
  var cancel = document.getElementById('jitDenyCancel');
  if (cancel) cancel.addEventListener('click', function() { closeDenyModal(); });
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', jitInit);
} else {
  jitInit();
}
