/*
 * Applies the viewer's colour scheme and table density before React runs, so a reload never shows one
 * frame in the wrong scheme or at the wrong row height (ADR-0158, ADR-0161). A static same-origin script,
 * loaded synchronously from index.html: the Content-Security-Policy allows it without 'unsafe-inline'.
 *
 * It mirrors two stored values and accepts nothing outside their known sets:
 *   mantine-color-scheme-value  'light' | 'dark' | 'auto' (Mantine's own key, stored as a bare string)
 *   as:density                  'compact' | 'comfortable' (JSON, as the density hook stores it)
 */
(function () {
  var root = document.documentElement;

  function read(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (e) {
      return null;
    }
  }

  var scheme = read('mantine-color-scheme-value');
  if (scheme !== 'light' && scheme !== 'dark') {
    scheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  root.setAttribute('data-mantine-color-scheme', scheme);

  var density = 'compact';
  try {
    if (JSON.parse(read('as:density')) === 'comfortable') density = 'comfortable';
  } catch (e) {
    // Unparseable: the default stands.
  }
  root.setAttribute('data-density', density);
})();
