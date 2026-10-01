import { Ago } from '../../kernel/time/Ago.tsx';
import type { Column } from '../../ui/table/index.ts';
import { lastSeenWords, type NodeFacts } from './nodeFacts.ts';

const NOT_REPORTED = 'Not reported';

/**
 * The topology table's columns: the same facts the graph's boxes and the side panel state, one row per
 * endpoint. The node identifies a row and is never hidden; the facts that say whether it is well come
 * next, and the version, the address, when it last answered and its NodeID go first when the window is
 * narrow. `now` ages "Last seen", so a view builds its columns again when its clock ticks.
 */
export function topologyColumns(now: number): Column<NodeFacts>[] {
  return [
    {
      id: 'node',
      header: 'Node',
      accessor: (f) => f.name,
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'node',
    },
    {
      id: 'role',
      header: 'Role',
      accessor: (f) => f.role,
      kind: 'status',
      priority: 'high',
      sortKey: 'role',
    },
    {
      id: 'pair',
      header: 'Pair',
      accessor: (f) => f.pair,
      kind: 'text',
      priority: 'high',
      sortKey: 'pair',
    },
    {
      id: 'liveness',
      header: 'Liveness',
      accessor: (f) => f.liveness.text,
      kind: 'text',
      priority: 'high',
      sortKey: 'liveness',
    },
    {
      id: 'version',
      header: 'Version',
      accessor: (f) => (f.version ? `${f.version}${f.versionFlag ? ` (${f.versionFlag})` : ''}` : 'Unknown'),
      kind: 'status',
      priority: 'low',
      sortKey: 'version',
    },
    {
      id: 'address',
      header: 'Address',
      accessor: (f) => f.address ?? NOT_REPORTED,
      kind: 'code',
      priority: 'low',
      sortKey: 'address',
    },
    {
      id: 'seen',
      header: 'Last seen',
      accessor: (f) => lastSeenWords(f.lastSeenAt, now).relative,
      cell: (f) =>
        f.lastSeenAt ? <Ago at={f.lastSeenAt} now={now} /> : <>{lastSeenWords(f.lastSeenAt, now).relative}</>,
      kind: 'time',
      priority: 'low',
      sortKey: 'seen',
    },
    {
      id: 'nodeId',
      header: 'Node ID',
      accessor: (f) => f.artemisNodeId ?? NOT_REPORTED,
      kind: 'identifier',
      priority: 'low',
      sortKey: 'nodeid',
    },
  ];
}

const SORT: Record<string, (f: NodeFacts) => string | number> = {
  node: (f) => f.name,
  role: (f) => f.role,
  pair: (f) => f.pair,
  liveness: (f) => f.liveness.text,
  version: (f) => f.version ?? '',
  address: (f) => f.address ?? '',
  // The most recent answer is the largest; a node that never answered sorts as the oldest.
  seen: (f) => (f.lastSeenAt ? Date.parse(f.lastSeenAt) : 0),
  nodeid: (f) => f.artemisNodeId ?? '',
};

/** The rows in the order `sort` asks for (a column's `sortKey`, `-` for descending); the graph's order when there is none. */
export function sortTopology(rows: NodeFacts[], sort: string | undefined): NodeFacts[] {
  const key = sort?.replace(/^-/, '');
  const by = key ? SORT[key] : undefined;
  if (!by) return rows;
  const dir = sort?.startsWith('-') ? -1 : 1;
  return [...rows].sort((a, b) => {
    const x = by(a);
    const y = by(b);
    const order = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y));
    return dir * order || a.name.localeCompare(b.name);
  });
}
