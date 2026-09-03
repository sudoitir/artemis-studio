import { Button, VisuallyHidden } from '@mantine/core';
import type { LogicalNodeView, NodeEndpointView } from '../api/client.ts';
import styles from './PairSpine.module.css';

/**
 * One logical node drawn as an identity axis with a reflection. A synced backup
 * adopts the primary's NodeID (Phase 0), so a pair is one identity: the serving
 * side above the axis, the replica below. State is read from the shape first —
 * which side a box sits on, whether the halves align, solid vs dashed border —
 * and colour only appears when something is wrong.
 */
export function PairSpine({
  node,
  onAddManagementUrl,
}: {
  node: LogicalNodeView;
  onAddManagementUrl: (endpoint: NodeEndpointView) => void;
}) {
  const serving = node.endpoints.filter((e) => e.active);
  const others = node.endpoints.filter((e) => !e.active);
  const shortId = (node.artemisNodeId ?? '—').slice(0, 8);

  if (node.splitBrain === 'CRITICAL') {
    return (
      <div className={styles.spine}>
        <VisuallyHidden>
          Split-brain: two nodes are live under node {shortId}. This is critical.
        </VisuallyHidden>
        <div className={styles.brains} role="alert">
          {serving.map((e) => (
            <NodeCard key={e.id} endpoint={e} slot="live" />
          ))}
        </div>
        <Axis
          nodeId={node.artemisNodeId}
          critical
          note="both nodes live"
        />
      </div>
    );
  }

  const top = serving[0] ?? null;
  const bottom = others[0] ?? null;
  const behind = node.replicationBehind;
  const suspected = node.splitBrain === 'SUSPECTED';

  return (
    <div className={styles.spine}>
      <VisuallyHidden>{summarise(node, serving, others)}</VisuallyHidden>

      <div className={styles.slot}>
        {top ? (
          <NodeCard endpoint={top} slot="live" />
        ) : (
          <EmptySlot label="No node is currently serving traffic." />
        )}
      </div>

      <Axis
        nodeId={node.artemisNodeId}
        broken={behind || suspected}
        note={
          suspected
            ? 'checking — two nodes reporting active'
            : behind
              ? 'replication behind'
              : undefined
        }
      />

      <div className={styles.slot}>
        {bottom ? (
          bottom.manageable ? (
            <NodeCard endpoint={bottom} slot="standby" offset={behind} />
          ) : (
            <UnmanagedCard
              endpoint={bottom}
              onAdd={() => onAddManagementUrl(bottom)}
            />
          )
        ) : (
          <EmptySlot label="No replica announced." />
        )}
      </div>
    </div>
  );
}

function Axis({
  nodeId,
  broken = false,
  critical = false,
  note,
}: {
  nodeId: string | null;
  broken?: boolean;
  critical?: boolean;
  note?: string;
}) {
  return (
    <div
      className={styles.axis}
      data-broken={broken || undefined}
      data-critical={critical || undefined}
    >
      <span className={styles.rule} />
      <span className={styles.nodeId}>{nodeId ?? 'no node id yet'}</span>
      {note ? <span className={styles.axisNote}>{note}</span> : null}
      <span className={styles.rule} />
    </div>
  );
}

function NodeCard({
  endpoint,
  slot,
  offset = false,
}: {
  endpoint: NodeEndpointView;
  slot: 'live' | 'standby';
  offset?: boolean;
}) {
  const stopped = endpoint.state === 'STOPPED';
  const markKind = stopped ? 'down' : slot === 'live' ? 'live' : 'standby';
  const status = stopped
    ? 'stopped'
    : slot === 'live'
      ? 'live'
      : endpoint.replicaSync === false
        ? 'not caught up'
        : 'standby';

  return (
    <div className={styles.node} data-offset={offset || undefined}>
      <div className={styles.nodeHead}>
        <span className={styles.name}>{endpoint.name}</span>
        {endpoint.version ? (
          <span className={styles.version}>{endpoint.version}</span>
        ) : null}
      </div>
      <div className={styles.nodeBody}>
        <span className={styles.mark} data-kind={markKind} />
        <span>{status}</span>
        <span className={styles.addr}>
          {endpoint.jolokiaUrl
            ? hostOf(endpoint.jolokiaUrl)
            : (endpoint.coreUrl ?? '')}
        </span>
      </div>
      {endpoint.lastError ? (
        <div className={styles.nodeBody}>
          <span className={styles.addr}>{endpoint.lastError}</span>
        </div>
      ) : null}
    </div>
  );
}

function UnmanagedCard({
  endpoint,
  onAdd,
}: {
  endpoint: NodeEndpointView;
  onAdd: () => void;
}) {
  return (
    <div className={styles.node} data-unmanaged="true">
      <div className={styles.nodeHead}>
        <span className={styles.addr}>{endpoint.coreUrl ?? endpoint.name}</span>
        <Button size="compact-xs" variant="default" onClick={onAdd}>
          Add management URL
        </Button>
      </div>
      <div className={styles.nodeBody}>
        <span>
          Its pair reported this address. It is a broker-to-broker connector, not
          a management URL, so Studio cannot reach it yet.
        </span>
      </div>
    </div>
  );
}

function EmptySlot({ label }: { label: string }) {
  return (
    <div className={styles.node} data-empty="true">
      {label}
    </div>
  );
}

function hostOf(url: string): string {
  try {
    const u = new URL(url);
    return u.port ? `${u.hostname}:${u.port}` : u.hostname;
  } catch {
    return url;
  }
}

function summarise(
  node: LogicalNodeView,
  serving: NodeEndpointView[],
  others: NodeEndpointView[],
): string {
  const id = (node.artemisNodeId ?? 'unknown').slice(0, 8);
  const live = serving.map((e) => `${e.name} live`).join(', ') || 'none live';
  const standby =
    others
      .map(
        (e) =>
          `${e.name} ${e.manageable ? 'standby' : 'discovered, not manageable'}`,
      )
      .join(', ') || 'no replica';
  const repl = node.replicationBehind ? 'replication behind' : 'replication in sync';
  return `Node ${id}: ${live}; ${standby}; ${repl}.`;
}
