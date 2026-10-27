/*
 * Just-In-Time (JIT) access banner shown on a job page: reflects the current
 * JIT request state (none / pending / active) and offers request / cancel /
 * revoke actions, plus a grayed-out placeholder "Build Now" while access is
 * not yet granted.
 *
 * Served as an external, same-origin script (CSP script-src 'self'); the
 * previous inline <script> injected by OmniAuthPageDecorator is blocked by
 * Jenkins' CSP. All event wiring uses addEventListener (not inline on* handler
 * attributes, which the same CSP blocks).
 */
(function () {
  "use strict";
  try {
    var SVG_BOLT = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" width="16" height="16" style="vertical-align:middle;flex-shrink:0;"><path fill-rule="evenodd" d="M11.3 1.046A1 1 0 0112 2v5h4a1 1 0 01.82 1.573l-7 10A1 1 0 018 18v-5H4a1 1 0 01-.82-1.573l7-10a1 1 0 011.12-.38z" clip-rule="evenodd"/></svg>';
    var SVG_CHECK = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" width="16" height="16" style="vertical-align:middle;flex-shrink:0;"><path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd"/></svg>';
    var SVG_CLOCK = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" width="16" height="16" style="vertical-align:middle;flex-shrink:0;"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clip-rule="evenodd"/></svg>';

    function extractJobPath(p) {
      var m = p.match(/\/job\/(.+)/);
      if (!m) return null;
      var parts = m[1].split("/");
      var out = [];
      for (var i = 0; i < parts.length; i++) {
        var s = parts[i];
        if (!s || s === "job") continue;
        if (/^\d+$/.test(s) || ["build", "configure", "workspace", "changes", "lastBuild", "api", "console", "testReport", "cobertura", "robot"].indexOf(s) >= 0) break;
        out.push(s);
      }
      return out.length ? out.join("/") : null;
    }

    var jobPath = extractJobPath(window.location.pathname);
    if (!jobPath) return;
    var base = window.location.href.split("/job/")[0];
    var _prevJitStatus = null;
    var _pendingPoll = null;
    var _cdInterval = null;
    var _jitModal = null;

    function fmtTime(s) { var m = Math.floor(s / 60); var ss = s % 60; return m + "m " + (ss < 10 ? "0" : "") + ss + "s"; }
    function escHtml(s) { if (!s) return ""; return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }

    function fetchStatus() {
      fetch(base + "/omniauth-jit/status?job=" + encodeURIComponent(jobPath), { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (d) { renderBanner(d); })
        .catch(function () {});
    }

    function removeBanner() { var b = document.getElementById("oau-jit-banner"); if (b) b.remove(); }

    function injectGrayBuildNow() {
      if (document.getElementById("oau-gray-build")) return;
      var tasks = document.getElementById("tasks"); if (!tasks) return;
      var links = tasks.querySelectorAll("a");
      for (var i = 0; i < links.length; i++) { if (/build now/i.test(links[i].textContent.trim())) return; }
      var d = document.createElement("div");
      d.id = "oau-gray-build"; d.className = "task";
      d.innerHTML = '<a class="task-link" href="#" style="opacity:0.38;cursor:default;pointer-events:none;">' +
        '<span class="task-icon-link"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg></span>' +
        'Build Now</a>';
      tasks.insertBefore(d, tasks.firstChild);
    }
    function removeGrayBuildNow() { var el = document.getElementById("oau-gray-build"); if (el) el.remove(); }

    function startCountdown(secs) {
      if (_cdInterval) clearInterval(_cdInterval);
      var rem = secs;
      _cdInterval = setInterval(function () {
        rem--;
        var el = document.getElementById("oau-jit-countdown");
        if (el) el.textContent = fmtTime(rem);
        if (rem <= 0) { clearInterval(_cdInterval); fetchStatus(); }
      }, 1000);
    }

    function renderBanner(d) {
      if (!d || !d.hasJit) return;
      if (d.status === "ACTIVE" && _prevJitStatus === "PENDING") { window.location.reload(); return; }
      if (d.status === "PENDING" && !_pendingPoll) { _pendingPoll = setInterval(fetchStatus, 5000); }
      if (d.status !== "PENDING" && _pendingPoll) { clearInterval(_pendingPoll); _pendingPoll = null; }
      _prevJitStatus = d.status;
      removeBanner();
      var banner = document.createElement("div");
      banner.id = "oau-jit-banner";
      var color, icon, msg, actions;
      if (d.status === "ACTIVE") {
        removeGrayBuildNow();
        color = "#16a34a"; icon = SVG_CHECK;
        msg = '<strong>JIT Access active</strong> &mdash; approved by <strong>' + escHtml(d.approverId) + '</strong> &middot; expires in <strong id="oau-jit-countdown">' + fmtTime(d.secondsRemaining) + '</strong>';
        actions = '<button type="button" class="oau-jit-act" data-action="revoke" data-req="' + escHtml(d.requestId) + '" style="padding:4px 12px;border:1px solid #16a34a;border-radius:4px;background:#fff;color:#16a34a;cursor:pointer;font-size:12px;font-weight:600;">End Access</button>';
      } else if (d.status === "PENDING") {
        injectGrayBuildNow();
        color = "#d97706"; icon = SVG_CLOCK;
        var approvalInfo = d.totalApprovers > 0 ? " &middot; <strong>" + d.approvedCount + " of " + d.totalApprovers + "</strong> approved" : "";
        msg = '<strong>JIT request pending</strong>' + approvalInfo + ' &middot; requested ' + escHtml(d.timeAgo);
        actions = '<button type="button" class="oau-jit-act" data-action="cancel" data-req="' + escHtml(d.requestId) + '" style="padding:4px 12px;border:1px solid #d97706;border-radius:4px;background:#fff;color:#d97706;cursor:pointer;font-size:12px;font-weight:600;">Cancel Request</button>';
      } else {
        injectGrayBuildNow();
        color = "#6366f1"; icon = SVG_BOLT;
        if (d.status === "DENIED" || d.status === "EXPIRED" || d.status === "TIMED_OUT") {
          msg = '<strong>JIT access required</strong> &mdash; previous request was <strong>' + d.status.toLowerCase() + '</strong>. Request again to build.';
        } else {
          msg = '<strong>JIT access required</strong> &mdash; building this pipeline requires approval from ' + escHtml(d.approverGroup || "an admin") + '.';
        }
        actions = '<button type="button" class="oau-jit-act" data-action="open" style="display:inline-flex;align-items:center;gap:5px;padding:4px 12px;border:none;border-radius:4px;background:#6366f1;color:#fff;cursor:pointer;font-size:12px;font-weight:600;">' + SVG_BOLT + ' Request JIT Access</button>';
      }
      banner.style.cssText = "margin:8px 16px;padding:10px 14px;border-radius:6px;background:#fff;border:1px solid " + color + ";display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;";
      banner.innerHTML = '<div style="display:flex;align-items:center;gap:8px;font-size:13px;"><span style="display:inline-flex;align-items:center;color:' + color + ';">' + icon + '</span><span style="color:#333;">' + msg + '</span></div><div style="display:flex;align-items:center;gap:8px;">' + actions + '</div>';
      // Wire the action button (CSP-safe: addEventListener, not an inline onclick attribute).
      var actBtn = banner.querySelector(".oau-jit-act");
      if (actBtn) {
        var act = actBtn.getAttribute("data-action");
        var req = actBtn.getAttribute("data-req");
        actBtn.addEventListener("click", function () {
          if (act === "revoke") oauJitRevoke(req);
          else if (act === "cancel") oauJitCancel(req);
          else if (act === "open") oauJitOpen();
        });
      }
      if (d.status === "ACTIVE") startCountdown(d.secondsRemaining);
      var main = document.getElementById("main-panel") || document.querySelector(".jenkins-main-panel") || document.querySelector('[id*="main"]');
      if (main) { main.insertBefore(banner, main.firstChild); } else { document.body.insertBefore(banner, document.body.firstChild); }
    }

    function oauJitOpen() {
      if (_jitModal) { _jitModal.style.display = "flex"; return; }
      var m = document.createElement("div");
      _jitModal = m;
      m.style.cssText = "display:flex;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.45);z-index:9999;align-items:center;justify-content:center;";
      var durOpts = "";
      fetch(base + "/omniauth-jit/status?job=" + encodeURIComponent(jobPath), { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (d) {
          for (var h = 1; h <= d.maxDurationHours; h++) { durOpts += '<option value="' + h + '"' + (h === 1 ? " selected" : "") + ">" + h + " hour" + (h > 1 ? "s" : "") + "</option>"; }
          var scopeLabel = d.scope && d.scope !== jobPath ? "Folder" : "Pipeline";
          var scopeVal = d.scope || jobPath;
          var scopeNote = d.scopeIsFolder ? '<br/><small style="color:#6b7280;">Approves access to all pipelines in this folder</small>' : "";
          m.innerHTML = '<div style="background:#fff;border-radius:8px;padding:24px;max-width:460px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,0.18);">' +
            '<div style="font-size:15px;font-weight:700;margin-bottom:16px;display:flex;align-items:center;gap:6px;">' + SVG_BOLT + ' Request JIT Access</div>' +
            '<div style="font-size:12px;color:#888;margin-bottom:16px;padding:8px 12px;background:#f5f5f5;border-radius:4px;">' +
            '<strong>' + escHtml(scopeLabel) + ':</strong> ' + escHtml(scopeVal) + scopeNote + '<br/>' +
            '<strong>Approver:</strong> ' + escHtml(d.approverGroup || "Any admin") + '<br/>' +
            '<strong>Auto-deny after:</strong> ' + d.approvalTimeoutHours + ' hour(s)' +
            '</div>' +
            '<div style="margin-bottom:12px;"><label style="display:block;font-size:11px;font-weight:700;text-transform:uppercase;color:#888;margin-bottom:4px;">Reason *</label>' +
            '<textarea id="oau-jit-reason" placeholder="Why do you need access? (e.g. Deploy v2.3.1, hotfix for JIRA-441)" style="width:100%;height:72px;padding:8px;border:1px solid #ccc;border-radius:4px;font-size:13px;resize:none;box-sizing:border-box;"></textarea></div>' +
            '<div style="margin-bottom:16px;"><label style="display:block;font-size:11px;font-weight:700;text-transform:uppercase;color:#888;margin-bottom:4px;">Duration</label>' +
            '<select id="oau-jit-dur" style="width:100%;padding:8px;border:1px solid #ccc;border-radius:4px;font-size:13px;">' + durOpts + '</select></div>' +
            '<div id="oau-jit-err" style="display:none;padding:8px 12px;background:#fee;border:1px solid #fca;border-radius:4px;font-size:12px;color:#c00;margin-bottom:12px;"></div>' +
            '<div style="display:flex;gap:8px;justify-content:flex-end;">' +
            '<button type="button" id="oau-jit-cancel-btn" style="padding:8px 16px;border:1px solid #ccc;border-radius:4px;background:#fff;cursor:pointer;font-size:13px;">Cancel</button>' +
            '<button type="button" id="oau-jit-submit" style="padding:8px 16px;border:none;border-radius:4px;background:#6366f1;color:#fff;cursor:pointer;font-size:13px;font-weight:600;">Submit Request</button>' +
            '</div></div>';
          var cancelBtn = m.querySelector("#oau-jit-cancel-btn");
          if (cancelBtn) cancelBtn.addEventListener("click", oauJitClose);
          var submitBtn = m.querySelector("#oau-jit-submit");
          if (submitBtn) submitBtn.addEventListener("click", oauJitSubmit);
        });
      document.body.appendChild(m);
    }

    function oauJitClose() { if (_jitModal) _jitModal.style.display = "none"; }

    function oauJitSubmit() {
      var reason = document.getElementById("oau-jit-reason").value.trim();
      var dur = document.getElementById("oau-jit-dur").value;
      var err = document.getElementById("oau-jit-err");
      if (!reason) { err.textContent = "Reason is required."; err.style.display = "block"; return; }
      var btn = document.getElementById("oau-jit-submit");
      btn.disabled = true; btn.textContent = "Submitting...";
      fetch(base + "/crumbIssuer/api/json", { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (cd) {
          var body = "job=" + encodeURIComponent(jobPath) + "&reason=" + encodeURIComponent(reason) + "&durationHours=" + encodeURIComponent(dur);
          var headers = { "Content-Type": "application/x-www-form-urlencoded" };
          headers[cd.crumbRequestField] = cd.crumb;
          return fetch(base + "/omniauth-jit/request", { method: "POST", headers: headers, body: body, credentials: "same-origin" });
        })
        .then(function (r) { return r.json(); })
        .then(function (data) {
          btn.disabled = false; btn.textContent = "Submit Request";
          if (data.ok) { oauJitClose(); fetchStatus(); } else { err.textContent = data.error || "Request failed."; err.style.display = "block"; }
        })
        .catch(function () { btn.disabled = false; btn.textContent = "Submit Request"; err.textContent = "Network error."; err.style.display = "block"; });
    }

    function oauJitCancel(requestId) {
      if (!confirm("Cancel your pending JIT request?")) return;
      fetch(base + "/crumbIssuer/api/json", { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (cd) {
          var headers = { "Content-Type": "application/x-www-form-urlencoded" };
          headers[cd.crumbRequestField] = cd.crumb;
          return fetch(base + "/omniauth-jit/cancel", { method: "POST", headers: headers, body: "requestId=" + encodeURIComponent(requestId), credentials: "same-origin" });
        })
        .then(function () { fetchStatus(); })
        .catch(function () {});
    }

    function oauJitRevoke(requestId) {
      if (!confirm("Revoke your active JIT access?")) return;
      fetch(base + "/crumbIssuer/api/json", { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (cd) {
          var headers = { "Content-Type": "application/x-www-form-urlencoded" };
          headers[cd.crumbRequestField] = cd.crumb;
          return fetch(base + "/omniauth-jit/cancel", { method: "POST", headers: headers, body: "requestId=" + encodeURIComponent(requestId), credentials: "same-origin" });
        })
        .then(function () { fetchStatus(); })
        .catch(function () {});
    }

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", fetchStatus);
    } else {
      fetchStatus();
    }
  } catch (e) { /* never break the page */ }
})();
