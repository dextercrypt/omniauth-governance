/*
 * Shared in-app confirmation modal for OmniAuth management pages.
 *
 * Replaces the native browser confirm() for any destructive control carrying a `data-confirm`
 * attribute (per-row Revoke / Delete / Keep-All, etc.). Include where needed:
 *   <st:adjunct includes="io.jenkins.plugins.omniauth.oau.oauConfirm"/>
 *
 * CSP-safe: external same-origin script, all wiring via addEventListener, no inline on* handlers,
 * no inline <script>. The element's native default action (a form submit) is preserved and runs
 * ONLY after the user confirms, so a mis-wire can never fire a destructive submit without a prompt.
 *
 * Supports `data-confirm` on a submit <button> (intercepts click → submits its form on OK) and on a
 * <form> (intercepts submit). Message text is applied with textContent, never innerHTML.
 */
(function () {
  "use strict";
  try {
    function showModal(message, onOk) {
      var ov = document.createElement("div");
      ov.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);" +
        "z-index:100000;display:flex;align-items:center;justify-content:center;";

      var card = document.createElement("div");
      card.setAttribute("role", "dialog");
      card.setAttribute("aria-modal", "true");
      card.style.cssText = "background:var(--card-background, #fff);color:var(--text-color, #1f2937);" +
        "border-radius:10px;padding:22px 24px;max-width:420px;width:90%;box-shadow:0 10px 40px rgba(0,0,0,0.3);";

      var msg = document.createElement("div");
      msg.style.cssText = "font-size:14px;line-height:1.5;margin-bottom:20px;";
      msg.textContent = message || "Are you sure?";

      var actions = document.createElement("div");
      actions.style.cssText = "display:flex;gap:8px;justify-content:flex-end;";

      var cancel = document.createElement("button");
      cancel.type = "button";
      cancel.className = "jenkins-button";
      cancel.textContent = "Cancel";

      var ok = document.createElement("button");
      ok.type = "button";
      // Base button shape from jenkins-button; NOT jenkins-button--primary (that forces the blue
      // primary colour over the inline style). Destructive action → red, set with !important so the
      // theme's button rules can't win it back.
      ok.className = "jenkins-button";
      ok.textContent = "Confirm";
      ok.style.cssText = "background:var(--error-color, #dc2626) !important;" +
        "border-color:var(--error-color, #dc2626) !important;color:#fff !important;";

      actions.appendChild(cancel);
      actions.appendChild(ok);
      card.appendChild(msg);
      card.appendChild(actions);
      ov.appendChild(card);

      function close() {
        if (ov.parentNode) ov.parentNode.removeChild(ov);
        document.removeEventListener("keydown", onKey);
      }
      function onKey(e) { if (e.key === "Escape") close(); }

      ov.addEventListener("click", function (e) { if (e.target === ov) close(); });
      cancel.addEventListener("click", close);
      ok.addEventListener("click", function () { close(); onOk(); });
      document.addEventListener("keydown", onKey);

      document.body.appendChild(ov);
      ok.focus();
    }

    // Run the control's native action now that the user has confirmed, bypassing our own interceptor.
    function proceed(el) {
      if (el.tagName === "FORM") {
        el.setAttribute("data-oau-confirmed", "1");
        if (el.requestSubmit) el.requestSubmit(); else el.submit();
      } else if (el.form) {
        // submit button inside a form — its form carries no data-confirm, so this submits cleanly
        if (el.form.requestSubmit) el.form.requestSubmit(el); else el.form.submit();
      } else if (el.tagName === "A" && el.getAttribute("href")) {
        window.location.href = el.getAttribute("href");
      } else {
        el.setAttribute("data-oau-confirmed", "1");
        el.click();
      }
    }

    function wire() {
      var els = document.querySelectorAll("[data-confirm]");
      for (var i = 0; i < els.length; i++) {
        (function (el) {
          var evt = el.tagName === "FORM" ? "submit" : "click";
          el.addEventListener(evt, function (e) {
            if (el.getAttribute("data-oau-confirmed") === "1") { el.removeAttribute("data-oau-confirmed"); return; }
            e.preventDefault();
            e.stopPropagation();
            showModal(el.getAttribute("data-confirm"), function () { proceed(el); });
          });
        })(els[i]);
      }
    }

    // Exposed so a page can prompt programmatically: window.oauConfirm("message", function(){ ... }).
    window.oauConfirm = showModal;

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
    else wire();
  } catch (e) { /* never break the page */ }
})();
