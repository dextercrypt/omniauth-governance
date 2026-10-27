/*
 * SOS Mode banner — a fixed red bar shown to admins while emergency recovery
 * is armed, with a "Deactivate now" action.
 *
 * Served as an external, same-origin script (CSP script-src 'self'); the
 * previous inline <script> injected by OmniAuthPageDecorator is blocked by
 * Jenkins' CSP and never ran. Context path and minutes-remaining are read
 * from the injected #oau-sos-state element's data-* attributes.
 */
(function () {
  "use strict";
  try {
    if (document.getElementById("oau-sos-banner")) return;
    var state = document.getElementById("oau-sos-state");
    if (!state) return;
    var ctx = state.getAttribute("data-ctx") || "";
    var mins = state.getAttribute("data-mins") || "";

    var bar = document.createElement("div");
    bar.id = "oau-sos-banner";
    bar.style.cssText = "position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#b91c1c;color:#fff;padding:10px 16px;display:flex;align-items:center;justify-content:center;gap:14px;flex-wrap:wrap;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Arial,sans-serif;font-size:13px;box-shadow:0 2px 8px rgba(0,0,0,0.3);";
    bar.innerHTML =
      '<span style="display:inline-flex;align-items:center;gap:8px;">' +
        '<strong style="display:inline-flex;align-items:center;gap:6px;"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>SOS Mode active</strong>' +
        '<span style="opacity:.9;">Emergency recovery is open at <code style="background:rgba(0,0,0,.2);padding:1px 5px;border-radius:3px;">/sos/recover</code>' +
        ' &middot; auto-closes in ' + mins + ' min</span></span>' +
      '<button type="button" id="oau-sos-deact" style="white-space:nowrap;padding:5px 14px;border:1px solid #fff;border-radius:5px;background:#fff;color:#b91c1c;font-size:12px;font-weight:700;cursor:pointer;">Deactivate now</button>';

    function place() {
      document.body.insertBefore(bar, document.body.firstChild);
      document.body.style.paddingTop = (bar.offsetHeight || 40) + "px";
    }
    if (document.body) { place(); } else { document.addEventListener("DOMContentLoaded", place); }

    var btn = bar.querySelector("#oau-sos-deact");
    btn.addEventListener("click", function () {
      btn.disabled = true; btn.textContent = "Deactivating...";
      fetch(ctx + "/crumbIssuer/api/json", { credentials: "same-origin" })
        .then(function (r) { return r.json(); })
        .then(function (cd) {
          var h = { "Content-Type": "application/x-www-form-urlencoded" };
          h[cd.crumbRequestField] = cd.crumb;
          return fetch(ctx + "/manage/omniauth-management/sosDeactivate", { method: "POST", headers: h, body: "", credentials: "same-origin" });
        })
        .then(function (r) {
          if (r.ok) { window.location.reload(); }
          else { btn.disabled = false; btn.textContent = "Deactivate now"; alert("You must be an administrator to deactivate SOS Mode."); }
        })
        .catch(function () { btn.disabled = false; btn.textContent = "Deactivate now"; alert("Could not deactivate — network error."); });
    });
  } catch (e) { /* never break the page */ }
})();
