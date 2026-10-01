/** How a sortable column stands: whether the table is sorted by it, and which way. */
export interface HeaderSorting {
  active: boolean;
  desc: boolean;
}

/** A `sort` query value as the column key it names and whether it descends (a leading `-`). */
export function parseSort(sort: string | undefined): { field: string | undefined; desc: boolean } {
  return { field: sort?.replace(/^-/, ''), desc: sort?.startsWith('-') ?? false };
}

/** The sort a click on `key`'s header asks for: ascending, then descending, then none. */
export function nextSort(sort: string | undefined, key: string): string | undefined {
  const { field, desc } = parseSort(sort);
  if (field !== key) return key;
  return desc ? undefined : `-${key}`;
}

/** How a column with `sortKey` stands under `sort`, or null when it cannot sort here. */
export function sortingOf(
  sort: string | undefined,
  sortKey: string | undefined,
  enabled: boolean,
): HeaderSorting | null {
  if (!sortKey || !enabled) return null;
  return { active: parseSort(sort).field === sortKey, desc: parseSort(sort).desc };
}
