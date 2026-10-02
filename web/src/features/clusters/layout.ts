import type { Edge, Node } from '@xyflow/react';

import type { HealthView, LogicalNodeView, NodeEndpointView, TopologyView } from './api.ts';
import { isServing, nodeFacts, type NodeFacts, type NodeKind } from './nodeFacts.ts';

export type { NodeKind };

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
 * Pure by contract: no callbacks and no React state live here. Every word on a box comes
 * from `nodeFacts`, so the box, the table and the side panel say the same thing.
 */

/**
 * Box geometry. Children are positioned relative to their group.
 *
 * <p>These are absolute pixels the canvas cannot negotiate with, so the box has
 * to be exactly `NODE_H` tall whatever it contains — `TopologyCanvas.module.css`
 * pins it there and shortens each of its four lines to one. An endpoint with a
 * long name and an error message used to grow past its slot and sit on top of the
 * standby box below it.
 */
export const NODE_W = 260;
export const NODE_H = 112;
export const GROUP_PAD = 20;
export const GROUP_GAP = 44;
export const LIVE_Y = 36;
export const AXIS_Y = LIVE_Y + NODE_H + 18;
export const BACKUP_Y = AXIS_Y + 30;
export const GROUP_H = BACKUP_Y + NODE_H + GROUP_PAD;
const BOX_DX = NODE_W + 20;

/** Column pitch for a single-endpoint-wide group; kept for callers that lay out by column. */
export const COL_W = NODE_W + 2 * GROUP_PAD + GROUP_GAP;

export type AxisStatus = 'ok' | 'behind' | 'suspected' | 'critical';

/** The four lines of a box, and the sentence that names it. */
export interface BrokerNodeData extends Record<string, unknown> {
  /** Line 1: the name, with the version beside it. */
  name: string;
  version: string | null;
  /** "unsupported" or "untested", beside the version; null when it is neither. */
  versionFlag: string | null;
  /** Line 2: the mark's shape, and the liveness in words. */
  kind: NodeKind;
  liveness: string;
  /** Line 3: the role and the pair. */
  roleLine: string;
  /** Line 4: the address, or the error that stopped Studio reaching the node. */
  detail: string | null;
  detailIsError: boolean;
  offset: boolean;
  /** The broker endpoints this box stands for: one, or every endpoint of a collapsed pair, its head first. */
  nodeIds: string[];
  /** The box's accessible name. */
  sentence: string;
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
export const DENSE_COLUMNS = 8;

/** Row pitch for a collapsed box. */
const DENSE_ROW_H = NODE_H + 16;

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
  /** How many logical nodes (pairs) the topology has, which the reduced-detail bound is measured in. */
  logicalCount: number;
  /**
   * The boxes in their keyboard columns, left to right and top to bottom within a column: a pair is
   * one column, a split brain's two serving boxes are two, and a reduced-detail grid's columns are its eight.
   */
  columns: string[][];
}

/**
 * The mark vocabulary, exported so the legend and the nodes cannot drift apart:
 * the legend renders these entries, and `kindOf` can only return one of these kinds.
 */
