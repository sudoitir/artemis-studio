import { useMemo } from 'react';
import { Text } from '@mantine/core';

import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import type { FlowEdgeView, FlowGraphView, FlowNodeView } from './api.ts';
import { edgeText, FAULT_LABELS, RELATION, rateSortValue, rateSourceLabel } from './flowFormat.ts';
import { focusOf, hasActions } from './flowSearch.ts';
import { FlowNodeActions } from './rowActions.tsx';
import classes from './FlowView.module.css';

const KIND: Record<string, string> = {
  PRODUCER: 'client',
  ADDRESS: 'address',
  QUEUE: 'queue',
  CONSUMER: 'client',
  REMOTE: 'remote',
};

interface Row {
  id: string;
  edge: FlowEdgeView;
  from: FlowNodeView | undefined;
  to: FlowNodeView | undefined;
  faults: string[];
}

/**
 * The same paths the graph draws, as rows (flow-visualization spec: the table presents the same
 * paths as the graph). One row per edge; activating a row focuses its client or queue, and its
 * menu opens that resource elsewhere, never changing the broker.
 */
export function FlowTable({
  clusterId,
  graph,
  sort,
  onSortChange,
  onFocus,
}: {
  clusterId: string;
  graph: FlowGraphView;
  sort: string | undefined;
  onSortChange: (sort: string | undefined) => void;
  onFocus: (focus: string) => void;
}) {
  const now = useServerNow(5_000);
  const rows = useMemo(() => sortRows(toRows(graph), sort), [graph, sort]);

  const columns: GridColumn<Row>[] = [
    { id: 'from', header: 'From', sortKey: 'from', accessor: (r) => r.from?.label, cell: (r) => <NodeName node={r.from} /> },
    { id: 'relation', header: 'Relation', sortKey: 'relation', width: 120, accessor: (r) => RELATION[r.edge.kind ?? ''] },
    { id: 'to', header: 'To', sortKey: 'to', accessor: (r) => r.to?.label, cell: (r) => <NodeName node={r.to} /> },
    {
      id: 'rate',
      header: 'Rate',
      sortKey: 'rate',
      numeric: true,
      width: 150,
      accessor: (r) => edgeText(r.edge),
      cell: (r) => (
        <Text size="sm" className={r.edge.stale ? classes.stale : classes.figure}>
          {edgeText(r.edge)}
        </Text>
      ),
    },
    {
      id: 'source',
      header: 'Rate from',
      width: 240,
      accessor: (r) => rateSourceLabel(r.edge),
      cell: (r) => (
        <Text size="xs" c="dimmed">
          {rateSourceLabel(r.edge)}
          {r.edge.asOf ? ` · ${elapsedLabel(now - Date.parse(r.edge.asOf))} ago` : ''}
          {r.edge.stale ? ' · stale' : ''}
        </Text>
      ),
    },
    {
      id: 'clients',
      header: 'Clients',
      sortKey: 'clients',
      numeric: true,
      width: 90,
      accessor: (r) => r.edge.members ?? '',
      cell: (r) => <Text size="sm" className={classes.figure}>{r.edge.members ?? ''}</Text>,
    },
    {
      id: 'faults',
      header: 'Faults',
      sortKey: 'faults',
      width: 150,
      accessor: (r) => r.faults.join(', '),
      cell: (r) =>
        r.faults.length === 0 ? null : (
          <Text size="sm" fw={600} className={classes.alarm}>
            {r.faults.join(', ')}
          </Text>
        ),
    },
  ];

  return (
    <VirtualTable
      label="Flow paths"
      columns={columns}
      data={rows}
      sort={sort}
      onSortChange={onSortChange}
      rowKey={(r) => r.id}
      onRowClick={(r) => {
        const subject = subjectOf(r);
        const focus = subject ? focusOf(subject) : null;
        if (focus) onFocus(focus);
      }}
      rowMenu={{
        label: (r) => subjectOf(r)?.label ?? r.id,
        render: (r, menu) => {
          const subject = subjectOf(r);
          return subject && hasActions(subject) ? (
            <FlowNodeActions clusterId={clusterId} node={subject} restoreFocus={menu.restoreFocus} />
          ) : (
            <Text size="sm" c="dimmed" px="sm" py={6}>
              Nothing to open for this path.
            </Text>
          );
        },
      }}
      emptyLabel={<Text size="sm">No paths to list for this view.</Text>}
    />
  );
}

function NodeName({ node }: { node: FlowNodeView | undefined }) {
  if (!node) return null;
  return (
    <Text size="sm" truncate title={node.label}>
      <Text span size="xs" c="dimmed">
        {KIND[node.kind ?? '']}{' '}
      </Text>
      {node.label}
      {node.members && node.members > 1 ? (
        <Text span size="xs" c="dimmed">
          {' '}×{node.members}
        </Text>
      ) : null}
    </Text>
  );
}

function toRows(graph: FlowGraphView): Row[] {
  const byId = new Map((graph.nodes ?? []).map((n) => [n.id, n]));
  return (graph.edges ?? []).map((edge) => {
    const from = byId.get(edge.source ?? '');
    const to = byId.get(edge.target ?? '');
    const faults = [...(edge.faults ?? []), ...(edge.kind === 'ROUTE' ? (to?.faults ?? []) : [])].map(
      (f) => FAULT_LABELS[f] ?? f.toLowerCase(),
    );
    return { id: edge.id ?? `${edge.source}->${edge.target}`, edge, from, to, faults };
  });
}

/** The resource a row is about: its client, its queue, or the address it diverts to. */
function subjectOf(row: Row): FlowNodeView | undefined {
  switch (row.edge.kind) {
    case 'PRODUCE':
    case 'BRIDGE':
    case 'CLUSTER_HOP':
      return row.from;
    case 'CONSUME':
    case 'ROUTE':
      return row.to;
    case 'DIVERT':
    case 'WILDCARD':
    case 'DEAD_LETTER':
    case 'EXPIRY':
      return row.to?.kind === 'ADDRESS' ? row.to : undefined;
    default:
      return undefined;
  }
}

/** Default: busiest first, unknown rates last. A column sort keeps unknown rates last in both directions. */
function sortRows(rows: Row[], sort: string | undefined): Row[] {
  const desc = sort ? sort.startsWith('-') : true;
  const field = sort ? sort.replace(/^-/, '') : 'rate';
  const dir = desc ? -1 : 1;
  const text = (r: Row): string => {
    switch (field) {
      case 'from':
        return r.from?.label ?? '';
      case 'to':
        return r.to?.label ?? '';
      case 'relation':
        return RELATION[r.edge.kind ?? ''] ?? '';
      default:
        return r.faults.join(',');
    }
  };
  return [...rows].sort((a, b) => {
    if (field === 'rate') {
      return dir * (rateSortValue(a.edge.rate, desc) - rateSortValue(b.edge.rate, desc)) || a.id.localeCompare(b.id);
    }
    if (field === 'clients') {
      return dir * ((a.edge.members ?? -1) - (b.edge.members ?? -1)) || a.id.localeCompare(b.id);
    }
    return dir * text(a).localeCompare(text(b)) || a.id.localeCompare(b.id);
  });
}
