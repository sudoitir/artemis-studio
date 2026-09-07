import type { Edge, Node } from '@xyflow/react';

import type { HealthView, LogicalNodeView, NodeEndpointView, TopologyView } from '../api/client.ts';

/**
 * The identity-axis grammar, as a pure layout function (was `PairSpine`).
 *
 * A synced backup adopts its primary's NodeID (Phase 0), so a pair is ONE
 * identity with a reflection. Each logical node is emitted as a React Flow
 * **group node** with its endpoints as children (`parentId` + `extent: 'parent'`),
 * so the axis that carries the grammar lives in the same transformed pane as the
 * boxes it groups and cannot drift away from them on a pan or zoom. State is read
 * from the shape first —
 *
 *   - which side of the group's axis a box sits on = HA role (serving above, standby below)
 *   - both boxes above the axis, in one group = split-brain CRITICAL
 *   - a dashed connecting edge + an offset bottom box = replication behind
 *   - a translucent dashed box = discovered, not yet manageable
 *
 * — with each state's mark distinguished by **shape**, not brightness, and colour
 * entering only when something is wrong. Every node also prints a status word, so
 * neither colour nor shape is ever the sole signal (non-negotiable #6).
 *
 * Pure by contract: no callbacks and no React state live here. The
 * "add a management URL" action reaches the unmanaged node through a context in
 * `TopologyCanvas`, so the registration preview can render the same graph with no
 * action attached.
 */

/**
 * Box geometry. Children are positioned relative to their group.
 *
 * <p>These are absolute pixels the canvas cannot negotiate with, so the box has
 * to be exactly `NODE_H` tall whatever it contains — `TopologyGraph.module.css`
 * pins it there and clamps the two variable-length lines (the name, the last
 * error) to fit. An endpoint with a long name and an error message used to grow
 * past its slot and sit on top of the standby box below it.
 */
export const NODE_W = 260;
export const NODE_H = 132;
export const GROUP_PAD = 20;
export const GROUP_GAP = 44;
export const LIVE_Y = 36;
export const BACKUP_Y = 216;
export const AXIS_Y = 186;
export const GROUP_H = BACKUP_Y + NODE_H + GROUP_PAD;
const SPLIT_BRAIN_DX = NODE_W + 20;

/** Column pitch for a single-endpoint-wide group; kept for callers that lay out by column. */
export const COL_W = NODE_W + 2 * GROUP_PAD + GROUP_GAP;

export type NodeKind = 'live' | 'standby' | 'behind' | 'down' | 'unmanaged';
export type AxisStatus = 'ok' | 'behind' | 'suspected' | 'critical';

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
  firing: boolean;
  srSentence: string;
}

export interface PairGroupData extends Record<string, unknown> {
  shortId: string;
  axisStatus: AxisStatus;
  axisNote: string;
}

export type TopologyNode = Node<BrokerNodeData> | Node<PairGroupData>;

/**
 * Above this many logical nodes the layout switches to its reduced-detail form
 * (ADR-0056): each pair collapses to one box carrying the pair's summary, and the
 * row wraps into a grid instead of running off to the right forever.
 *
 * Judgement, not measurement — a number in one place, expected to move the first
 * time someone runs this against a genuinely large estate.
 */
export const DENSE_THRESHOLD = 24;

/** Pairs per row once the layout wraps. */
const DENSE_COLUMNS = 8;

/** Row pitch for a collapsed box. */
const DENSE_ROW_H = 140;

export interface TopologyLayout {
  nodes: TopologyNode[];
  edges: Edge[];
  summary: string;
  /**
   * True when detail was reduced to fit the node count. The canvas states it —
   * an operator who cannot tell whether they are seeing everything is worse off
   * than one looking at a slow graph (ADR-0056).
   */
  dense: boolean;
}

/**
 * The mark vocabulary, exported so the legend and the nodes cannot drift apart:
 * the legend renders these entries, and `kindOf` can only return one of these kinds.
 */
export const NODE_MARKS: ReadonlyArray<{ kind: NodeKind; label: string }> = [
  { kind: 'live', label: 'serving' },
  { kind: 'standby', label: 'standby, in sync' },
  { kind: 'behind', label: 'replication behind' },
  { kind: 'down', label: 'stopped or unreachable' },
  { kind: 'unmanaged', label: 'discovered, no management URL' },
];

