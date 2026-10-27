/*
 * SOS Recovery Codes section behaviour.
 * Loaded as a Jenkins adjunct:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.sosCodesSectionAssets"/>
 * Externalised from sosCodesSection.jelly's inline <script> for CSP compliance (Jenkins hosting).
 * The sosGenerate/sosCopy/sosDownload/sosContinue functions stay GLOBAL because the jelly's inline
 * onclick= handlers call them. The one server value (it.sosEnrollMode) is no longer interpolated
 * into the JS — it is read from a <script type="application/json" id="sosData"> island.
 * Load-time addEventListener wiring is deferred until the DOM is parsed (adjuncts load in <head>).
 */
var sosEnrollMode = false;
var sosCodes = [];

function sosGenerate(isRegen) {
  // Destructive regenerate → styled confirm modal; first-time generate → straight through.
  if (isRegen) { document.getElementById('sos-regen-modal').style.display = 'flex'; return; }
  sosDoGenerate();
}

function sosDoGenerate() {
  var base = window.location.pathname.split('/manage/')[0];
  fetch(base + '/crumbIssuer/api/json')
    .then(function(r){ return r.json(); })
    .then(function(cd){
      var xhr = new XMLHttpRequest();
      xhr.open('POST', base + '/manage/omniauth-management/sosGenerateCodes', true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
      xhr.onload = function() {
        try {
          var r = JSON.parse(xhr.responseText);
          if (r.success) {
            sosCodes = r.codes;
            renderCodes();
          } else {
            alert(r.error || 'Could not generate codes.');
          }
        } catch (e) { alert('Server error.'); }
      };
      xhr.onerror = function(){ alert('Network error.'); };
      xhr.send('');
    })
    .catch(function(){ alert('Could not fetch security token.'); });
}

function renderCodes() {
  var list = document.getElementById('sos-codes-list');
  list.innerHTML = '';
  sosCodes.forEach(function(c){
    var d = document.createElement('div');
    d.style.cssText = 'padding:8px 10px;border:1px solid var(--oau-card-border);border-radius:6px;background:var(--oau-code-bg,rgba(127,127,127,0.08));text-align:center;letter-spacing:1px;';
    d.textContent = c;
    list.appendChild(d);
  });
  var ack = document.getElementById('sos-saved-ack');
  ack.checked = false;
  document.getElementById('sos-continue-btn').disabled = true;
  var panel = document.getElementById('sos-codes-panel');
  panel.style.display = 'block';
  panel.scrollIntoView({behavior:'smooth'});
}

function sosCopy() {
  navigator.clipboard.writeText(sosCodes.join('\n')).then(function(){}, function(){});
}

function sosDownload() {
  var blob = new Blob([sosCodes.join('\n') + '\n'], {type:'text/plain'});
  var a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'omniauth-sos-recovery-codes.txt';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function sosContinue() {
  var base = window.location.pathname.split('/manage/')[0];
  if (sosEnrollMode) {
    window.location.href = base + '/';
  } else {
    window.location.reload();
  }
}

(function () {
  function init() {
    var dataEl = document.getElementById('sosData');
    if (dataEl) {
      try { sosEnrollMode = JSON.parse(dataEl.textContent).enrollMode === true; } catch (e) { sosEnrollMode = false; }
    }
    document.getElementById('sos-regen-cancel').addEventListener('click', function() {
      document.getElementById('sos-regen-modal').style.display = 'none';
    });
    document.getElementById('sos-regen-confirm').addEventListener('click', function() {
      document.getElementById('sos-regen-modal').style.display = 'none';
      sosDoGenerate();
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
