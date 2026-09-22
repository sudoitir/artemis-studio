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

/** One card size for every kind: kind is carried by the glyph and the word, not by the outline. */
const CARD = { width: 224, height: 76 };

export const NODE_SIZE: Record<RoutingKind, { width: number; height: number }> = {
  address: CARD,
  queue: CARD,
  divert: CARD,
  bridge: CARD,
  target: CARD,
};

export type Positions = Record<string, { x: number; y: number }>;

export interface RoutingNodeData extends Record<string, unknown> {
  view: RoutingNodeView;
  selected: boolean;
}

export interface RoutingEdgeData extends Record<string, unknown> {
  view: RoutingEdgeView;
  /**
   * With an element selected, its own lines are brought forward and the rest recede; with
   * nothing selected every line is drawn alike.
   */
  emphasis?: 'forward' | 'recede';
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
      'elk.layered.spacing.nodeNodeBetweenLayers': '112',
      'elk.spacing.nodeNode': '28',
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
    .map((e) => {
      const emphasis: RoutingEdgeData['emphasis'] = !selectedId
        ? undefined
        : e.source === selectedId || e.target === selectedId
          ? 'forward'
          : 'recede';
      return {
        id: e.id,
        source: e.source,
        target: e.target,
        type: 'routing',
        // A presentation attribute takes a custom property, so the arrowhead follows
        // the theme with the line rather than being painted a literal colour.
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: emphasis === 'forward' ? 'var(--as-text)' : 'var(--as-flow-edge)',
          width: 16,
          height: 16,
        },
        focusable: false,
        selectable: false,
        data: { view: e, emphasis } satisfies RoutingEdgeData,
      };
    });
  return { nodes, edges };
}

export type Direction = 'left' | 'right' | 'up' | 'down';

const centre = (n: Node) => ({ x: n.position.x + (n.width ?? 0) / 2, y: n.position.y + (n.height ?? 0) / 2 });

/**
 * The element an arrow key moves to from `fromId`: the nearest one whose centre lies in that
 * direction, with distance off the line weighted double so the element in line wins over a nearer
 * one off to the side. Null at the edge — the canvas stays put rather than wrapping, so an arrow key
 * never jumps somewhere the operator cannot see coming.
 */
export function neighbour(nodes: Node[], fromId: string, direction: Direction): string | null {
  const from = nodes.find((n) => n.id === fromId);
  if (!from) return null;
  const origin = centre(from);
  let best: string | null = null;
  let bestScore = Infinity;
  for (const n of nodes) {
    if (n.id === fromId) continue;
    const at = centre(n);
    const dx = at.x - origin.x;
    const dy = at.y - origin.y;
    const [along, across] =
      direction === 'right' ? [dx, dy] : direction === 'left' ? [-dx, dy] : direction === 'down' ? [dy, dx] : [-dy, dx];
    // Strictly ahead: an element level with this one is not "to its right" by a rounding error.
    if (along <= 1) continue;
    const score = along + 2 * Math.abs(across);
    if (score < bestScore) {
      bestScore = score;
      best = n.id;
    }
  }
  return best;
}

/** Left to right, then down: the order Tab-like entry lands in, by row first. */
export function readingOrder(nodes: Node[]): string[] {
  return [...nodes].sort((a, b) => a.position.y - b.position.y || a.position.x - b.position.x).map((n) => n.id);
}