export const NODE_MARKS: ReadonlyArray<{ kind: NodeKind; label: string }> = [
  { kind: 'live', label: 'serving' },
  { kind: 'split', label: 'split brain' },
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

function brokerNode(
  parentId: string,
  x: number,
  y: number,
  endpoint: NodeEndpointView,
  logical: LogicalNodeView,
  offset: boolean,
): Node<BrokerNodeData> {
  const facts = nodeFacts(endpoint, logical);
  return {
    id: endpoint.id,
    type: 'broker',
    parentId,
    extent: 'parent',
    position: { x, y },
    draggable: false,
    connectable: false,
    data: boxOf(facts, offset),
  };
}

/** The four lines of an endpoint's box, from its facts. */
function boxOf(facts: NodeFacts, offset: boolean): BrokerNodeData {
  const error = facts.liveness.kind === 'unreachable' ? facts.lastError : null;
  return {
    name: facts.name,
    version: facts.version,
    versionFlag: facts.versionFlag,
    kind: facts.mark,
    liveness: facts.liveness.label,
    roleLine: `${facts.role} · ${facts.pair}`,
    detail: error ?? facts.address,
    detailIsError: error !== null,
    offset,
    nodeIds: [facts.id],
    sentence: facts.sentence,
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
 * Every endpoint of a pair, in two rows: the serving ones above the axis, the rest below it, each row
 * side by side. A healthy pair is one box above one box, with the replication line between them. A
 * split brain is two serving boxes side by side with no line, because neither is the primary and
 * neither a backup. Whatever else a pair holds (a second serving endpoint that is only suspected, a
 * backup with the primary down) is drawn too: a node the graph does not draw is a node the operator
 * does not know to look at.
 */
function pairChildren(
  logical: LogicalNodeView,
  groupId: string,
  serving: NodeEndpointView[],
  others: NodeEndpointView[],
  axisStatus: AxisStatus,
): { children: Node<BrokerNodeData>[]; edges: Edge[]; columns: string[][] } {
  const above = serving.map((e, i) => brokerNode(groupId, GROUP_PAD + i * BOX_DX, LIVE_Y, e, logical, false));
  const below = others.map((e, i) =>
    brokerNode(groupId, GROUP_PAD + i * BOX_DX, BACKUP_Y, e, logical, logical.replicationBehind),
  );
  const top = above[0];
  const edges: Edge[] =
    axisStatus === 'critical' || !top
      ? []
      : below.map((bottom) => ({
          id: `${top.id}--${bottom.id}`,
          source: top.id,
          target: bottom.id,
          style: {
            stroke: logical.replicationBehind ? 'var(--as-graph-edge-behind)' : 'var(--as-graph-edge)',
            strokeDasharray: logical.replicationBehind ? '6 4' : undefined,
          },
        }));
  // Each box has its column, the serving one above the other, so the keyboard reads the picture.
  const columns = Array.from({ length: Math.max(above.length, below.length) }, (_, i) =>
    [above[i], below[i]].filter((box) => box !== undefined).map((box) => box.id),
  );
  return { children: [...above, ...below], edges, columns };
}

/**
 * One logical node → one group node plus its endpoint children. Returns the
 * group's own width so the caller can pack groups left to right without a
 * wider group (a split brain's, say) overlapping its neighbour.
 */
function layoutLogicalNode(
  logical: LogicalNodeView,
  x: number,
): { nodes: TopologyNode[]; edges: Edge[]; width: number; columns: string[][] } {
  const axisStatus = axisStatusOf(logical);
  const shortId = (logical.artemisNodeId ?? '—').slice(0, 8);
  const groupId = `pair:${logical.artemisNodeId ?? shortId}`;
  const serving = logical.endpoints.filter(isServing);
  const others = logical.endpoints.filter((e) => !isServing(e));

  const { children, edges, columns } = pairChildren(logical, groupId, serving, others, axisStatus);

  const spread = Math.max(0, columns.length - 1) * BOX_DX;
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
  return { nodes: [group, ...children], edges, width, columns };
}

function collapsedKind(axisStatus: AxisStatus, serving: number): NodeKind {
  if (axisStatus === 'critical') return 'split';
  if (axisStatus === 'suspected') return 'down';
  if (axisStatus === 'behind') return 'behind';
  return serving > 0 ? 'live' : 'down';
}

function collapsedStatusWord(axisStatus: AxisStatus, serving: number, standby: number): string {
  if (axisStatus === 'critical') return `split brain — ${serving} serving`;
  if (axisStatus === 'suspected') return 'split brain suspected';
  if (axisStatus === 'behind') return 'replication behind';
  return serving === 0 ? 'nothing serving' : `serving · ${standby} standby`;
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
function collapsedNode(logical: LogicalNodeView, x: number, y: number): Node<BrokerNodeData> {
  const axisStatus = axisStatusOf(logical);
  const serving = logical.endpoints.filter(isServing);
  const others = logical.endpoints.filter((e) => !isServing(e));
  const head = serving[0] ?? others[0] ?? null;
  const shortId = (logical.artemisNodeId ?? '—').slice(0, 8);

  const kind = collapsedKind(axisStatus, serving.length);
  const statusWord = collapsedStatusWord(axisStatus, serving.length, others.length);
  const count = `${logical.endpoints.length} endpoint${logical.endpoints.length === 1 ? '' : 's'}`;

  return {
    id: `collapsed:${logical.artemisNodeId ?? shortId}`,
    type: 'broker',
    position: { x, y },
    draggable: false,
    connectable: false,
    selectable: false,
    data: {
      name: head?.name ?? shortId,
      version: null,
      versionFlag: null,
      kind,
      liveness: statusWord,
      roleLine: count,
      detail: `node ${shortId}`,
      detailIsError: false,
      offset: false,
      // The head first: choosing the box chooses it, and the panel lists the rest.
      nodeIds: [...serving, ...others].map((e) => e.id),
      sentence: `Node ${shortId}: ${statusWord}. ${count[0].toUpperCase()}${count.slice(1)}.`,
    },
  };
}

export function layout(topology: TopologyView, health: HealthView): TopologyLayout {
  const ordered = [...topology.nodes].sort((a, b) => (a.artemisNodeId ?? '').localeCompare(b.artemisNodeId ?? ''));

  const nodes: TopologyNode[] = [];
  const edges: Edge[] = [];
  const columns: string[][] = [];
  const dense = ordered.length > DENSE_THRESHOLD;
  const logicalCount = ordered.length;
  const summary = summarise(topology, health);

  if (dense) {
    // Wrapped into a grid rather than one endless row: at this count the strip is
    // several screens wide and the operator can never see two nodes at once.
    ordered.forEach((logical, i) => {
      const column = i % DENSE_COLUMNS;
      const row = Math.floor(i / DENSE_COLUMNS);
      const box = collapsedNode(logical, column * COL_W, row * DENSE_ROW_H);
      nodes.push(box);
      columns[column] ??= [];
      columns[column].push(box.id);
    });
    return { nodes, edges, summary, dense, logicalCount, columns };
  }

  let x = 0;
  for (const logical of ordered) {
    const part = layoutLogicalNode(logical, x);
    nodes.push(...part.nodes);
    edges.push(...part.edges);
    columns.push(...part.columns);
    x += part.width + GROUP_GAP;
  }

  return { nodes, edges, summary, dense, logicalCount, columns };
}

/** The sentence the roll-up adds to the health level, when a pair is in trouble. */
function rollUpOf(health: HealthView): string {
  if (health.splitBrain === 'CRITICAL') return ' Split-brain confirmed.';
  if (health.splitBrain === 'SUSPECTED') return ' Split-brain suspected.';
  return health.replicationBehind ? ' Replication is not caught up.' : '';
}

function summarise(topology: TopologyView, health: HealthView): string {
  const parts = topology.nodes.map((n) => {
    const id = (n.artemisNodeId ?? 'unknown').slice(0, 8);
    const live = n.endpoints.filter(isServing).map((e) => e.name);
    const standby = n.endpoints.filter((e) => !isServing(e)).map((e) => e.name);
    const standbyNote = standby.length ? `, ${standby.join(', ')} standby` : '';
    return `node ${id}: ${live.join(', ') || 'none'} live${standbyNote}`;
  });
  return `Cluster health ${health.level.toLowerCase()}.${rollUpOf(health)} ${parts.join('; ')}.`;
}
