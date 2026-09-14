/** A listing's navigable state: filter, sort and page live in the URL, not in local state (non-negotiable #9). */
export interface ResourceSearch {
  q?: string;
  sort?: string;
  page?: number;
}

export function validateResourceSearch(raw: Record<string, unknown>): ResourceSearch {
  const out: ResourceSearch = {};
  if (typeof raw.q === 'string' && raw.q) out.q = raw.q;
  if (typeof raw.sort === 'string' && raw.sort) out.sort = raw.sort;
  const page = Number(raw.page);
  if (Number.isFinite(page) && page > 1) out.page = Math.floor(page);
  return out;
}
