/*
 * Break Glass gate for the Global Security (configureSecurity) page.
 *
 * Served as an external, same-origin script from /plugin/omniauth-governance/js/
 * so it satisfies Jenkins core's Content-Security-Policy (script-src 'self'); the
 * previous inline <script> injected by OmniAuthPageDecorator is blocked by that CSP
 * and never ran. State is read from the injected #omniauth-bg-state element's
 * data-* attributes (data-* is not subject to CSP) rather than inlined EL.
 *
 * Modes (data-mode):
 *   enrolled    - admin has a TOTP device: gray out the matrix, offer Emergency
 *                 Override (reason + 6-digit TOTP -> breakGlassActivate).
 *   notEnrolled - no TOTP device: gray out the matrix, Override button points to
 *                 Break Glass enrollment.
 *   active      - override currently active: leave the matrix editable, show the
 *                 countdown banner + "End Override Now" (breakGlassDeactivate).
 */
(function () {
  "use strict";
  try {
    if (!window.location.pathname.match(/\/configureSecurity(\/)?$/)) return;

    var state = document.getElementById("omniauth-bg-state");
    if (!state) return;
    var mode = state.getAttribute("data-mode");
    var base = window.location.pathname.split("/manage/")[0];

    // matrix-auth 3.x renders the matrix inside .mas-container (search bar, Add
    // user/group buttons, permission cards). Fall back to the legacy <table>
    // layout only when that container is absent (older matrix-auth).
    function findContainer() {
      var c = document.querySelector(".mas-container");
      if (c) return c;
      var ck = document.querySelector(
        'input[type="checkbox"][name*="hudson.model"],' +
        'input[type="checkbox"][name*="jenkins.model"],' +
        'input[type="checkbox"][name*="hudson.plugins"]');
      if (!ck) return null;
      var t = ck.closest("table");
      if (!t) return null;
      var c2 = t.parentElement, depth = 0;
      while (c2 && depth < 6) {
        if (c2.querySelector("button") || c2.className === "repeated-container") break;
        var p = c2.parentElement;
        if (!p || p.tagName === "FORM" || p.tagName === "BODY") break;
        c2 = p; depth++;
      }
      return c2;
    }

    // Wrap the matrix container, insert a banner above it, and (optionally) gray it out.
    // Returns the wrapper so callers can place a modal trigger.
    function wrapWithBanner(c, grayOut, bannerEl) {
      var w = document.createElement("div");
      w.style.cssText = "position:relative;margin-bottom:4px;";
      c.parentNode.insertBefore(w, c);
      w.appendChild(c);
      if (grayOut) c.style.cssText = "opacity:0.4;pointer-events:none;user-select:none;";
      w.insertBefore(bannerEl, c);
      return w;
    }

    var BOLT_SVG = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>';
    var LOCK_SVG_SM = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 9.9-1"/></svg>';
    var LOCK_SVG_LG = '<svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>';

    function managedBanner() {
      var b = document.createElement("div");
      b.style.cssText = "padding:10px 14px;background:#fff8e1;border:1px solid #ffcc02;border-radius:6px;margin-bottom:6px;font-size:13px;color:#795600;display:flex;align-items:center;justify-content:space-between;gap:8px;";
      b.innerHTML =
        '<div style="display:flex;align-items:center;gap:8px;">' + BOLT_SVG +
        '<span><strong>Managed by OmniAuth</strong> &mdash; Authorization table is managed automatically. ' +
        'Configure access via <strong>Manage Jenkins &rarr; OmniAuth &rarr; Access Management</strong>.</span></div>' +
        '<button type="button" id="omniauth-bg-open" style="white-space:nowrap;padding:6px 12px;border:1px solid #dc3545;border-radius:4px;background:#fff;color:#dc3545;cursor:pointer;font-size:12px;font-weight:600;">Emergency Override</button>';
      return b;
    }

    function crumbThen(cb) {
      fetch(base + "/crumbIssuer/api/json")
        .then(function (r) { return r.json(); })
        .then(cb);
    }

    // ---- Mode: active override ------------------------------------------------
    if (mode === "active") {
      var expiry = parseInt(state.getAttribute("data-expiry"), 10) || 0;
      var c = findContainer();
      if (!c) return;
      var b = document.createElement("div");
      b.style.cssText = "padding:10px 14px;background:#fff3cd;border:1px solid #ffc107;border-radius:6px;margin-bottom:6px;font-size:13px;color:#856404;display:flex;align-items:center;justify-content:space-between;gap:8px;";
      b.innerHTML =
        '<div style="display:flex;align-items:center;gap:8px;">' + LOCK_SVG_SM +
        '<span><strong>Emergency Override Active</strong> &mdash; Authorization table is unlocked. ' +
        'Expires in <strong id="omniauth-timer">--:--</strong>.</span></div>' +
        '<button type="button" id="omniauth-bg-end" style="white-space:nowrap;padding:6px 12px;border:1px solid #856404;border-radius:4px;background:#fff;color:#856404;cursor:pointer;font-size:12px;font-weight:600;">End Override Now</button>';
      wrapWithBanner(c, false, b);
      function tick() {
        var rem = Math.max(0, expiry - Date.now());
        var mm = Math.floor(rem / 60000), ss = Math.floor((rem % 60000) / 1000);
        var el = document.getElementById("omniauth-timer");
        if (el) el.textContent = mm + ":" + (ss < 10 ? "0" : "") + ss;
        if (rem <= 0) window.location.reload();
      }
      tick();
      setInterval(tick, 1000);
      document.getElementById("omniauth-bg-end").onclick = function () {
        crumbThen(function (cd) {
          var xhr = new XMLHttpRequest();
          xhr.open("POST", base + "/manage/omniauth-management/breakGlassDeactivate", true);
          xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
          xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
          xhr.onload = function () { window.location.reload(); };
          xhr.send("");
        });
      };
      return;
    }

    // ---- Modes: enrolled / notEnrolled (gray out + modal) ---------------------
    var modal = document.createElement("div");
    modal.id = "omniauth-bg-modal";
    modal.style.cssText = "display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:9999;align-items:center;justify-content:center;";

    if (mode === "notEnrolled") {
      modal.innerHTML =
        '<div style="background:#fff;border-radius:8px;padding:24px;max-width:480px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,0.2);">' +
          '<h3 style="margin:0 0 20px;font-size:16px;color:#212529;">Emergency Override</h3>' +
          '<div style="text-align:center;padding:4px 0 16px;">' +
            '<div style="margin-bottom:12px;display:flex;justify-content:center;">' + LOCK_SVG_LG + '</div>' +
            '<div style="font-size:14px;font-weight:600;color:#495057;margin-bottom:8px;">No Authenticator Device Enrolled</div>' +
            '<div style="font-size:13px;color:#6c757d;margin-bottom:20px;">Enroll a TOTP device on the Break Glass page before you can activate Emergency Override.</div>' +
            '<div style="display:flex;gap:8px;justify-content:center;">' +
              '<button type="button" id="omniauth-bg-cancel" style="padding:8px 16px;border:1px solid #ced4da;border-radius:4px;background:#fff;cursor:pointer;font-size:13px;">Cancel</button>' +
              '<a id="omniauth-bg-enroll-link" href="#" style="display:inline-flex;align-items:center;padding:8px 16px;border:none;border-radius:4px;background:#0d6efd;color:#fff;font-size:13px;font-weight:600;text-decoration:none;">Set Up Break Glass &#8594;</a>' +
            '</div>' +
          '</div>' +
        '</div>';
      document.body.appendChild(modal);
      document.getElementById("omniauth-bg-cancel").onclick = function () { modal.style.display = "none"; };
      var enrollEl = document.getElementById("omniauth-bg-enroll-link");
      if (enrollEl) enrollEl.href = base + "/manage/omniauth-management/emergencyAccess";
    } else {
      // enrolled
      modal.innerHTML =
        '<div style="background:#fff;border-radius:8px;padding:24px;max-width:480px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,0.2);">' +
          '<h3 style="margin:0 0 16px;font-size:16px;color:#212529;">Emergency Override</h3>' +
          '<div id="omniauth-bg-form">' +
            '<p style="margin:0 0 16px;font-size:13px;color:#6c757d;">Unlocks the Authorization table for 15 minutes. All actions are fully logged in OmniAuth Audit Log.</p>' +
            '<div style="margin-bottom:12px;">' +
              '<label style="display:block;font-size:13px;font-weight:600;margin-bottom:4px;">Reason <span style="color:#dc3545">*</span></label>' +
              '<textarea id="omniauth-bg-reason" placeholder="Why do you need emergency access?" style="width:100%;height:72px;padding:8px;border:1px solid #ced4da;border-radius:4px;font-size:13px;resize:none;box-sizing:border-box;"></textarea>' +
            '</div>' +
            '<div style="margin-bottom:16px;">' +
              '<label style="display:block;font-size:13px;font-weight:600;margin-bottom:4px;">TOTP Code <span style="color:#dc3545">*</span></label>' +
              '<input id="omniauth-bg-totp" type="text" inputmode="numeric" maxlength="6" autocomplete="off" placeholder="6-digit code" style="width:100%;padding:8px;border:1px solid #ced4da;border-radius:4px;font-size:13px;box-sizing:border-box;letter-spacing:4px;"/>' +
            '</div>' +
            '<div id="omniauth-bg-err" style="display:none;padding:8px 12px;background:#f8d7da;border:1px solid #f5c6cb;border-radius:4px;font-size:12px;color:#721c24;margin-bottom:12px;"></div>' +
            '<div style="display:flex;gap:8px;justify-content:flex-end;">' +
              '<button type="button" id="omniauth-bg-cancel" style="padding:8px 16px;border:1px solid #ced4da;border-radius:4px;background:#fff;cursor:pointer;font-size:13px;">Cancel</button>' +
              '<button type="button" id="omniauth-bg-submit" style="padding:8px 16px;border:none;border-radius:4px;background:#dc3545;color:#fff;cursor:pointer;font-size:13px;font-weight:600;">Activate Override</button>' +
            '</div>' +
          '</div>' +
          '<div id="omniauth-bg-success" style="display:none;text-align:center;padding:8px 0 4px;">' +
            '<div style="font-size:40px;color:#27ae60;margin-bottom:10px;">&#10003;</div>' +
            '<div style="font-size:15px;font-weight:600;color:#155724;margin-bottom:6px;">Emergency Override Active</div>' +
            '<div style="font-size:13px;color:#6c757d;margin-bottom:4px;">Authorization table is now unlocked for 15 minutes.</div>' +
            '<div style="font-size:13px;color:#6c757d;margin-bottom:14px;">Reloading in <span id="omniauth-bg-secs">5</span>s&hellip;</div>' +
            '<div style="height:4px;background:#e9ecef;border-radius:2px;margin-bottom:16px;overflow:hidden;">' +
              '<div id="omniauth-bg-prog" style="height:100%;width:100%;background:#27ae60;transition:width 5s linear;"></div>' +
            '</div>' +
          '</div>' +
        '</div>';
      document.body.appendChild(modal);
      document.getElementById("omniauth-bg-cancel").onclick = function () { modal.style.display = "none"; };
      document.getElementById("omniauth-bg-submit").onclick = function () {
        var reason = document.getElementById("omniauth-bg-reason").value.trim();
        var totp = document.getElementById("omniauth-bg-totp").value.trim();
        var err = document.getElementById("omniauth-bg-err");
        if (!reason) { err.textContent = "Reason is required."; err.style.display = "block"; return; }
        if (!totp || totp.length !== 6) { err.textContent = "Enter the 6-digit TOTP code."; err.style.display = "block"; return; }
        err.style.display = "none";
        var btn = document.getElementById("omniauth-bg-submit");
        btn.disabled = true; btn.textContent = "Verifying...";
        crumbThen(function (cd) {
          var xhr = new XMLHttpRequest();
          xhr.open("POST", base + "/manage/omniauth-management/breakGlassActivate", true);
          xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
          xhr.setRequestHeader(cd.crumbRequestField, cd.crumb);
          xhr.onload = function () {
            btn.disabled = false; btn.textContent = "Activate Override";
            try {
              var r = JSON.parse(xhr.responseText);
              if (r.success) {
                document.getElementById("omniauth-bg-form").style.display = "none";
                var s = document.getElementById("omniauth-bg-success");
                s.style.display = "block";
                requestAnimationFrame(function () { requestAnimationFrame(function () { var p = document.getElementById("omniauth-bg-prog"); if (p) p.style.width = "0%"; }); });
                var secs = 5;
                var iv = setInterval(function () {
                  secs--;
                  var el = document.getElementById("omniauth-bg-secs"); if (el) el.textContent = secs;
                  if (secs <= 0) { clearInterval(iv); window.onbeforeunload = null; window.location.reload(); }
                }, 1000);
              } else {
                err.textContent = r.error || "Verification failed."; err.style.display = "block";
              }
            } catch (e) {
              err.textContent = "Server error (" + xhr.status + ")."; err.style.display = "block";
            }
          };
          xhr.onerror = function () { btn.disabled = false; btn.textContent = "Activate Override"; err.textContent = "Network error."; err.style.display = "block"; };
          xhr.send("reason=" + encodeURIComponent(reason) + "&totpCode=" + encodeURIComponent(totp));
        });
      };
    }

    function run() {
      if (document.getElementById("omniauth-migration-confirm")) return;
      var c = findContainer();
      if (!c) return;
      wrapWithBanner(c, true, managedBanner());
      document.getElementById("omniauth-bg-open").onclick = function () { modal.style.display = "flex"; };
    }
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", run);
    } else {
      run();
    }
  } catch (e) { /* never break the page */ }
})();
