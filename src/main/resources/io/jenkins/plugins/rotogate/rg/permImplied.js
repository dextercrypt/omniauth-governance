/*
 * Implied-permission handling for the Rotogate permission pickers (role builder, user grant,
 * group grant). Mirrors what matrix-auth / role-strategy do: when a stronger permission is
 * ticked, the permissions it implies are auto-ticked and disabled, so the admin can't un-grant
 * something that the stronger permission already confers. Include per page:
 *   <st:adjunct includes="io.jenkins.plugins.rotogate.rg.permImplied"/>
 *
 * Each permission checkbox carries data-implied-by="<implying permission id>" (empty if none).
 * A forced checkbox is left disabled, so its value is not submitted — that is correct, because
 * RotogateItemACL grants an implied permission at check time whenever the implying one is granted
 * (it walks the impliedBy chain), exactly as Jenkins core does.
 *
 * CSP-safe: external same-origin script, all wiring via addEventListener. Uses literal && (never
 * EL) as this is a static adjunct.
 */
(function () {
  "use strict";
  try {
    function wire(root) {
      var cbs = Array.prototype.slice.call(root.querySelectorAll('input[type="checkbox"][data-implied-by]'));
      if (cbs.length < 2) return;

      var byId = {};
      cbs.forEach(function (cb) { byId[cb.value] = cb; });

      // The admin's own ticks, tracked independently of the forced (implied) state.
      var explicit = {};
      cbs.forEach(function (cb) { if (cb.checked) explicit[cb.value] = true; });

      var applying = false;

      function recompute() {
        // Transitive closure: a permission is forced on when its implier is on (explicit or forced).
        var forced = {};
        var changed = true;
        while (changed) {
          changed = false;
          cbs.forEach(function (cb) {
            var parent = cb.getAttribute("data-implied-by");
            if (parent && byId[parent] && (explicit[parent] || forced[parent]) && !forced[cb.value]) {
              forced[cb.value] = true;
              changed = true;
            }
          });
        }
        applying = true;
        cbs.forEach(function (cb) {
          var wantChecked = forced[cb.value] ? true : !!explicit[cb.value];
          var wantDisabled = !!forced[cb.value];
          if (cb.checked !== wantChecked || cb.disabled !== wantDisabled) {
            cb.checked = wantChecked;
            cb.disabled = wantDisabled;
            // Let any page-specific picker JS (counts, chips) stay in sync.
            cb.dispatchEvent(new Event("change", { bubbles: true }));
          }
        });
        applying = false;
      }

      root.addEventListener("change", function (e) {
        if (applying) return;
        var cb = e.target;
        if (!cb || typeof cb.getAttribute !== "function" || cb.getAttribute("data-implied-by") === null) return;
        if (cb.disabled) return;
        if (cb.checked) explicit[cb.value] = true; else delete explicit[cb.value];
        recompute();
      }, true);

      recompute();
    }

    function init() {
      // One picker per page, but scope to each form so ids never collide across pickers.
      var forms = document.querySelectorAll("form");
      var wiredAny = false;
      for (var i = 0; i < forms.length; i++) {
        if (forms[i].querySelector('input[data-implied-by]')) { wire(forms[i]); wiredAny = true; }
      }
      if (!wiredAny) wire(document);
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
  } catch (e) { /* never break the page */ }
})();
