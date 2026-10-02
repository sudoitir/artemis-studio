/** What the Topology page keeps in its address, so a view can be shared and restored. */
export interface TopologySearch {
  /** The chosen endpoint's id. */
  node?: string;
  /** `table` shows the nodes as a table; the graph is the default and is left out. */
  view?: 'table';
  /** The table's sort: a column's `sortKey`, with a leading `-` for descending. */
  sort?: string;
}

export function validateTopologySearch(raw: Record<string, unknown>): TopologySearch {
  const out: TopologySearch = {};
  if (typeof raw.node === 'string' && raw.node) out.node = raw.node;
  if (raw.view === 'table') out.view = 'table';
  if (typeof raw.sort === 'string' && /^-?[a-z]+$/.test(raw.sort)) out.sort = raw.sort;
  return out;
}
