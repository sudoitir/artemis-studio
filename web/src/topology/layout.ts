import type { Edge, Node } from '@xyflow/react';

import type { HealthView, LogicalNodeView, NodeEndpointView, TopologyView } from '../api/client.ts';

/**
 * The identity-axis grammar, as a pure layout function (was `PairSpine`).
 *
 * A synced backup adopts its primary's NodeID (Phase 0), so a pair is ONE
 * identity with a reflection: the serving endpoint above the axis, the replica
 * below. State is read from the shape first —
 *
 *   - which side of the axis a box sits on = HA role (live above, standby below)
 *   - both boxes above the axis = split-brain CRITICAL (two nodes live in a pair)
 *   - a dashed connecting edge + an offset bottom box = replication behind
 *   - a translucent dashed box = discovered, not yet manageable
 *
 * — and colour only enters when something is wrong. Every node also prints a
 * status word, so colour is never the sole signal (non-negotiable #6).
 */

export const COL_W = 260;
export const LIVE_Y = 40;
export const BACKUP_Y = 200;
const SPLIT_BRAIN_DX = 150;

export type NodeKind = 'live' | 'standby' | 'down' | 'unmanaged';

export interface BrokerNodeData extends Record<string, unknown> {
  name: string;
  kind: NodeKind;
  statusWord: string;
  shortId: string;
  version: string | null;
  address: string | null;
  lastError: string | null;
  offset: boolean;
  unmanaged: boolean;
  srSentence: string;
}

export interface TopologyLayout {
  nodes: Node<BrokerNodeData>[];
  edges: Edge[];
  axisStatus: 'ok' | 'behind' | 'suspected' | 'critical';
  summary: string;
}

function host(url: string | null): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.port ? `${u.hostname}:${u.port}` : u.hostname;
  } catch {
    return url;
  }
}

function kindOf(endpoint: NodeEndpointView, serving: boolean): NodeKind {
  if (!endpoint.manageable) return 'unmanaged';
  if (endpoint.state === 'STOPPED' || endpoint.lastError) return 'down';
  return serving ? 'live' : 'standby';
}

function statusWordOf(kind: NodeKind, endpoint: NodeEndpointView): string {
  switch (kind) {
    case 'live':
      return 'live';
    case 'down':
      return endpoint.lastError ? 'unreachable' : 'stopped';
    case 'unmanaged':
      return 'discovered — no management URL';
    case 'standby':
      return endpoint.replicaSync === false ? 'not caught up' : 'standby';
  }
}

function brokerNode(
  id: string,
  x: number,
  y: number,
  endpoint: NodeEndpointView,
  serving: boolean,
  offset: boolean,
  logicalId: string | null,
): Node<BrokerNodeData> {
  const kind = kindOf(endpoint, serving);
  const statusWord = statusWordOf(kind, endpoint);
  return {
    id,
    type: kind === 'unmanaged' ? 'unmanaged' : 'broker',
    position: { x, y },
    draggable: false,
    connectable: false,
    data: {
      name: endpoint.name,
      kind,
      statusWord,
      shortId: (logicalId ?? '—').slice(0, 8),
      version: endpoint.version ?? null,
      address: host(endpoint.jolokiaUrl ?? null) ?? endpoint.coreUrl ?? null,
      lastError: endpoint.lastError ?? null,
      offset,
      unmanaged: kind === 'unmanaged',
      srSentence: `${endpoint.name}: ${statusWord}${endpoint.version ? `, Artemis ${endpoint.version}` : ''}.`,
    },
  };
}

function layoutLogicalNode(
  logical: LogicalNodeView,
  column: number,
): { nodes: Node<BrokerNodeData>[]; edges: Edge[] } {
  const x = column * COL_W;
  const serving = logical.endpoints.filter((e) => e.active && !e.lastError);
  const others = logical.endpoints.filter((e) => !(e.active && !e.lastError));
  const nodes: Node<BrokerNodeData>[] = [];
  const edges: Edge[] = [];

  if (logical.splitBrain === 'CRITICAL') {
    serving.forEach((e, i) => {
      nodes.push(
        brokerNode(e.id, x + i * SPLIT_BRAIN_DX, LIVE_Y, e, true, false, logical.artemisNodeId ?? null),
      );
    });
    return { nodes, edges };
  }

  const top = serving[0] ?? null;
  const bottom = others[0] ?? null;
  if (top) {
    nodes.push(brokerNode(top.id, x, LIVE_Y, top, true, false, logical.artemisNodeId ?? null));
  }
  if (bottom) {
    nodes.push(
      brokerNode(
        bottom.id,
        x,
        BACKUP_Y,
        bottom,
        false,
        logical.replicationBehind,
        logical.artemisNodeId ?? null,
      ),
    );
  }
  if (top && bottom) {
    edges.push({
      id: `${top.id}--${bottom.id}`,
      source: top.id,
      target: bottom.id,
      style: {
        stroke: logical.replicationBehind
          ? 'var(--as-graph-edge-behind)'
          : 'var(--as-graph-edge)',
        strokeDasharray: logical.replicationBehind ? '6 4' : undefined,
      },
    });
  }
  return { nodes, edges };
}

export function layout(topology: TopologyView, health: HealthView): TopologyLayout {
  const ordered = [...topology.nodes].sort((a, b) =>
    (a.artemisNodeId ?? '').localeCompare(b.artemisNodeId ?? ''),
  );

  const nodes: Node<BrokerNodeData>[] = [];
  const edges: Edge[] = [];
  ordered.forEach((logical, i) => {
    const part = layoutLogicalNode(logical, i);
    nodes.push(...part.nodes);
    edges.push(...part.edges);
  });

  const axisStatus: TopologyLayout['axisStatus'] =
    health.splitBrain === 'CRITICAL'
      ? 'critical'
      : health.splitBrain === 'SUSPECTED'
        ? 'suspected'
        : health.replicationBehind
          ? 'behind'
          : 'ok';

  return { nodes, edges, axisStatus, summary: summarise(topology, health) };
}

function summarise(topology: TopologyView, health: HealthView): string {
  const parts = topology.nodes.map((n) => {
    const id = (n.artemisNodeId ?? 'unknown').slice(0, 8);
    const live = n.endpoints.filter((e) => e.active && !e.lastError).map((e) => e.name);
    const standby = n.endpoints.filter((e) => !(e.active && !e.lastError)).map((e) => e.name);
    return `node ${id}: ${live.join(', ') || 'none'} live${
      standby.length ? `, ${standby.join(', ')} standby` : ''
    }`;
  });
  return `Cluster health ${health.level.toLowerCase()}. ${parts.join('; ')}.`;
}