export const EDGE_MARKS: ReadonlyArray<{ kind: 'replicating' | 'behind'; label: string }> = [
  { kind: 'replicating', label: 'replicating' },
  { kind: 'behind', label: 'not caught up' },
];

/** True for an endpoint box; false for the pair group that contains it. */
export function isBrokerNode(node: TopologyNode): node is Node<BrokerNodeData> {
  return node.type !== 'pair';
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
  if (serving) return 'live';
  return endpoint.replicaSync === false ? 'behind' : 'standby';
}

function statusWordOf(kind: NodeKind, endpoint: NodeEndpointView): string {
  switch (kind) {
    case 'live':
      return 'live';
    case 'down':
      return endpoint.lastError ? 'unreachable' : 'stopped';
    case 'unmanaged':
      return 'discovered — no management URL';
    case 'behind':
      return 'not caught up';
    case 'standby':
      return 'standby';
  }
}

function brokerNode(
  id: string,
  parentId: string,
  x: number,
  y: number,
  endpoint: NodeEndpointView,
  serving: boolean,
  offset: boolean,
  logicalId: string | null,
  firing: boolean,
): Node<BrokerNodeData> {
  const kind = kindOf(endpoint, serving);
  const statusWord = statusWordOf(kind, endpoint);
  return {
    id,
    type: kind === 'unmanaged' ? 'unmanaged' : 'broker',
    parentId,
    extent: 'parent',
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
      firing,
      srSentence: `${endpoint.name}: ${statusWord}${endpoint.version ? `, Artemis ${endpoint.version}` : ''}${firing ? ', alert firing' : ''}.`,
    },
  };
}

function axisStatusOf(logical: LogicalNodeView): AxisStatus {
  if (logical.splitBrain === 'CRITICAL') return 'critical';
  if (logical.splitBrain === 'SUSPECTED') return 'suspected';
  return logical.replicationBehind ? 'behind' : 'ok';
}

function axisNoteOf(status: AxisStatus): string {
  switch (status) {
    case 'critical':
      return 'two nodes live in one pair';
    case 'suspected':
      return 'checking — two nodes reporting active';
    case 'behind':
      return 'replication behind';
    case 'ok':
      return 'shared NodeID';
  }
}

/**
 * One logical node → one group node plus its endpoint children. Returns the
 * group's own width so the caller can pack groups left to right without a
 * split-brain group (which is wider) overlapping its neighbour.
 */
function layoutLogicalNode(
  logical: LogicalNodeView,
  x: number,
  firingNodeIds: ReadonlySet<string>,
): { nodes: TopologyNode[]; edges: Edge[]; width: number } {
  const axisStatus = axisStatusOf(logical);
  const shortId = (logical.artemisNodeId ?? '—').slice(0, 8);
  const groupId = `pair:${logical.artemisNodeId ?? shortId}`;
  const serving = logical.endpoints.filter((e) => e.active && !e.lastError);
  const others = logical.endpoints.filter((e) => !(e.active && !e.lastError));

  const children: Node<BrokerNodeData>[] = [];
  const edges: Edge[] = [];

  if (axisStatus === 'critical') {
    serving.forEach((e, i) => {
      children.push(
        brokerNode(
          e.id,
          groupId,
          GROUP_PAD + i * SPLIT_BRAIN_DX,
          LIVE_Y,
          e,
          true,
          false,
          logical.artemisNodeId ?? null,
          firingNodeIds.has(e.id),
        ),
      );
    });
  } else {
    const top = serving[0] ?? null;
    const bottom = others[0] ?? null;
    if (top) {
      children.push(
        brokerNode(
          top.id,
          groupId,
          GROUP_PAD,
          LIVE_Y,
          top,
          true,
          false,
          logical.artemisNodeId ?? null,
          firingNodeIds.has(top.id),
        ),
      );
    }
    if (bottom) {
      children.push(
        brokerNode(
          bottom.id,
          groupId,
          GROUP_PAD,
          BACKUP_Y,
          bottom,
          false,
          logical.replicationBehind,
          logical.artemisNodeId ?? null,
          firingNodeIds.has(bottom.id),
        ),
      );
    }
    if (top && bottom) {
      edges.push({
        id: `${top.id}--${bottom.id}`,
        source: top.id,
        target: bottom.id,
        style: {
          stroke: logical.replicationBehind ? 'var(--as-graph-edge-behind)' : 'var(--as-graph-edge)',
          strokeDasharray: logical.replicationBehind ? '6 4' : undefined,
        },
      });
    }
  }

  const spread = Math.max(0, children.length - 1) * (axisStatus === 'critical' ? SPLIT_BRAIN_DX : 0);
  const width = NODE_W + spread + 2 * GROUP_PAD;

  const group: Node<PairGroupData> = {
    id: groupId,
    type: 'pair',
    position: { x, y: 0 },
    draggable: false,
    connectable: false,
    selectable: false,
    style: { width, height: GROUP_H },
    data: { shortId, axisStatus, axisNote: axisNoteOf(axisStatus) },
  };

  // React Flow requires a parent to precede its children in the node array.
  return { nodes: [group, ...children], edges, width };
}

