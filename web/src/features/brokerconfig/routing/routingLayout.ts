import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { ElkNode } from 'elkjs/lib/elk-api';

import type { RoutingEdgeView, RoutingGraph, RoutingKind, RoutingNodeView } from './routingGraph.ts';

/**
 * The routing canvas's geometry (ADR-0090), modelled on `features/flow/flowLayout.ts`
 * and pure for the same reason: it is tested against the real engine and runs
 * unchanged in the ELK worker.
 *
 * <p>Unlike the flow graph this one is **not** columnar. A divert's output is
 * another address, so an address can sit on either side of one; partitioning by
 * kind would force a divert's target back into the column its source came from
 * and cross every line in the graph. Layered left-to-right on the real
 * dependencies puts each element after what feeds it, which is the question the
 * canvas exists to answer.
 */

export const NODE_SIZE: Record<RoutingKind, { width: number; height: number }> = {
  address: { width: 212, height: 62 },
  queue: { width: 212, height: 62 },
  divert: { width: 196, height: 72 },
  bridge: { width: 208, height: 78 },
  target: { width: 212, height: 66 },
};

export type Positions = Record<string, { x: number; y: number }>;

export interface RoutingNodeData extends Record<string, unknown> {
  view: RoutingNodeView;
  selected: boolean;
}

export interface RoutingEdgeData extends Record<string, unknown> {
  view: RoutingEdgeView;
}

/**
 * What positions depend on: which elements exist and how they connect. A drift
 * evaluation that changes only an element's state keeps the signature, so nothing
 * moves under the operator's cursor.
 */
export function layoutSignature(graph: RoutingGraph): string {
  const nodes = graph.nodes.map((n) => n.id).sort();
  const edges = graph.edges.map((e) => `${e.source}>${e.target}`).sort();
  return `${nodes.join('|')}#${edges.join('|')}`;
}

export function toElkGraph(graph: RoutingGraph): ElkNode {
  const ids = new Set(graph.nodes.map((n) => n.id));
  return {
    id: 'routing',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.layered.spacing.nodeNodeBetweenLayers': '96',
      'elk.spacing.nodeNode': '24',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
    },
    children: [...graph.nodes]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((n) => ({ id: n.id, ...NODE_SIZE[n.kind] })),
    edges: graph.edges
      .filter((e) => ids.has(e.source) && ids.has(e.target))
      .map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
}

export function positionsFrom(laidOut: ElkNode): Positions {
  const out: Positions = {};
  for (const child of laidOut.children ?? []) out[child.id] = { x: child.x ?? 0, y: child.y ?? 0 };
  return out;
}

/**
 * The React Flow model at known positions. An element whose position is not known
 * yet — it appeared since the last layout — is left out, with its lines, until the
 * next layout lands.
 */
export function toReactFlow(
  graph: RoutingGraph,
  positions: Positions,
  selectedId: string | null,
): { nodes: Node[]; edges: Edge[] } {
  const placed = graph.nodes.filter((n) => positions[n.id]);
  const nodes: Node[] = placed.map((n) => ({
    id: n.id,
    type: n.kind,
    position: positions[n.id],
    ...NODE_SIZE[n.kind],
    draggable: false,
    // React Flow's own focus and selection would compete with the roving tabstop
    // the canvas owns; the element inside each node is what takes focus.
    focusable: false,
    selectable: false,
    data: { view: n, selected: n.id === selectedId } satisfies RoutingNodeData,
  }));
  const ids = new Set(placed.map((n) => n.id));
  const edges: Edge[] = graph.edges
    .filter((e) => ids.has(e.source) && ids.has(e.target))
    .map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      type: 'routing',
      // A presentation attribute takes a custom property, so the arrowhead follows
      // the theme with the line rather than being painted a literal colour.
      markerEnd: { type: MarkerType.ArrowClosed, color: 'var(--as-flow-edge)', width: 14, height: 14 },
      focusable: false,
      selectable: false,
      data: { view: e } satisfies RoutingEdgeData,
    }));
  return { nodes, edges };
}
