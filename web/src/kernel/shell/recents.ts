/**
 * The views and resources an operator opened most recently on a cluster, for the palette's Recent
 * group (ADR-0109). Browser-local and per viewer: a convenience that is fine to lose with site data,
 * never state anything depends on.
 */
export interface Recent {
  label: string;
  /** Where it is: the pathname, and the search it was opened with. */
  to: string;
  search: Record<string, unknown>;
  /** What kind of place it is, shown as the entry's description. */
  kind: string;
}

const MAX = 8;
const key = (clusterId: string) => `as:recents:${clusterId}`;

export function readRecents(clusterId: string): Recent[] {
  try {
    const raw = window.localStorage.getItem(key(clusterId));
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed.filter((r): r is Recent => typeof r?.label === 'string' && typeof r?.to === 'string').slice(0, MAX)
      : [];
  } catch {
    return [];
  }
}

/** Records a visit, most recent first, one entry per label. */
export function recordRecent(clusterId: string, recent: Recent) {
  try {
    const next = [recent, ...readRecents(clusterId).filter((r) => r.label !== recent.label)].slice(0, MAX);
    window.localStorage.setItem(key(clusterId), JSON.stringify(next));
  } catch {
    // Storage refused (private window, quota): recents are a convenience, and silently absent.
  }
}
