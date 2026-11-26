/*
 * Declarative confirmation shim for Rotogate management pages.
 *
 * Wires any destructive control carrying a `data-confirm` attribute to Jenkins' own native
 * dialog (core's `dialog.confirm`, which uses the browser <dialog> element — consistent styling,
 * theme-aware, nothing for us to render or maintain). The control's native default action (a form
 * submit) runs ONLY after the user confirms, so a mis-wire can never fire a destructive submit
 * without a prompt. Include where needed:
 *   <st:adjunct includes="io.jenkins.plugins.rotogate.rg.rgConfirm"/>
 *
 *   <button type="submit" data-confirm="Revoke Custom on Jenkins (Global)?">Revoke</button>
 *   <button type="submit" data-confirm="Delete this role?" data-confirm-title="Delete role">…</button>
 *
 * Supports `data-confirm` on a submit <button> (intercepts click) and on a <form> (intercepts
 * submit). Optional `data-confirm-title` sets the dialog heading; all prompts are destructive.
 *
 * CSP-safe: external same-origin script, all wiring via addEventListener, no inline on* handlers.
 */
(function () {
  "use strict";
  try {
    // Ask for confirmation. Returns a Promise that resolves when the user confirms. Uses core's
    // native dialog when present, falling back to window.confirm only if it somehow isn't loaded.
    function ask(title, message) {
      if (window.dialog && typeof window.dialog.confirm === "function") {
        return window.dialog.confirm(title, { message: message, type: "destructive" });
      }
      return window.confirm(message) ? Promise.resolve() : Promise.reject();
    }

    // Run the control's native action now that the user has confirmed, bypassing our interceptor.
    function proceed(el) {
      if (el.tagName === "FORM") {
        el.setAttribute("data-rg-confirmed", "1");
        if (el.requestSubmit) el.requestSubmit(); else el.submit();
      } else if (el.form) {
        // submit button inside a form — its form carries no data-confirm, so this submits cleanly
        if (el.form.requestSubmit) el.form.requestSubmit(el); else el.form.submit();
      } else if (el.tagName === "A" && el.getAttribute("href")) {
        window.location.href = el.getAttribute("href");
      } else {
        el.setAttribute("data-rg-confirmed", "1");
        el.click();
      }
    }

    function wire() {
      var els = document.querySelectorAll("[data-confirm]");
      for (var i = 0; i < els.length; i++) {
        (function (el) {
          var evt = el.tagName === "FORM" ? "submit" : "click";
          el.addEventListener(evt, function (e) {
            if (el.getAttribute("data-rg-confirmed") === "1") { el.removeAttribute("data-rg-confirmed"); return; }
            e.preventDefault();
            e.stopPropagation();
            var title = el.getAttribute("data-confirm-title") || "Please confirm";
            ask(title, el.getAttribute("data-confirm")).then(function () { proceed(el); }, function () { /* cancelled */ });
          });
        })(els[i]);
      }
    }

    // Exposed so a page can prompt programmatically: window.rgConfirm("Title", "message").then(fn).
    window.rgConfirm = function (title, message) { return ask(title, message); };

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
    else wire();
  } catch (e) { /* never break the page */ }
})();
