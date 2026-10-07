import { randomId } from '@mantine/hooks';

const DEFAULT_PORT = '8161';
const DEFAULT_PATH = '/console/jolokia';
const BARE_PATHS = new Set(['', '/', '/console', '/console/']);

/** An extra seed row of a connection form: the address typed, and an id that outlives the row's position. */
export interface SeedRow {
  url: string;
  key: string;
}

export function seedRow(url = ''): SeedRow {
  return { url, key: randomId() };
}

export interface NormalisedSeed {
  /** What the operator typed, verbatim — shown back on an error. */
  original: string;
  /** The normalised URL, or `null` if it still doesn't parse as a URL. */
  url: string | null;
}

/**
 * Lowers the cost of a wrong seed entry: a bare host becomes a full Jolokia URL
 * with the conventional port and path filled in. Never silently rewrites what the
 * operator typed without showing them the result — the caller renders
 * `original` next to `url` so a guessed default is visible, not hidden.
 */
export function normaliseSeeds(raw: string): NormalisedSeed[] {
  const tokens = raw
    .split(/[\n,;]+|\s+/)
    .map((t) => t.trim())
    .filter(Boolean);

  const seen = new Set<string>();
  const out: NormalisedSeed[] = [];
  for (const original of tokens) {
    const url = normaliseOne(original);
    const key = url ?? original;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ original, url });
  }
  return out;
}

function normaliseOne(token: string): string | null {
  let candidate = token;
  if (!/^https?:\/\//i.test(candidate)) {
    candidate = `http://${candidate}`;
  }
  try {
    const u = new URL(candidate);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    // `URL.port` is empty both when no port was typed and when the scheme's default (80, 443) was, so
    // the typed text decides: only a URL with no port at all gets the conventional one.
    if (!u.port && !hasExplicitPort(candidate)) u.port = DEFAULT_PORT;
    // `/console` is what an operator copies out of the browser's address bar. The
    // agent lives one level deeper, and posting to the console itself only bounces
    // to its login page, so fill the rest of the path in rather than let that fail.
    if (BARE_PATHS.has(u.pathname)) u.pathname = DEFAULT_PATH;
    return u.toString();
  } catch {
    return null;
  }
}

/** Whether the URL's authority names a port, `host:80` or `[::1]:443`, which `URL.port` hides when it is the default. */
function hasExplicitPort(url: string): boolean {
  const authority = url.replace(/^https?:\/\//i, '').split(/[/?#]/, 1)[0];
  return /:\d+$/.test(authority.replace(/^.*@/, ''));
}
