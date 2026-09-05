import { useMemo } from 'react';
import {
  Background,
  Handle,
  Position,
  ReactFlow,
  type NodeProps,
} from '@xyflow/react';
import { Button, VisuallyHidden } from '@mantine/core';

import type { TopologyLayout, BrokerNodeData } from './layout.ts';
import styles from './TopologyGraph.module.css';

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
        {d.version ? <span className={styles.badge}>{d.version}</span> : null}
      </div>
      <div className={styles.body}>
        <span className={styles.mark} data-kind={d.kind} aria-hidden="true" />
        <span className={styles.word}>{d.statusWord}</span>
      </div>
      {d.address ? <span className={styles.addr}>{d.address}</span> : null}
      {d.lastError ? <span className={styles.addr}>{d.lastError}</span> : null}
      <span className={styles.badge}>id {d.shortId}</span>
      <Handle type="source" position={Position.Bottom} className={styles.handle} />
    </div>
  );
}

function UnmanagedNode({ data }: NodeProps) {
  const d = data as BrokerNodeData;
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
      <Button component="span" size="compact-xs" variant="default" disabled>
        Add a management URL in Settings
      </Button>
    </div>
  );
}

const nodeTypes = { broker: BrokerNode, unmanaged: UnmanagedNode };

/**
 * The identity-axis grammar, rendered from an already-computed {@link TopologyLayout}
 * (extracted out of {@link TopologyGraph} so the registration preview and its example
 * cards can draw the exact same graph a live cluster uses — no forked visual
 * language, design.md Decision 6). Purely presentational: no data hooks.
 */
export function TopologyCanvas({
  model,
  interactive = true,
}: {
  model: TopologyLayout;
  interactive?: boolean;
}) {
  const proOptions = useMemo(() => ({ hideAttribution: true }), []);
  return (
    <div className={styles.wrapper}>
      <div className={styles.axis} data-status={model.axisStatus} aria-hidden="true">
        <span className={styles.axisNote}>
          {model.axisStatus === 'critical'
            ? 'two nodes live in one pair'
            : model.axisStatus === 'suspected'
              ? 'checking — two nodes reporting active'
              : model.axisStatus === 'behind'
                ? 'replication behind'
                : 'shared NodeID'}
        </span>
      </div>
      <ReactFlow
        nodes={model.nodes}
        edges={model.edges}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.25 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnScroll={interactive}
        zoomOnScroll={interactive}
        proOptions={proOptions}
      >
        <Background variant={undefined} gap={20} />
      </ReactFlow>
      <VisuallyHidden role="status">{model.summary}</VisuallyHidden>
    </div>
  );
}
