import { useMemo, useState } from 'react';
import { SegmentedControl } from '@mantine/core';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { useServerNow } from '../../kernel/time/time.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { AddManagementUrl } from './AddManagementUrl.tsx';
import { useHealth, useTopology, type HealthView, type TopologyView as Topology } from './api.ts';
import { layout } from './layout.ts';
import { allNodeFacts } from './nodeFacts.ts';
import { NodePanel, NodePanelMissing, NodePanelPlaceholder } from './NodePanel.tsx';
import { TopologyCanvas } from './TopologyCanvas.tsx';
import { TopologyTable } from './TopologyTable.tsx';
import type { TopologySearch } from './topologySearch.ts';
import styles from './Topology.module.css';

type SetSearch = (patch: Partial<Record<keyof TopologySearch, unknown>>) => void;

/**
 * The reason a query has nothing to show, once it has failed at least once: retrying is TanStack's
 * business, but the operator should read the cause now rather than a loader that outlasts the retries.
 * A query that has data keeps showing it through a failed refresh.
 */
function failureOf(query: { data: unknown; error: unknown; failureCount: number; failureReason: unknown }) {
  if (query.data !== undefined) return null;
  return query.error ?? (query.failureCount > 0 ? query.failureReason : null);
}

/**
 * The topology of a cluster: which nodes it has, which is serving, which stands behind it, and whether
 * any pair is in trouble, every state in words. The graph and the table state the same facts
 * (`nodeFacts`); the chosen node and the view live in the address.
 */
export function TopologyView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const search = useSearch({ strict: false }) as TopologySearch;
  const navigate = useNavigate();
  const topology = useTopology(clusterId);
  const health = useHealth(clusterId);

  const setSearch: SetSearch = (patch) => {
    void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, ...patch }) });
  };
  const failure = failureOf(topology) ?? failureOf(health);
  const view = search.view ?? 'graph';

  let body;
  if (failure) {
    body = (
      <ErrorState
        error={failure}
        onRetry={() => {
          void topology.refetch();
          void health.refetch();
        }}
      />
    );
  } else if (topology.data === undefined || health.data === undefined) {
    body = (
      <div className={styles.loading}>
        <LoadingState label="Loading the topology" blockSize="var(--topology-frame-block)" />
      </div>
    );
  } else if (topology.data.nodes.length === 0) {
    body = (
      <EmptyState
        kind="empty"
        title="No node has answered yet"
        description="Studio learns the topology from the first broker it reaches. Nothing has answered on this cluster's seed address yet, so there is nothing to draw. Studio keeps looking and lists the nodes here as they answer."
      />
    );
  } else {
    body = (
      <TopologyBody
        clusterId={clusterId}
        topology={topology.data}
        health={health.data}
        search={search}
        setSearch={setSearch}
      />
    );
  }

  return (
    <Page>
      <PageHeader
        title="Topology"
        description="The nodes of this cluster, which one is serving, which stands behind it, and whether every pair is in step."
        actions={
          <SegmentedControl
            size="xs"
            aria-label="Show as"
            data={[
              { value: 'graph', label: 'Graph' },
              { value: 'table', label: 'Table' },
            ]}
            value={view}
            onChange={(value) => setSearch({ view: value === 'table' ? 'table' : undefined })}
          />
        }
      />
      {body}
    </Page>
  );
}

/** The graph or the table, beside the panel of the chosen node. */
function TopologyBody({
  clusterId,
  topology,
  health,
  search,
  setSearch,
}: Readonly<{
  clusterId: string;
  topology: Topology;
  health: HealthView;
  search: TopologySearch;
  setSearch: SetSearch;
}>) {
  useDisplayZone();
  const now = useServerNow(5_000);
  const [addingFor, setAddingFor] = useState<string | null>(null);
  const model = useMemo(() => layout(topology, health), [topology, health]);
  const nodes = useMemo(() => allNodeFacts(topology), [topology]);
  const selectedId = search.node ?? null;
  const select = (nodeId: string | null) => setSearch({ node: nodeId ?? undefined });

  const logical = selectedId ? topology.nodes.find((n) => n.endpoints.some((e) => e.id === selectedId)) : undefined;
  const facts = nodes.find((f) => f.id === selectedId);
  const endpoint = addingFor ? topology.nodes.flatMap((n) => n.endpoints).find((e) => e.id === addingFor) : undefined;

  let panel;
  if (!selectedId) panel = <NodePanelPlaceholder />;
  else if (facts && logical) {
    panel = <NodePanel facts={facts} logical={logical} now={now} onAddManagementUrl={() => setAddingFor(facts.id)} />;
  } else panel = <NodePanelMissing onClear={() => select(null)} />;

  return (
    <div className={styles.body}>
      <div className={styles.main}>
        {search.view === 'table' ? (
          <div className={styles.tableFrame}>
            <TopologyTable
              nodes={nodes}
              selectedId={selectedId}
              sort={search.sort}
              onSortChange={(sort) => setSearch({ sort })}
              onSelect={select}
            />
          </div>
        ) : (
          <TopologyCanvas
            model={model}
            clusterId={clusterId}
            selectedId={selectedId}
            onSelect={select}
            onShowTable={() => setSearch({ view: 'table' })}
          />
        )}
      </div>
      <aside className={styles.aside} aria-label="Chosen node">
        {panel}
      </aside>
      <AddManagementUrl
        clusterId={clusterId}
        endpoint={endpoint ?? null}
        opened={addingFor !== null}
        onClose={() => setAddingFor(null)}
      />
    </div>
  );
}
