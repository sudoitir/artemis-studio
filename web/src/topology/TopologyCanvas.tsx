import { createContext, useContext, useEffect, useMemo } from 'react';
import {
  Background,
  Controls,
  Handle,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type NodeProps,
} from '@xyflow/react';
import { Button, Text, VisuallyHidden } from '@mantine/core';

import {
  AXIS_Y,
  EDGE_MARKS,
  NODE_MARKS,
  isBrokerNode,
  type BrokerNodeData,
  type PairGroupData,
  type TopologyLayout,
} from './layout.ts';
import styles from './TopologyGraph.module.css';

/**
 * The canvas's actions, carried by context rather than through `layout()` — which
 * stays a pure function. A canvas rendered without a provider (the registration
 * preview's example cards) shows the invitation as plain text instead of a control
 * that cannot be used.
 */
export interface TopologyActionsValue {
  /** Open the add-a-management-URL flow for one discovered-but-unreachable endpoint. */
  addManagementUrl?: (endpointId: string) => void;
  /** Re-run discovery — the only action that helps a cluster with no nodes at all. */
  rediscover?: () => void;
}

const ActionsContext = createContext<TopologyActionsValue>({});

export function TopologyActions({
  value,
  children,
}: {
  value: TopologyActionsValue;
  children: React.ReactNode;
}) {
  return <ActionsContext.Provider value={value}>{children}</ActionsContext.Provider>;
}

function PairGroup({ data }: NodeProps) {
  const d = data as PairGroupData;
  return (
    <div className={styles.group} data-status={d.axisStatus}>
      <span className={styles.groupId}>id {d.shortId}</span>
      <div className={styles.groupAxis} style={{ insetBlockStart: AXIS_Y }} aria-hidden="true">
        <span className={styles.axisNote}>{d.axisNote}</span>
      </div>
    </div>
  );
}

function BrokerNode({ data }: NodeProps) {
  const d = data as BrokerNodeData;
  return (
    <div
      className={styles.node}
      data-kind={d.kind}
      data-offset={d.offset || undefined}
      tabIndex={0}
      aria-label={d.srSentence}
    >
      <Handle type="target" position={Position.Top} className={styles.handle} />
      <div className={styles.head}>
        <span className={styles.name}>{d.name}</span>
        {d.firing ? (
          <span className={styles.alertDot} title="An alert is firing on this node" />
        ) : null}
        {d.version ? <span className={styles.badge}>{d.version}</span> : null}
      </div>
      <div className={styles.body}>
        <span className={styles.mark} data-kind={d.kind} aria-hidden="true" />
        <span className={styles.word}>{d.statusWord}</span>
      </div>
      {d.address ? <span className={styles.addr}>{d.address}</span> : null}
      {d.lastError ? <span className={styles.addr}>{d.lastError}</span> : null}
      <Handle type="source" position={Position.Bottom} className={styles.handle} />
    </div>
  );
}

function UnmanagedNode({ id, data }: NodeProps) {
  const d = data as BrokerNodeData;
  const { addManagementUrl } = useContext(ActionsContext);
  return (
    <div className={styles.node} data-kind="unmanaged" tabIndex={0} aria-label={d.srSentence}>
      <Handle type="target" position={Position.Top} className={styles.handle} />
      <div className={styles.head}>
        <span className={styles.name}>{d.address ?? d.name}</span>
      </div>
      <div className={styles.body}>
        <span className={styles.mark} data-kind="unmanaged" aria-hidden="true" />
        <span className={styles.word}>discovered — not manageable</span>
      </div>
      {addManagementUrl ? (
        <Button size="compact-xs" variant="default" onClick={() => addManagementUrl(id)}>
          Add a management URL
        </Button>
      ) : (
        <span className={styles.cta}>Add a management URL to manage it</span>
      )}
    </div>
  );
}

const nodeTypes = { broker: BrokerNode, unmanaged: UnmanagedNode, pair: PairGroup };

/** Re-fit when the set of logical nodes changes, so a failover leaves no stale viewport. */
function RefitOnNodeSetChange({ signature }: { signature: string }) {
  const flow = useReactFlow();
  useEffect(() => {
    flow.fitView({ padding: 0.15, maxZoom: 1 });
  }, [flow, signature]);
  return null;
}

function Legend() {
  return (
    <div className={styles.legend}>
      {NODE_MARKS.map((m) => (
        <span key={m.kind} className={styles.legendItem}>
          <span className={styles.mark} data-kind={m.kind} aria-hidden="true" />
          {m.label}
        </span>
      ))}
      {EDGE_MARKS.map((e) => (
        <span key={e.kind} className={styles.legendItem}>
          <span className={styles.legendEdge} data-kind={e.kind} aria-hidden="true" />
          {e.label}
        </span>
      ))}
      <span className={styles.legendItem}>
        <span className={styles.legendAxis} aria-hidden="true" />
        shared NodeID — serving above, standby below
      </span>
    </div>
  );
}

function EmptyCanvas({ height }: { height?: string }) {
  const { rediscover } = useContext(ActionsContext);
  return (
    <div className={styles.wrapper} style={height ? { blockSize: height } : undefined}>
      <div className={styles.empty}>
        <Text fw={600}>No nodes yet</Text>
        <Text size="sm" c="dimmed">
          Studio learns the topology from the first broker it reaches. Nothing has
          answered on this cluster's seed address yet.
        </Text>
        {rediscover ? (
          <Button size="compact-sm" variant="default" onClick={rediscover}>
            Rediscover
          </Button>
        ) : null}
      </div>
    </div>
  );
}

/**
 * The identity-axis grammar, rendered from an already-computed {@link TopologyLayout}
 * (extracted out of {@link TopologyGraph} so the registration preview and its example
 * cards can draw the exact same graph a live cluster uses — no forked visual
 * language, design.md Decision 6). Purely presentational: no data hooks.
 */
export function TopologyCanvas({
  model,
  interactive = true,
  height,
}: {
  model: TopologyLayout;
  interactive?: boolean;
  /** Override the frame height. Embedders that already constrain the box pass `100%`. */
  height?: string;
}) {
  const proOptions = useMemo(() => ({ hideAttribution: true }), []);
  const signature = useMemo(
    () =>
      model.nodes
        .filter((n) => !isBrokerNode(n))
        .map((n) => n.id)
        .join('|'),
    [model.nodes],
  );

  if (model.nodes.length === 0) return <EmptyCanvas height={height} />;

  return (
    <div>
      <div className={styles.wrapper} style={height ? { blockSize: height } : undefined}>
        <ReactFlowProvider>
          <ReactFlow
            nodes={model.nodes}
            edges={model.edges}
            nodeTypes={nodeTypes}
            fitView
            /* React Flow's own default is maxZoom 2, which blows a two-node
               cluster up to fill the frame. Never magnify past natural size. */
            fitViewOptions={{ padding: 0.15, maxZoom: 1 }}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            panOnScroll={interactive}
            zoomOnScroll={interactive}
            proOptions={proOptions}
          >
            <Background variant={undefined} gap={20} />
            {interactive ? <Controls showInteractive={false} /> : null}
            <RefitOnNodeSetChange signature={signature} />
          </ReactFlow>
        </ReactFlowProvider>
      </div>
      {interactive ? <Legend /> : null}
      <VisuallyHidden role="status">{model.summary}</VisuallyHidden>
    </div>
  );
}
