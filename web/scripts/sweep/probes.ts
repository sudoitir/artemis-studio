/**
 * The code the sweep runs inside the page, kept as source strings.
 *
 * The scripts are typed for Node alone (no DOM library, like the other scripts here), and Playwright
 * serialises a function to a string anyway; a string says plainly what crosses into the browser.
 */

/**
 * Installed before any page script, on every document, so it sees the whole load.
 *
 * - Layout shifts are collected from navigation start with `buffered: true`, so the swap from skeleton
 *   to content counts, which an observer started after load never sees. Shifts right after input are
 *   excluded, as the metric defines it; a sweep does not type.
 * - Content-Security-Policy violations are collected from the document, not from the console, because a
 *   blocked font or style is easy to miss in a console full of other noise.
 */
export const INIT_PROBES = `(() => {
  const sweep = (window.__sweep = { cls: 0, shifts: 0, csp: [] });
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) {
      if (entry.hadRecentInput) continue;
      sweep.cls += entry.value;
      sweep.shifts += 1;
    }
  }).observe({ type: 'layout-shift', buffered: true });
  document.addEventListener('securitypolicyviolation', (event) => {
    sweep.csp.push({ directive: event.violatedDirective, blocked: event.blockedURI, source: event.sourceFile });
  });
})();`;

/** The scheme Mantine reads before it renders; its own storage, not `prefers-color-scheme`. */
export const storeScheme = (scheme: 'light' | 'dark') =>
  `try { localStorage.setItem('mantine-color-scheme-value', '${scheme}'); } catch {}`;

/** True while something on the page says it is still loading. */
export const BUSY = `!!document.querySelector('.mantine-Skeleton-root, .mantine-Loader-root, [aria-busy="true"], [data-loading]')`;

/**
 * What the page looks like at the moment of capture: the layout shift total, policy violations, the
 * scheme Mantine actually applied, whether the page scrolls sideways, and every table or scroller
 * whose content is wider than its box.
 */
export const MEASURE = `(() => {
  const root = document.documentElement;
  const scrollers = [];
  for (const el of document.querySelectorAll('[role="grid"], table, [class*="scroller" i], [data-table-scroller]')) {
    if (el.scrollWidth > el.clientWidth + 1) {
      scrollers.push({
        tag: el.tagName.toLowerCase(),
        role: el.getAttribute('role'),
        className: String(el.className).slice(0, 80),
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
      });
    }
  }
  return {
    cls: window.__sweep.cls,
    shifts: window.__sweep.shifts,
    csp: window.__sweep.csp,
    scheme: root.getAttribute('data-mantine-color-scheme'),
    pageOverflow: root.scrollWidth > root.clientWidth ? { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth } : null,
    scrollers,
  };
})()`;

export interface Measure {
  cls: number;
  shifts: number;
  csp: { directive: string; blocked: string; source: string }[];
  scheme: string | null;
  pageOverflow: { scrollWidth: number; clientWidth: number } | null;
  scrollers: { tag: string; role: string | null; className: string; scrollWidth: number; clientWidth: number }[];
}