/**
 * One logical node as a single box, for the reduced-detail layout.
 *
 * The pair's shape is what is lost, so the box has to say in words what the axis
 * said in geometry: which endpoint is serving, how many stand behind it, and
 * whether either of the two things that make a pair dangerous — split brain,
 * replication behind — is true. A collapsed pair that hides a split brain would
 * be worse than no graph at all.
 */
function collapsedNode(
  logical: LogicalNodeView,
  x: number,
  y: number,
  firingNodeIds: ReadonlySet<string>,
): Node<BrokerNodeData> {
  const axisStatus = axisStatusOf(logical);
  const serving = logical.endpoints.filter((e) => e.active && !e.lastError);
  const others = logical.endpoints.filter((e) => !(e.active && !e.lastError));
  const head = serving[0] ?? others[0] ?? null;
  const shortId = (logical.artemisNodeId ?? '—').slice(0, 8);

  const kind: NodeKind =
    axisStatus === 'critical' || axisStatus === 'suspected'
      ? 'down'
      : axisStatus === 'behind'
        ? 'behind'
        : serving.length > 0
          ? 'live'
          : 'down';

  const statusWord =
    axisStatus === 'critical'
      ? `split brain — ${serving.length} serving`
      : axisStatus === 'suspected'
        ? 'split brain suspected'
        : axisStatus === 'behind'
          ? 'replication behind'
          : serving.length === 0
            ? 'nothing serving'
            : `serving · ${others.length} standby`;

  return {
    id: `collapsed:${logical.artemisNodeId ?? shortId}`,
    type: 'broker',
    position: { x, y },
    draggable: false,
    connectable: false,
    selectable: false,
    data: {
      name: head?.name ?? shortId,
      kind,
      statusWord,
      shortId,
      version: null,
      address: null,
      lastError: null,
      offset: false,
      unmanaged: false,
      firing: logical.endpoints.some((e) => firingNodeIds.has(e.id)),
      srSentence: `Node ${shortId}: ${statusWord}. ${logical.endpoints.length} endpoint${
        logical.endpoints.length === 1 ? '' : 's'
      }.`,
    },
  };
}

export function layout(
  topology: TopologyView,
  health: HealthView,
  firingNodeIds: ReadonlySet<string> = new Set(),
): TopologyLayout {
  const ordered = [...topology.nodes].sort((a, b) =>
    (a.artemisNodeId ?? '').localeCompare(b.artemisNodeId ?? ''),
  );

  const nodes: TopologyNode[] = [];
  const edges: Edge[] = [];
  const dense = ordered.length > DENSE_THRESHOLD;

  if (dense) {
    // Wrapped into a grid rather than one endless row: at this count the strip is
    // several screens wide and the operator can never see two nodes at once.
    ordered.forEach((logical, i) => {
      const column = i % DENSE_COLUMNS;
      const row = Math.floor(i / DENSE_COLUMNS);
      nodes.push(collapsedNode(logical, column * COL_W, row * DENSE_ROW_H, firingNodeIds));
    });
    return { nodes, edges, summary: summarise(topology, health), dense };
  }

  let x = 0;
  for (const logical of ordered) {
    const part = layoutLogicalNode(logical, x, firingNodeIds);
    nodes.push(...part.nodes);
    edges.push(...part.edges);
    x += part.width + GROUP_GAP;
  }

  return { nodes, edges, summary: summarise(topology, health), dense };
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
  const rollUp =
    health.splitBrain === 'CRITICAL'
      ? ' Split-brain confirmed.'
      : health.splitBrain === 'SUSPECTED'
        ? ' Split-brain suspected.'
        : health.replicationBehind
          ? ' Replication is not caught up.'
          : '';
  return `Cluster health ${health.level.toLowerCase()}.${rollUp} ${parts.join('; ')}.`;
}
