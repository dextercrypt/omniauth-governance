/*
 * Scheduled login-announcement rules editor (Settings page).
 *
 * Renders the rules held in the hidden #rgSchedJson field as editable rows (message, day
 * checkboxes, all-day / time window, priority, enabled, remove), keeps that hidden field in sync on
 * every change so the normal form POST carries them, and shows a non-blocking overlap warning when
 * two enabled rules could display at the same time. Loaded as an adjunct:
 *   <st:adjunct includes="io.jenkins.plugins.rotogate.RotogateManagementLink.schedAnnouncements"/>
 *
 * CSP-safe: external script, all wiring via addEventListener, DOM built with createElement (no
 * innerHTML of user data).
 */
(function () {
  "use strict";

  // ISO day numbering 1=Mon..7=Sun, with single-letter labels.
  var DAYS = [["1", "M"], ["2", "T"], ["3", "W"], ["4", "T"], ["5", "F"], ["6", "S"], ["7", "S"]];

  var list, jsonInput, warnEl;

  function el(tag, cls, attrs) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    return e;
  }
  function minToTime(m) {
    m = Math.max(0, Math.min(1439, m | 0));
    var h = Math.floor(m / 60), mm = m % 60;
    return (h < 10 ? "0" : "") + h + ":" + (mm < 10 ? "0" : "") + mm;
  }
  function timeToMin(s) {
    var p = (s || "").split(":");
    if (p.length !== 2) return 0;
    var h = parseInt(p[0], 10) || 0, m = parseInt(p[1], 10) || 0;
    return Math.max(0, Math.min(1439, h * 60 + m));
  }
  function labelWrap(text, control) {
    var w = el("div", "rg-sched-field");
    var l = el("div", "rg-sched-flabel");
    l.textContent = text;
    w.appendChild(l);
    w.appendChild(control);
    return w;
  }
  function short(s) { return s.length > 30 ? s.slice(0, 30) + "…" : s; }

  function buildRow(rule) {
    var row = el("div", "rg-sched-row");

    var msg = el("input", "jenkins-input rg-sched-msg", { type: "text", placeholder: "Message, e.g. No production deployments" });
    msg.value = rule.message || "";
    row.appendChild(labelWrap("Message", msg));

    var daysWrap = el("div", "rg-sched-days");
    DAYS.forEach(function (d) {
      var lab = el("label", "rg-sched-day");
      var cb = el("input", null, { type: "checkbox", "data-day": d[0] });
      cb.checked = (rule.days || []).indexOf(parseInt(d[0], 10)) >= 0;
      lab.appendChild(cb);
      lab.appendChild(document.createTextNode(d[1]));
      daysWrap.appendChild(lab);
    });
    row.appendChild(labelWrap("Days (none = every day)", daysWrap));

    var timeWrap = el("div", "rg-sched-time");
    var allDayLab = el("label", "rg-sched-allday");
    var allDay = el("input", null, { type: "checkbox" });
    allDay.checked = rule.allDay !== false;
    allDayLab.appendChild(allDay);
    allDayLab.appendChild(document.createTextNode("All day"));
    var start = el("input", "jenkins-input rg-sched-start", { type: "time" });
    start.value = minToTime(rule.start || 0);
    var end = el("input", "jenkins-input rg-sched-end", { type: "time" });
    end.value = minToTime(rule.end != null ? rule.end : 1439);
    var times = el("span", "rg-sched-times");
    times.appendChild(start);
    times.appendChild(document.createTextNode(" to "));
    times.appendChild(end);
    function syncTimes() { times.style.display = allDay.checked ? "none" : "inline-flex"; }
    allDay.addEventListener("change", function () { syncTimes(); changed(); });
    syncTimes();
    timeWrap.appendChild(allDayLab);
    timeWrap.appendChild(times);
    row.appendChild(labelWrap("Time window (server time)", timeWrap));

    var meta = el("div", "rg-sched-meta");
    var prio = el("input", "jenkins-input rg-sched-prio", { type: "number" });
    prio.value = rule.priority != null ? rule.priority : 0;
    meta.appendChild(labelWrap("Priority", prio));
    var enLab = el("label", "rg-sched-en");
    var en = el("input", null, { type: "checkbox" });
    en.checked = rule.enabled !== false;
    enLab.appendChild(en);
    enLab.appendChild(document.createTextNode("Enabled"));
    meta.appendChild(enLab);
    var rm = el("button", "jenkins-button jenkins-button--small rg-sched-rm", { type: "button" });
    rm.textContent = "Remove";
    rm.addEventListener("click", function () { row.remove(); changed(); });
    meta.appendChild(rm);
    row.appendChild(meta);

    [msg, prio, en, start, end].forEach(function (i) {
      i.addEventListener("input", changed);
      i.addEventListener("change", changed);
    });
    daysWrap.querySelectorAll("input").forEach(function (i) { i.addEventListener("change", changed); });

    row._get = function () {
      var days = [];
      daysWrap.querySelectorAll("input:checked").forEach(function (c) { days.push(parseInt(c.getAttribute("data-day"), 10)); });
      return {
        message: msg.value.trim(),
        days: days,
        allDay: allDay.checked,
        start: timeToMin(start.value),
        end: timeToMin(end.value),
        priority: parseInt(prio.value, 10) || 0,
        enabled: en.checked
      };
    };
    return row;
  }

  function serialize() {
    var rules = [];
    list.querySelectorAll(".rg-sched-row").forEach(function (r) { if (r._get) rules.push(r._get()); });
    jsonInput.value = JSON.stringify(rules);
    return rules;
  }

  function daysOverlap(a, b) {
    if (!a.days.length || !b.days.length) return true; // empty = every day
    return a.days.some(function (d) { return b.days.indexOf(d) >= 0; });
  }
  function timeOverlap(a, b) {
    if (a.allDay || b.allDay) return true;
    return a.start <= b.end && b.start <= a.end;
  }
  function overlapWarn(rules) {
    if (!warnEl) return;
    var msgs = [];
    var active = rules.filter(function (r) { return r.enabled && r.message; });
    for (var i = 0; i < active.length; i++) {
      for (var j = i + 1; j < active.length; j++) {
        if (daysOverlap(active[i], active[j]) && timeOverlap(active[i], active[j])) {
          msgs.push("“" + short(active[i].message) + "” and “" + short(active[j].message) + "” can show at the same time.");
        }
      }
    }
    if (msgs.length) {
      warnEl.style.display = "";
      warnEl.textContent = "Heads up: " + msgs.join(" ")
        + " Both may appear together; when more than 2 banners are active, the higher-priority ones win.";
    } else {
      warnEl.style.display = "none";
      warnEl.textContent = "";
    }
  }

  function changed() { overlapWarn(serialize()); }

  function addRow(rule) {
    list.appendChild(buildRow(rule || { message: "", days: [], allDay: true, start: 0, end: 1439, priority: 0, enabled: true }));
  }

  function init() {
    list = document.getElementById("rgSchedList");
    jsonInput = document.getElementById("rgSchedJson");
    warnEl = document.getElementById("rgSchedWarn");
    if (!list || !jsonInput) return;
    var initial = [];
    try { initial = JSON.parse(jsonInput.value || "[]"); } catch (e) { initial = []; }
    if (!Array.isArray(initial)) initial = [];
    initial.forEach(addRow);
    var addBtn = document.getElementById("rgSchedAdd");
    if (addBtn) addBtn.addEventListener("click", function () { addRow(); changed(); });
    changed();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
