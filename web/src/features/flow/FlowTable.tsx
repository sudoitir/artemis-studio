import { useMemo } from 'react';
import { Text } from '@mantine/core';

import { useServerNow } from '../../kernel/time/time.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { FlowGraphView, FlowNodeView } from './api.ts';
import { pathColumns, type PathRow } from './columns.ts';
import { FAULT_LABELS, RELATION, rateSortValue } from './flowFormat.ts';
import { focusOf, hasActions } from './flowSearch.ts';
import { FlowNodeActions } from './rowActions.tsx';

const rowKey = (r: PathRow) => r.id;

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
}: Readonly<{
  clusterId: string;
  graph: FlowGraphView;
  sort: string | undefined;
  onSortChange: (sort: string | undefined) => void;
  onFocus: (focus: string) => void;
}>) {
  const now = useServerNow(5_000);
  const rows = useMemo(() => sortRows(toRows(graph), sort), [graph, sort]);
  const columns = useMemo(() => pathColumns(now), [now]);

  return (
    <DataTable
      label="Flow paths"
      storageKey="flow.paths"
      height="fill"
      columns={columns}
      data={rows}
      sort={sort}
      onSortChange={onSortChange}
      rowKey={rowKey}
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
            <Text size="sm" c="dimmed" px="sm" py="xs">
              Nothing to open for this path.
            </Text>
          );
        },
      }}
      empty={
        <EmptyState
          kind="empty"
          title="No paths to list"
          description="A path is one hop of a message's journey: a client producing to an address, an address routing to a queue, a queue consumed by a client. Studio draws a path once it sees a producer, a consumer or a binding on this cluster, so an empty table means none has been seen yet."
        />
      }
    />
  );
}

function toRows(graph: FlowGraphView): PathRow[] {
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
function subjectOf(row: PathRow): FlowNodeView | undefined {
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
function sortRows(rows: PathRow[], sort: string | undefined): PathRow[] {
  const desc = sort ? sort.startsWith('-') : true;
  const field = sort ? sort.replace(/^-/, '') : 'rate';
  const dir = desc ? -1 : 1;
  const text = (r: PathRow): string => {
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
