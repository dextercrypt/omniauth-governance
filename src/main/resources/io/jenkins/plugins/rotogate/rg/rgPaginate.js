/*
 * Reusable client-side table paginator for Rotogate management pages.
 *
 * Tag any table with class `rg-paginated` (optionally `data-page-size="N"`, default 15) and this
 * script slices its rows into pages, inserting a footer control (‹ Prev / "Page X of Y" / Next ›)
 * plus a "Showing A-B of T" label right after the table. The pager only appears once a table
 * actually exceeds its page size, so short tables look untouched. Include where needed:
 *   <st:adjunct includes="io.jenkins.plugins.rotogate.rg.rgPaginate"/>
 *
 * Filter-aware by design. The paginator NEVER touches a row's inline `style.display`; it hides
 * out-of-page rows with the `.rg-pg-off` class only. A page that already filters rows (search /
 * status pills) keeps doing exactly what it does - setting `style.display='none'` on non-matches -
 * and simply calls `RGPaginate.refresh(table)` afterwards. The paginator then paginates only the
 * rows the filter left visible (inline display !== 'none'), so paging follows the filtered set and
 * resets to page 1 on every filter change.
 *
 * CSP-safe: external same-origin script, all wiring via addEventListener, no inline on* handlers,
 * no injected markup beyond text nodes and plain elements.
 */
(function () {
  "use strict";

  var DEFAULT_PAGE_SIZE = 15;
  var registry = [];

  function bodyRows(table) {
    var tb = table.tBodies && table.tBodies[0];
    if (!tb) return [];
    return Array.prototype.filter.call(tb.children, function (n) { return n.tagName === "TR"; });
  }

  // Rows the paginator is allowed to page = those a filter hasn't hidden. A filter hides a
  // non-match either with inline `display:none` or with a page-specific class named in the
  // table's `data-hide-class` (e.g. auditLog's "alx-hidden"); the paginator's own hiding uses
  // the separate `.rg-pg-off` class, so the two never clash.
  function eligibleRows(all, hideClass) {
    return all.filter(function (r) {
      if (r.style.display === "none") return false;
      if (hideClass && r.classList.contains(hideClass)) return false;
      return true;
    });
  }

  function render(state) {
    var elig = eligibleRows(bodyRows(state.table), state.hideClass);
    var total = elig.length;
    var pages = Math.max(1, Math.ceil(total / state.pageSize));
    if (state.page > pages) state.page = pages;
    if (state.page < 1) state.page = 1;

    var start = (state.page - 1) * state.pageSize;
    var end = start + state.pageSize;
    elig.forEach(function (r, i) {
      if (i >= start && i < end) r.classList.remove("rg-pg-off");
      else r.classList.add("rg-pg-off");
    });

    var show = total > state.pageSize;
    state.pager.hidden = !show;
    if (show) {
      var a = total === 0 ? 0 : start + 1;
      var b = Math.min(end, total);
      state.info.textContent = "Showing " + a + "-" + b + " of " + total;
      state.pageLbl.textContent = "Page " + state.page + " of " + pages;
      state.prev.disabled = state.page <= 1;
      state.next.disabled = state.page >= pages;
    }
  }

  function buildPager(state) {
    var pager = document.createElement("div");
    pager.className = "rg-pager";

    var info = document.createElement("span");
    info.className = "rg-pager__info";

    var nav = document.createElement("div");
    nav.className = "rg-pager__nav";

    var prev = document.createElement("button");
    prev.type = "button";
    prev.className = "rg-pager__btn";
    prev.textContent = "‹";
    prev.setAttribute("aria-label", "Previous page");

    var pageLbl = document.createElement("span");
    pageLbl.className = "rg-pager__page";

    var next = document.createElement("button");
    next.type = "button";
    next.className = "rg-pager__btn";
    next.textContent = "›";
    next.setAttribute("aria-label", "Next page");

    nav.appendChild(prev);
    nav.appendChild(pageLbl);
    nav.appendChild(next);
    pager.appendChild(info);
    pager.appendChild(nav);

    prev.addEventListener("click", function () { state.page--; render(state); });
    next.addEventListener("click", function () { state.page++; render(state); });

    state.pager = pager;
    state.info = info;
    state.pageLbl = pageLbl;
    state.prev = prev;
    state.next = next;

    state.table.parentNode.insertBefore(pager, state.table.nextSibling);
  }

  function init() {
    var tables = document.querySelectorAll("table.rg-paginated");
    Array.prototype.forEach.call(tables, function (table) {
      if (table.__rgPg) return;
      var ps = parseInt(table.getAttribute("data-page-size"), 10);
      if (!ps || ps < 1) ps = DEFAULT_PAGE_SIZE;
      var state = { table: table, pageSize: ps, page: 1, hideClass: table.getAttribute("data-hide-class") || null };
      buildPager(state);
      table.__rgPg = state;
      registry.push(state);
      render(state);

      // Jenkins' `sortable` reorders the rows in place on a header click; re-page afterwards so
      // the current page reflects the new order rather than the old DOM positions.
      if (table.classList.contains("sortable")) {
        var head = table.tHead;
        if (head) {
          head.addEventListener("click", function () {
            setTimeout(function () { render(state); }, 0);
          });
        }
      }
    });
  }

  // Public hook for filter scripts: recompute after they change which rows are visible.
  // Resets to page 1 since the filtered set has changed.
  window.RGPaginate = {
    refresh: function (table) {
      var state = table && table.__rgPg;
      if (!state) return;
      state.page = 1;
      render(state);
    },
    refreshAll: function () {
      registry.forEach(function (s) { s.page = 1; render(s); });
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
