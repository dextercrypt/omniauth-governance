/*
 * Overview dashboard behaviour: banner dismissal, relative timestamps, KPI
 * count-up animation, and live metric auto-refresh.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.omniauth.OmniAuthManagementLink.indexAssets"/>
 * Externalised from index.jelly's inline <script> blocks for CSP compliance (Jenkins hosting).
 * Functions called from inline on* handlers are declared at top level (global).
 * Adjuncts load in <head>, so code that touches the DOM on load runs via
 * oauOnReady() once the document is parsed.
 */

function oauOnReady(fn) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', fn);
  } else {
    fn();
  }
}

// ---- Banner dismiss handlers (called from inline on* handlers) ----
function oauDismissWarning() {
  localStorage.setItem('oau_warning_dismissed', Date.now().toString());
  var el = document.getElementById('oau-warning-banner');
  if (el) el.style.display = 'none';
}

function oauDismissWeekly(key) {
  localStorage.setItem(key, Date.now().toString());
  var el = document.getElementById(key + '_banner');
  if (el) el.style.display = 'none';
}

// ---- On load: apply dismissed banners and relative timestamps ----
oauOnReady(function() {
  var WEEK = 7 * 24 * 60 * 60 * 1000;
  // Weekly info banners: localStorage-scoped
  ['oau_notif_dismiss', 'oau_cleanup_dismiss', 'oau_review_dismiss'].forEach(function(key) {
    var ts = localStorage.getItem(key);
    if (ts && (Date.now() - parseInt(ts, 10)) < WEEK) {
      var el = document.getElementById(key + '_banner');
      if (el) el.style.display = 'none';
    }
  });

  // Warning banner dismissed within the last 6 hours
  var wts = localStorage.getItem('oau_warning_dismissed');
  if (wts && (Date.now() - parseInt(wts)) < 6 * 60 * 60 * 1000) {
    var wel = document.getElementById('oau-warning-banner');
    if (wel) wel.style.display = 'none';
  }

  // Relative timestamps for audit feed
  var now = Date.now();
  document.querySelectorAll('.oau-audit-ts[data-ts]').forEach(function(el) {
    var ts = el.getAttribute('data-ts');
    if (!ts) return;
    try {
      var diff = Math.floor((now - new Date(ts).getTime()) / 1000);
      var text;
      if (diff < 60)       text = diff + 's ago';
      else if (diff < 3600) text = Math.floor(diff / 60) + 'm ago';
      else if (diff < 86400) text = Math.floor(diff / 3600) + 'h ago';
      else                  text = Math.floor(diff / 86400) + 'd ago';
      el.insertAdjacentText('beforeend', text);
    } catch(e) { el.textContent = ts; }
  });
});

// ---- KPI count-up (0 -> value on load; old -> new on live update) ----
function oauAnimateValue(el, to) {
  var from = parseInt(el.getAttribute('data-cur') || '0', 10) || 0;
  to = (typeof to === 'number') ? to : (parseInt((el.textContent || '').replace(/[^0-9-]/g,''), 10) || 0);
  if (from === to) { el.textContent = to; el.setAttribute('data-cur', to); return; }
  var start = null, dur = 650;
  function step(t) {
    if (!start) start = t;
    var p = Math.min(1, (t - start) / dur);
    var eased = 1 - Math.pow(1 - p, 3);
    el.textContent = Math.round(from + (to - from) * eased);
    if (p < 1) requestAnimationFrame(step);
    else { el.textContent = to; el.setAttribute('data-cur', to); }
  }
  requestAnimationFrame(step);
}

oauOnReady(function() {
  document.querySelectorAll('.oau-kpi-card__value[data-kpi]').forEach(function(el) {
    var target = parseInt((el.textContent || '').replace(/[^0-9-]/g,''), 10) || 0;
    el.setAttribute('data-cur', '0');
    oauAnimateValue(el, target);
  });
  oauStartLive();
});

// ---- Live auto-refresh of volatile KPIs ----
var OAU_LIVE = true, OAU_TIMER = null, OAU_INTERVAL = 15000, OAU_LAST = Date.now();
function oauRenderAgo() {
  var el = document.getElementById('oauLiveText');
  if (!el) return;
  if (!OAU_LIVE) { el.textContent = 'Paused'; return; }
  var s = Math.max(0, Math.floor((Date.now() - OAU_LAST) / 1000));
  el.textContent = 'Live · ' + (s < 2 ? 'just now' : s + 's ago');
}
function oauFetchMetrics() {
  if (!OAU_LIVE) return;
  fetch('overviewMetrics', { headers: { 'Accept':'application/json' } })
    .then(function(r){ return r.ok ? r.json() : null; })
    .then(function(d){
      if (!d) return;
      OAU_LAST = Date.now();
      document.querySelectorAll('.oau-kpi-card__value[data-kpi]').forEach(function(el) {
        var k = el.getAttribute('data-kpi');
        if (!(k in d)) return;
        var cur = parseInt(el.getAttribute('data-cur') || '0', 10) || 0;
        if (cur !== d[k]) {
          oauAnimateValue(el, d[k]);
          el.classList.remove('oau-ticked'); void el.offsetWidth; el.classList.add('oau-ticked');
        }
      });
      oauRenderAgo();
    })
    .catch(function(){ /* transient — keep last values */ });
}
function oauStartLive() {
  oauRenderAgo();
  if (OAU_TIMER) clearInterval(OAU_TIMER);
  OAU_TIMER = setInterval(function(){ oauFetchMetrics(); oauRenderAgo(); }, OAU_INTERVAL);
  // first refresh shortly after load so "live" feels immediate
  setTimeout(oauFetchMetrics, 3000);
}
function oauToggleLive() {
  OAU_LIVE = !OAU_LIVE;
  var status = document.getElementById('oauLiveStatus');
  var icon = document.getElementById('oauLiveIcon');
  var btn = document.getElementById('oauLiveBtnText');
  if (OAU_LIVE) {
    status.classList.remove('oau-live-status--paused');
    btn.textContent = 'Pause';
    icon.innerHTML = '<rect x="6" y="5" width="4" height="14" rx="1"></rect><rect x="14" y="5" width="4" height="14" rx="1"></rect>';
    OAU_LAST = Date.now(); oauFetchMetrics();
  } else {
    status.classList.add('oau-live-status--paused');
    btn.textContent = 'Resume';
    icon.innerHTML = '<polygon points="6 4 20 12 6 20 6 4"></polygon>';
  }
  oauRenderAgo();
}
// Pause polling when the tab is hidden; resume on return.
document.addEventListener('visibilitychange', function() {
  if (document.hidden) { if (OAU_TIMER) { clearInterval(OAU_TIMER); OAU_TIMER = null; } }
  else if (OAU_LIVE && !OAU_TIMER) { oauStartLive(); }
});

// ---- Wire control handlers (replaces inline on* attributes for CSP) ----
oauOnReady(function() {
  var liveToggle = document.getElementById('oauLiveToggle');
  if (liveToggle) liveToggle.addEventListener('click', oauToggleLive);

  document.querySelectorAll('.js-oau-dismiss-warning').forEach(function(el) {
    el.addEventListener('click', oauDismissWarning);
  });

  document.querySelectorAll('.js-oau-dismiss-weekly').forEach(function(el) {
    el.addEventListener('click', function() {
      oauDismissWeekly(this.dataset.key);
    });
  });
});
