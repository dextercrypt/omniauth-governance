/*
 * Overview dashboard behaviour: banner dismissal, relative timestamps, KPI
 * count-up animation, and live metric auto-refresh.
 * Loaded as a Jenkins adjunct: <st:adjunct includes="io.jenkins.plugins.rotogate.RotogateManagementLink.indexAssets"/>
 * Externalised from index.jelly's inline <script> blocks for CSP compliance (Jenkins hosting).
 * Functions called from inline on* handlers are declared at top level (global).
 * Adjuncts load in <head>, so code that touches the DOM on load runs via
 * rgOnReady() once the document is parsed.
 */

function rgOnReady(fn) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', fn);
  } else {
    fn();
  }
}

// ---- Banner dismiss handlers (called from inline on* handlers) ----
function rgDismissWarning() {
  localStorage.setItem('rg_warning_dismissed', Date.now().toString());
  var el = document.getElementById('rg-warning-banner');
  if (el) el.style.display = 'none';
}

function rgDismissWeekly(key) {
  localStorage.setItem(key, Date.now().toString());
  var el = document.getElementById(key + '_banner');
  if (el) el.style.display = 'none';
}

// ---- On load: apply dismissed banners and relative timestamps ----
rgOnReady(function() {
  var WEEK = 7 * 24 * 60 * 60 * 1000;
  // Weekly info banners: localStorage-scoped
  ['rg_notif_dismiss', 'rg_cleanup_dismiss', 'rg_review_dismiss'].forEach(function(key) {
    var ts = localStorage.getItem(key);
    if (ts && (Date.now() - parseInt(ts, 10)) < WEEK) {
      var el = document.getElementById(key + '_banner');
      if (el) el.style.display = 'none';
    }
  });

  // Warning banner dismissed within the last 6 hours
  var wts = localStorage.getItem('rg_warning_dismissed');
  if (wts && (Date.now() - parseInt(wts)) < 6 * 60 * 60 * 1000) {
    var wel = document.getElementById('rg-warning-banner');
    if (wel) wel.style.display = 'none';
  }

  // Relative timestamps for audit feed
  var now = Date.now();
  document.querySelectorAll('.rg-audit-ts[data-ts]').forEach(function(el) {
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
function rgAnimateValue(el, to) {
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

rgOnReady(function() {
  document.querySelectorAll('.rg-kpi-card__value[data-kpi]').forEach(function(el) {
    var target = parseInt((el.textContent || '').replace(/[^0-9-]/g,''), 10) || 0;
    el.setAttribute('data-cur', '0');
    rgAnimateValue(el, target);
  });
  rgStartLive();
});

// ---- Live auto-refresh of volatile KPIs ----
var RG_LIVE = true, RG_TIMER = null, RG_INTERVAL = 15000, RG_LAST = Date.now();
function rgRenderAgo() {
  var el = document.getElementById('rgLiveText');
  if (!el) return;
  if (!RG_LIVE) { el.textContent = 'Paused'; return; }
  var s = Math.max(0, Math.floor((Date.now() - RG_LAST) / 1000));
  el.textContent = 'Live · ' + (s < 2 ? 'just now' : s + 's ago');
}
function rgFetchMetrics() {
  if (!RG_LIVE) return;
  fetch('overviewMetrics', { headers: { 'Accept':'application/json' } })
    .then(function(r){ return r.ok ? r.json() : null; })
    .then(function(d){
      if (!d) return;
      RG_LAST = Date.now();
      document.querySelectorAll('.rg-kpi-card__value[data-kpi]').forEach(function(el) {
        var k = el.getAttribute('data-kpi');
        if (!(k in d)) return;
        var cur = parseInt(el.getAttribute('data-cur') || '0', 10) || 0;
        if (cur !== d[k]) {
          rgAnimateValue(el, d[k]);
          el.classList.remove('rg-ticked'); void el.offsetWidth; el.classList.add('rg-ticked');
        }
      });
      rgRenderAgo();
    })
    .catch(function(){ /* transient — keep last values */ });
}
function rgStartLive() {
  rgRenderAgo();
  if (RG_TIMER) clearInterval(RG_TIMER);
  RG_TIMER = setInterval(function(){ rgFetchMetrics(); rgRenderAgo(); }, RG_INTERVAL);
  // first refresh shortly after load so "live" feels immediate
  setTimeout(rgFetchMetrics, 3000);
}
function rgToggleLive() {
  RG_LIVE = !RG_LIVE;
  var status = document.getElementById('rgLiveStatus');
  var icon = document.getElementById('rgLiveIcon');
  var btn = document.getElementById('rgLiveBtnText');
  if (RG_LIVE) {
    status.classList.remove('rg-live-status--paused');
    btn.textContent = 'Pause';
    icon.innerHTML = '<rect x="6" y="5" width="4" height="14" rx="1"></rect><rect x="14" y="5" width="4" height="14" rx="1"></rect>';
    RG_LAST = Date.now(); rgFetchMetrics();
  } else {
    status.classList.add('rg-live-status--paused');
    btn.textContent = 'Resume';
    icon.innerHTML = '<polygon points="6 4 20 12 6 20 6 4"></polygon>';
  }
  rgRenderAgo();
}
// Pause polling when the tab is hidden; resume on return.
document.addEventListener('visibilitychange', function() {
  if (document.hidden) { if (RG_TIMER) { clearInterval(RG_TIMER); RG_TIMER = null; } }
  else if (RG_LIVE && !RG_TIMER) { rgStartLive(); }
});

// ---- Wire control handlers (replaces inline on* attributes for CSP) ----
rgOnReady(function() {
  var liveToggle = document.getElementById('rgLiveToggle');
  if (liveToggle) liveToggle.addEventListener('click', rgToggleLive);

  document.querySelectorAll('.js-rg-dismiss-warning').forEach(function(el) {
    el.addEventListener('click', rgDismissWarning);
  });

  document.querySelectorAll('.js-rg-dismiss-weekly').forEach(function(el) {
    el.addEventListener('click', function() {
      rgDismissWeekly(this.dataset.key);
    });
  });
});
