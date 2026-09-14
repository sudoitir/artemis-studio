import type { Edge, Node } from '@xyflow/react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import type { FlowEdgeView, FlowGraphView, FlowNodeView } from './api.ts';

/**
 * The flow graph's geometry (ADR-0080): four fixed columns laid out by ELK layered, pure so it is
 * tested with the real engine and run unchanged in a web worker.
 */

export const COLUMNS = ['PRODUCER', 'ADDRESS', 'QUEUE', 'CONSUMER'] as const;
export type Column = (typeof COLUMNS)[number];

export const COLUMN_TITLES: Record<Column, string> = {
  PRODUCER: 'Producers',
  ADDRESS: 'Addresses',
  QUEUE: 'Queues',
  CONSUMER: 'Consumers',
};

export const NODE_SIZE: Record<Column, { width: number; height: number }> = {
  PRODUCER: { width: 216, height: 64 },
  ADDRESS: { width: 208, height: 58 },
  QUEUE: { width: 228, height: 78 },
  CONSUMER: { width: 216, height: 64 },
};

/** Above this many nodes the canvas renders only what is on screen and shows a minimap (ADR-0056). */
export const DENSE_NODES = 80;

const LANE_OFFSET = 52;

export type Positions = Record<string, { x: number; y: number }>;

export interface FlowNodeData extends Record<string, unknown> {
  view: FlowNodeView;
  /** 0–1: this queue's backlog against the deepest shown queue; 0 for other kinds. */
  depth: number;
  dimmed: boolean;
}

export interface FlowEdgeData extends Record<string, unknown> {
  view: FlowEdgeView;
  dots: number;
  dimmed: boolean;
}

export interface LaneData extends Record<string, unknown> {
  title: string;
  count: number;
}

function column(node: FlowNodeView): Column {
  return (COLUMNS as readonly string[]).includes(node.kind ?? '') ? (node.kind as Column) : 'QUEUE';
}

/**
 * What positions depend on: which nodes exist and how they connect — never rates. A refresh that
 * changes only rates keeps the signature, so no node moves.
 */
export function layoutSignature(graph: FlowGraphView): string {
  const nodes = (graph.nodes ?? []).map((n) => n.id).sort();
  const edges = (graph.edges ?? []).map((e) => `${e.source}>${e.target}`).sort();
  return `${nodes.join('|')}#${edges.join('|')}`;
}

export function toElkGraph(graph: FlowGraphView): ElkNode {
  const nodes = [...(graph.nodes ?? [])].sort(
    (a, b) =>
      COLUMNS.indexOf(column(a)) - COLUMNS.indexOf(column(b)) || (a.label ?? '').localeCompare(b.label ?? ''),
  );
  const ids = new Set(nodes.map((n) => n.id));
  return {
    id: 'flow',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.partitioning.activate': 'true',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.layered.spacing.nodeNodeBetweenLayers': '132',
      'elk.spacing.nodeNode': '22',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    },
    children: nodes.map((n) => ({
      id: n.id!,
      ...NODE_SIZE[column(n)],
      layoutOptions: { 'elk.partitioning.partition': String(COLUMNS.indexOf(column(n))) },
    })),
    edges: (graph.edges ?? [])
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ id: e.id!, sources: [e.source!], targets: [e.target!] })),
  };
}

export function positionsFrom(laidOut: ElkNode): Positions {
  const out: Positions = {};
  for (const child of laidOut.children ?? []) {
    out[child.id] = { x: child.x ?? 0, y: child.y ?? 0 };
  }
  return out;
}

/** Every node reachable upstream and downstream of `id`: the whole path an emphasis should keep. */
export function pathThrough(graph: FlowGraphView, id: string): Set<string> {
  const out = new Map<string, string[]>();
  const into = new Map<string, string[]>();
  for (const e of graph.edges ?? []) {
    out.set(e.source!, [...(out.get(e.source!) ?? []), e.target!]);
    into.set(e.target!, [...(into.get(e.target!) ?? []), e.source!]);
  }
  const seen = new Set<string>([id]);
  for (const links of [out, into]) {
    const stack = [id];
    while (stack.length) {
      const next = stack.pop()!;
      for (const n of links.get(next) ?? []) {
        if (!seen.has(n)) {
          seen.add(n);
          stack.push(n);
        }
      }
    }
  }
  return seen;
}

/**
 * The React Flow model for a graph at known positions. A node whose position is not known yet — it
 * appeared since the last layout — is left out, with its edges, until the next layout lands.
 */
export function toReactFlow(
  graph: FlowGraphView,
  positions: Positions,
  dots: Map<string, number>,
  emphasis: Set<string> | null,
): { nodes: Node[]; edges: Edge[] } {
  const placed = (graph.nodes ?? []).filter((n) => positions[n.id!]);
  const deepest = Math.max(1, ...placed.map((n) => n.messageCount ?? 0));
  const nodes: Node[] = placed.map((n) => ({
    id: n.id!,
    type: column(n) === 'QUEUE' ? 'queue' : column(n) === 'ADDRESS' ? 'address' : 'client',
    position: positions[n.id!],
    ...NODE_SIZE[column(n)],
    draggable: false,
    connectable: false,
    focusable: false,
    data: {
      view: n,
      depth: column(n) === 'QUEUE' ? Math.min(1, (n.messageCount ?? 0) / deepest) : 0,
      dimmed: emphasis !== null && !emphasis.has(n.id!),
    } satisfies FlowNodeData,
  }));

  for (const col of COLUMNS) {
    const inColumn = placed.filter((n) => column(n) === col);
    if (inColumn.length === 0) continue;
    const xs = inColumn.map((n) => positions[n.id!].x);
    const top = Math.min(...placed.map((n) => positions[n.id!].y));
    nodes.push({
      id: `lane:${col}`,
      type: 'lane',
      position: { x: Math.min(...xs), y: top - LANE_OFFSET },
      width: NODE_SIZE[col].width,
      height: 32,
      draggable: false,
      connectable: false,
      selectable: false,
      focusable: false,
      data: { title: COLUMN_TITLES[col], count: inColumn.length } satisfies LaneData,
    });
  }

  const ids = new Set(placed.map((n) => n.id));
  const edges: Edge[] = (graph.edges ?? [])
    .filter((e) => ids.has(e.source) && ids.has(e.target))
    .map((e) => ({
      id: e.id!,
      source: e.source!,
      target: e.target!,
      type: 'flow',
      focusable: false,
      selectable: false,
      data: {
        view: e,
        dots: dots.get(e.id!) ?? 0,
        dimmed: emphasis !== null && !(emphasis.has(e.source!) && emphasis.has(e.target!)),
      } satisfies FlowEdgeData,
    }));
  return { nodes, edges };
}
