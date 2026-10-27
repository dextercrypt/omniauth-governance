/*
 * Active Sessions page behaviour: the revoke-session confirmation modal.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.sessionsAssets"/>
 * Externalised from sessions.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * The functions below are referenced by on* handlers in the jelly, so they stay global;
 * they only touch the DOM when invoked, so no DOMContentLoaded guard is required.
 */
function oauShowRevokeModal(btn) {
  var sessionId  = btn.dataset.sessionid;
  var userId     = btn.dataset.userid;
  var crumbField = btn.dataset.crumbField;
  var crumbValue = btn.dataset.crumbValue;

  document.getElementById('oauRevokeUserId').textContent       = userId;
  document.getElementById('oauRevokeSessionIdInput').value      = sessionId;
  document.getElementById('oauRevokeCrumbField').name           = crumbField;
  document.getElementById('oauRevokeCrumbField').value          = crumbField;
  document.getElementById('oauRevokeCrumbValue').name           = crumbField;
  document.getElementById('oauRevokeCrumbValue').value          = crumbValue;
  document.getElementById('oauRevokeModal').style.display       = 'flex';

  document.getElementById('oauRevokeConfirmBtn').onclick = function() {
    oauCloseRevokeModal();
    document.getElementById('oauRevokeForm').submit();
  };
}
function oauCloseRevokeModal() {
  document.getElementById('oauRevokeModal').style.display = 'none';
}
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') oauCloseRevokeModal();
});
