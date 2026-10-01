import { elapsedLabel } from '../../kernel/time/time.ts';
import type { Column } from '../../ui/table/index.ts';
import type { FlowEdgeView, FlowNodeView } from './api.ts';
import { faultsCell, figureCell, nodeCell, nodeNameCell, nodeText, rateCell, sourceCell } from './cells.tsx';
import { edgeText, formatCount, formatRate, RELATION, rateSourceLabel } from './flowFormat.ts';

/** One path of the flow, one row of the paths table. */
export interface PathRow {
  id: string;
  edge: FlowEdgeView;
  from: FlowNodeView | undefined;
  to: FlowNodeView | undefined;
  faults: string[];
}

function sourceText(edge: FlowEdgeView, now: number): string {
  return `${rateSourceLabel(edge)}${edge.asOf ? ` · ${elapsedLabel(now - Date.parse(edge.asOf))} ago` : ''}${edge.stale ? ' · stale' : ''}`;
}

/**
 * The paths table's columns. Both ends of a path identify its row and are never hidden; the rate and
 * the faults go next, and where a rate came from and how many clients share it are the first to be
 * hidden. `now` ages the "rate from" text, so a view builds its columns again when it ticks.
 */
export function pathColumns(now: number): Column<PathRow>[] {
  return [
    {
      id: 'from',
      header: 'From',
      accessor: (r) => nodeText(r.from),
      cell: (r) => nodeCell(r.from),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'from',
    },
    {
      id: 'relation',
      header: 'Relation',
      accessor: (r) => RELATION[r.edge.kind ?? ''],
      kind: 'status',
      priority: 'high',
      sortKey: 'relation',
    },
    {
      id: 'to',
      header: 'To',
      accessor: (r) => nodeText(r.to),
      cell: (r) => nodeCell(r.to),
      kind: 'identifier',
      priority: 'essential',
      sortKey: 'to',
    },
    {
      id: 'rate',
      header: 'Rate',
      accessor: (r) => edgeText(r.edge),
      cell: (r) => rateCell(r.edge),
      kind: 'number',
      priority: 'high',
      sortKey: 'rate',
    },
    {
      id: 'source',
      header: 'Rate from',
      accessor: (r) => sourceText(r.edge, now),
      cell: (r) => sourceCell(sourceText(r.edge, now)),
      kind: 'text',
      priority: 'low',
    },
    {
      id: 'clients',
      header: 'Clients',
      accessor: (r) => r.edge.members ?? '',
      kind: 'number',
      priority: 'low',
      sortKey: 'clients',
    },
    {
      id: 'faults',
      header: 'Faults',
      accessor: (r) => r.faults.join(', '),
      cell: (r) => faultsCell(r.faults),
      kind: 'status',
      priority: 'high',
      sortKey: 'faults',
    },
  ];
}

const UNKNOWN = 'unknown';
const MEASURING = 'measuring…';
const count = (n: number | null | undefined) => (n === null || n === undefined ? UNKNOWN : formatCount(n));
// Rates are messages per second, named in the column headers ("In/s"), so the figures fit the pane.
const rate = (n: number | null | undefined) => (n === null || n === undefined ? MEASURING : formatRate(n));

/** One row per broker node; `stale` is a node that did not answer, whose figures are unknown, not zero. */
export interface NodeRow {
  nodeId: string;
  node: string;
  messageCount?: number | null;
  consumerCount?: number | null;
  inRate?: number | null;
  outRate?: number | null;
  stale: boolean;
}

/** What a per-node table is about: a resource's totals, what a client produces, or what it consumes. */
export type NodeShape = 'resource' | 'producer' | 'consumer';

function figure(id: string, header: string, value: (r: NodeRow) => string, priority: 'high' | 'low'): Column<NodeRow> {
  const text = (r: NodeRow) => (r.stale ? UNKNOWN : value(r));
  return {
    id,
    header,
    accessor: text,
    cell: (r) => figureCell(text(r), r.stale),
    kind: 'number',
    priority,
  };
}

/**
 * The per-node table's columns. The node is the row's identity and its attribution, so it is never
 * hidden; its state rides in its own cell, since a column for it would not fit beside the graph. The
 * rates come before the secondary counts.
 */
export function nodeColumns(shape: NodeShape): Column<NodeRow>[] {
  const node: Column<NodeRow> = {
    id: 'node',
    header: 'Node',
    accessor: (r) => (r.stale ? `${r.node} (did not answer)` : r.node),
    cell: (r) => nodeNameCell(r.node, r.stale),
    kind: 'identifier',
    priority: 'essential',
  };
  const inRate = figure('in', shape === 'producer' ? 'Sends/s' : 'In/s', (r) => rate(r.inRate), 'high');
  const outRate = figure('out', shape === 'consumer' ? 'Receives/s' : 'Out/s', (r) => rate(r.outRate), 'high');
  if (shape === 'producer') return [node, inRate];
  if (shape === 'consumer') return [node, outRate];
  return [
    node,
    figure('backlog', 'Backlog', (r) => count(r.messageCount), 'high'),
    figure('consumers', 'Consumers', (r) => count(r.consumerCount), 'low'),
    inRate,
    outRate,
  ];
}
