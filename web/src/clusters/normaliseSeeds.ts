const DEFAULT_PORT = '8161';
const DEFAULT_PATH = '/console/jolokia';

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
    if (!u.port) u.port = DEFAULT_PORT;
    if (u.pathname === '' || u.pathname === '/') u.pathname = DEFAULT_PATH;
    return u.toString();
  } catch {
    return null;
  }
}
