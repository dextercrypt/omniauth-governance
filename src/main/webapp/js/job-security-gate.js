/*
 * Grays out the job-level "Enable project-based security" block on a job's
 * /configure page, since OmniAuth manages job permissions centrally.
 *
 * Served as an external, same-origin script (CSP script-src 'self'); the
 * previous inline <script> injected by OmniAuthPageDecorator is blocked by
 * Jenkins' CSP and never ran.
 */
(function () {
  "use strict";
  try {
    if (!window.location.pathname.match(/\/configure$/)) return;
    function run() {
      var cb = document.querySelector('input[name="useProjectSecurity"]');
      if (!cb) return;
      var c = cb.closest(".optionalBlock-container");
      if (!c) {
        c = cb.parentElement;
        var d = 0;
        while (c) {
          if (c.classList && (c.classList.contains("jenkins-form-item") || c.classList.contains("optionalBlock-container"))) break;
          c = c.parentElement;
          if (++d > 10) { c = null; break; }
        }
      }
      if (!c) return;
      var w = document.createElement("div");
      w.style.cssText = "position:relative;margin-bottom:4px;";
      c.parentNode.insertBefore(w, c);
      w.appendChild(c);
      c.style.cssText = "opacity:0.4;pointer-events:none;user-select:none;";
      var b = document.createElement("div");
      b.style.cssText = "padding:10px 14px;background:#fff8e1;border:1px solid #ffcc02;border-radius:6px;margin-bottom:6px;font-size:13px;color:#795600;display:flex;align-items:center;gap:8px;";
      b.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg><span><strong>Managed by OmniAuth</strong> — Job-level permission matrix is disabled. Configure access via <strong>Manage Jenkins → OmniAuth → Access Management</strong>.</span>';
      w.insertBefore(b, c);
    }
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", run);
    } else {
      run();
    }
  } catch (e) { /* never break the page */ }
})();
