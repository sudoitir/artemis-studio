/** Schemes a link taken from data may use. Anything else (`javascript:`, `data:`, `vbscript:`) is not a link. */
const LINK_SCHEMES = new Set(['http:', 'https:', 'mailto:']);

/**
 * The address to put in an `href`, `src` or `window.open` when it came from data (a plugin's vendor
 * link, an identity provider's start path, a link in broker data), or `undefined` when it must not
 * be linked. It allows `http:`, `https:`, `mailto:` and addresses on this page's own origin, which is
 * how a relative path resolves. The check parses the value the way a browser does, so leading
 * whitespace, tabs and newlines inside the scheme cannot hide one.
 */
export function safeHref(value: string | null | undefined): string | undefined {
  if (!value) return undefined;
  let url: URL;
  try {
    url = new URL(value, globalThis.location.href);
  } catch {
    return undefined;
  }
  return url.origin === globalThis.location.origin || LINK_SCHEMES.has(url.protocol) ? value : undefined;
}
