/**
 * Whether a management URL pattern is exactly `http(s)://{host}[:port][/path]`: the placeholder is the whole
 * host and appears nowhere else, with no account, query or fragment. The server checks it the same way.
 */
export function isValidPattern(pattern: string): boolean {
  return /^https?:\/\/\{host\}(?::\d{1,5})?(?:\/[^?#{}\s@]*)?$/.test(pattern);
}

/** `host:port` of a URL, the port defaulted by the scheme, so two spellings of one address compare equal. */
export function hostPort(url: string): string {
  try {
    const u = new URL(url);
    return `${u.hostname.toLowerCase()}:${u.port || (u.protocol === 'https:' ? '443' : '80')}`;
  } catch {
    return url;
  }
}
