import { useMemo } from 'react';

import { useServerNow } from '../../kernel/time/time.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { NodeFacts } from './nodeFacts.ts';
import { sortTopology, topologyColumns } from './topologyColumns.tsx';
import styles from './Topology.module.css';

const rowKey = (f: NodeFacts) => f.id;

/**
 * The topology as a table: every node the graph draws, with the same facts in words (the graph's
 * text equivalent, and the view that stays whole above the level-of-detail bound). Choosing a row
 * chooses the node, as choosing its box does.
 */
export function TopologyTable({
  nodes,
  selectedId,
  sort,
  onSortChange,
  onSelect,
}: Readonly<{
  nodes: NodeFacts[];
  selectedId: string | null;
  sort: string | undefined;
  onSortChange: (sort: string | undefined) => void;
  onSelect: (nodeId: string) => void;
}>) {
  const now = useServerNow(5_000);
  const columns = useMemo(() => topologyColumns(now), [now]);
  const rows = useMemo(() => sortTopology(nodes, sort), [nodes, sort]);
  return (
    <DataTable
      label="Nodes"
      storageKey="topology.nodes"
      height="fill"
      columns={columns}
      data={rows}
      rowKey={rowKey}
      sort={sort}
      onSortChange={onSortChange}
      onRowClick={(f) => onSelect(f.id)}
      rowClassName={(f) => (f.id === selectedId ? styles.selectedRow : undefined)}
      empty={
        <EmptyState
          kind="empty"
          title="No node has answered yet"
          description="Studio lists a node here as soon as it answers on the cluster's seed address."
        />
      }
    />
  );
}
