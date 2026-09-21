import type { ConfigDeclarationView, ConfigDriftFindingView } from '../api.ts';
import type { Section, WireSection } from '../words.ts';

/**
 * The routing graph, as data (ADR-0090 D8). Pure: a declaration and the stored
 * per-node evaluation in, nodes and edges out, so what the canvas draws is
 * testable without a canvas.
 *
 * <p>Every element carries one of the states below **in words**. None of them is
 * a claim about where an element came from: Artemis records nothing that would
 * support one, and ADR-0065 D2 withdrew that vocabulary. "Observed and not
 * declared" is a statement about Studio's declaration, not about the broker's
 * history.
 */

export type RoutingKind = 'address' | 'queue' | 'divert' | 'bridge' | 'target';

export type ElementState =
  /** Declared here and seen on the nodes that were evaluated. */
  | 'BOTH'
  /** Declared here and missing from at least one evaluated node. */
  | 'DECLARED_ONLY'
  /** Seen on the nodes and absent from the declaration. */
  | 'OBSERVED_ONLY'
  /** Declared here, with no evaluated live node to compare against. */
  | 'UNEVALUATED'
  /** Named by something declared, but not itself a declared item. */
  | 'REFERENCED';

/**
 * The sentence each state reads as. The first three are the three the routing
 * spec requires; the last two exist because claiming one of the three when
 * nothing has been evaluated, or for an address only named in passing, would be
 * a claim Studio has not checked.
 */
export const STATE_WORDS: Record<ElementState, string> = {
  BOTH: 'declared and observed on the brokers',
  DECLARED_ONLY: 'declared, not yet applied',
  OBSERVED_ONLY: 'observed on the brokers, not declared',
  UNEVALUATED: 'declared; no live node has been evaluated yet',
  REFERENCED: 'named by a declared element; not itself declared',
};

/** The same five states, short enough for a node on the canvas. Still words, never colour alone. */
export const STATE_SHORT: Record<ElementState, string> = {
  BOTH: 'declared and observed',
  DECLARED_ONLY: 'declared, not applied',
  OBSERVED_ONLY: 'observed, not declared',
  UNEVALUATED: 'declared, not evaluated',
  REFERENCED: 'not declared',
};

export const KIND_WORDS: Record<RoutingKind, string> = {
  address: 'Address',
  queue: 'Queue',
  divert: 'Divert',
  bridge: 'Bridge',
  target: 'Target on another broker',
};

/**
 * What a drag from one element to another composes, or null where the pair composes nothing.
 * The canvas proposes exactly two things: a divert between two addresses, and a bridge from a
 * queue to a target on another broker. Everything else is a drag that goes nowhere, so the
 * canvas neither offers it as a start nor accepts it as an end.
 */
export function composes(from: RoutingKind, to: RoutingKind): 'divert' | 'bridge' | null {
  if (from === 'address' && to === 'address') return 'divert';
  if (from === 'queue' && to === 'target') return 'bridge';
  return null;
}

/** Kinds a drag can start from, and kinds it can end on — derived from {@link composes}. */
export const STARTS: ReadonlySet<RoutingKind> = new Set(['address', 'queue']);
export const ENDS: ReadonlySet<RoutingKind> = new Set(['address', 'target']);

export interface RoutingNodeView {
  /** `kind:key` — stable, so it survives a refresh and can live in the URL. */
  id: string;
  kind: RoutingKind;
  name: string;
  state: ElementState;
  /** What this element connects, in words: the second half of its accessible name. */
  connects: string;
  /** Observed runtime state that is a fault rather than drift, in words; null when there is none. */
  fault: string | null;
  /** The declaration editor this element opens, when it is one Studio declares. */
  edit: { section: Section; item: string } | null;
}

export interface RoutingEdgeView {
  id: string;
  source: string;
  target: string;
  /** What the line means, for the edge's accessible name. */
  label: string;
  /** A word drawn on the line itself — a divert's "copies" or "takes" — when the line carries one. */
  chip?: string;
}

export interface RoutingGraph {
  nodes: RoutingNodeView[];
  edges: RoutingEdgeView[];
}

/** Above this many elements the canvas draws a region and says so (ADR-0056, operator-ui). */
export const GRAPH_BOUND = 150;

export const addressId = (name: string) => `address:${name}`;
export const queueId = (name: string) => `queue:${name}`;

function evaluatedNodes(declaration: ConfigDeclarationView) {
  return declaration.nodes.filter((n) => n.live && (n.state === 'IN_SYNC' || n.state === 'DRIFTED'));
}

/** Whether a finding is about one item of one section. */
function about(f: ConfigDriftFindingView, wire: WireSection, key: string): boolean {
  return f.section === wire && f.key === key;
}

/**
 * Which of the states a declared item is in, and the fault its nodes report.
 *
 * <p>A bridge that matches the declaration and is not forwarding is not drift —
 * an apply would write nothing that could close it — so it is carried separately
 * and never turns the element's state into "not applied" (ADR-0091).
 */
function declaredState(
  declaration: ConfigDeclarationView,
  wire: WireSection,
  key: string,
): { state: ElementState; fault: string | null } {
  const evaluated = evaluatedNodes(declaration);
  const faults = evaluated
    .filter((n) => n.findings.some((f) => about(f, wire, key) && f.kind === 'NOT_CONNECTED'))
    .map((n) => n.nodeName);
  const fault = faults.length ? `not forwarding on ${faults.join(', ')}` : null;
  if (evaluated.length === 0) return { state: 'UNEVALUATED', fault };
  const missing = evaluated
    .filter((n) => n.findings.some((f) => about(f, wire, key) && f.kind === 'MISSING'))
    .map((n) => n.nodeName);
  return missing.length
    ? { state: 'DECLARED_ONLY', fault }
    : { state: 'BOTH', fault };
}

/** Every UNDECLARED finding across the live nodes, deduplicated by section and key. */
function undeclared(declaration: ConfigDeclarationView): ConfigDriftFindingView[] {
  const seen = new Set<string>();
  const out: ConfigDriftFindingView[] = [];
  for (const node of evaluatedNodes(declaration)) {
    for (const f of node.findings) {
      if (f.kind !== 'UNDECLARED' || !f.key) continue;
      if (seen.add(`${f.section}:${f.key}`)) out.push(f);
    }
  }
  return out;
}

function text(map: Record<string, unknown> | undefined, key: string): string | null {
  const v = map?.[key];
  return typeof v === 'string' && v ? v : null;
}

/** Where a bridge forwards to, as one target: the address, on whatever it connects over. */
export function targetLabel(forwardingAddress: string, over: string | null): string {
  return over ? `${forwardingAddress} over ${over}` : forwardingAddress;
}

/**
 * The declaration and what the nodes report, as one graph: addresses, the queues
 * bound to them, the diverts between addresses, the bridges out of queues, and
 * the targets those bridges reach.
 */
export function buildRoutingGraph(declaration: ConfigDeclarationView): RoutingGraph {
  const nodes = new Map<string, RoutingNodeView>();
  const edges: RoutingEdgeView[] = [];
  const doc = declaration.document;

  /** An address or queue named by something else, added only if nothing declared it first. */
  const referenced = (kind: 'address' | 'queue', name: string): string => {
    const id = `${kind}:${name}`;
    if (!nodes.has(id)) {
      nodes.set(id, {
        id,
        kind,
        name,
        state: 'REFERENCED',
        connects: `not declared, and no node has been asked about it`,
        fault: null,
        edit: null,
      });
    }
    return id;
  };

  for (const a of doc.addresses) {
    const { state, fault } = declaredState(declaration, 'ADDRESS', a.name);
    nodes.set(addressId(a.name), {
      id: addressId(a.name),
      kind: 'address',
      name: a.name,
      state,
      connects: a.queues.length
        ? `routes ${a.routingTypes.join(' and ')} to ${a.queues.length} queue${a.queues.length === 1 ? '' : 's'}`
        : `routes ${a.routingTypes.join(' and ')}, with no queue declared on it`,
      fault,
      edit: { section: 'addresses', item: a.name },
    });
    for (const q of a.queues) {
      const queue = declaredState(declaration, 'QUEUE', q.name);
      nodes.set(queueId(q.name), {
        id: queueId(q.name),
        kind: 'queue',
        name: q.name,
        state: queue.state,
        connects: `bound to address ${a.name}, ${q.routingType.toLowerCase()}`,
        fault: queue.fault,
        edit: { section: 'addresses', item: a.name },
      });
      edges.push({
        id: `bind:${a.name}:${q.name}`,
        source: addressId(a.name),
        target: queueId(q.name),
        label: `address ${a.name} routes to queue ${q.name}`,
      });
    }
  }

  for (const d of doc.diverts) {
    const { state, fault } = declaredState(declaration, 'DIVERT', d.name);
    const id = `divert:${d.name}`;
    nodes.set(id, {
      id,
      kind: 'divert',
      name: d.name,
      state,
      connects: `${d.exclusive ? 'takes' : 'copies'} messages from address ${d.address} to address ${d.forwardingAddress}`,
      fault,
      edit: { section: 'diverts', item: d.name },
    });
    const from = nodes.has(addressId(d.address)) ? addressId(d.address) : referenced('address', d.address);
    const to = nodes.has(addressId(d.forwardingAddress))
      ? addressId(d.forwardingAddress)
      : referenced('address', d.forwardingAddress);
    edges.push({ id: `divert-in:${d.name}`, source: from, target: id, label: `address ${d.address} feeds divert ${d.name}` });
    edges.push({
      id: `divert-out:${d.name}`,
      source: id,
      target: to,
      label: `divert ${d.name} ${d.exclusive ? 'takes' : 'copies'} messages to address ${d.forwardingAddress}`,
      chip: d.exclusive ? 'takes' : 'copies',
    });
  }

  for (const b of doc.bridges) {
    const { state, fault } = declaredState(declaration, 'BRIDGE', b.name);
    const id = `bridge:${b.name}`;
    const over = b.staticConnectors.length ? b.staticConnectors.join(', ') : (b.discoveryGroupName ?? null);
    nodes.set(id, {
      id,
      kind: 'bridge',
      name: b.name,
      state,
      connects: `forwards queue ${b.queueName} to address ${b.forwardingAddress}${over ? ` over ${over}` : ''}`,
      fault,
      edit: { section: 'bridges', item: b.name },
    });
    const from = nodes.has(queueId(b.queueName)) ? queueId(b.queueName) : referenced('queue', b.queueName);
    const label = targetLabel(b.forwardingAddress, over);
    const targetNodeId = `target:${label}`;
    if (!nodes.has(targetNodeId)) {
      nodes.set(targetNodeId, {
        id: targetNodeId,
        // The name is the address, so a bridge composed onto this target gets the
        // address and not the sentence describing how it is reached.
        kind: 'target',
        name: b.forwardingAddress,
        state: 'REFERENCED',
        connects: `on another broker${over ? `, reached over ${over}` : ''}; Studio does not read it from here`,
        fault: null,
        edit: null,
      });
    }
    edges.push({ id: `bridge-in:${b.name}`, source: from, target: id, label: `queue ${b.queueName} feeds bridge ${b.name}` });
    edges.push({
      id: `bridge-out:${b.name}`,
      source: id,
      target: targetNodeId,
      label: `bridge ${b.name} forwards to ${label}`,
    });
  }

  for (const f of undeclared(declaration)) {
    const key = f.key!;
    if (f.section === 'DIVERT' && !nodes.has(`divert:${key}`)) {
      const id = `divert:${key}`;
      const from = text(f.observed, 'address');
      const to = text(f.observed, 'forwarding-address');
      nodes.set(id, {
        id,
        kind: 'divert',
        name: key,
        state: 'OBSERVED_ONLY',
        connects:
          from && to ? `moves messages from address ${from} to address ${to}` : 'the nodes did not report what it connects',
        fault: null,
        edit: null,
      });
      if (from) edges.push({ id: `divert-in:${key}`, source: referenced('address', from), target: id, label: `address ${from} feeds divert ${key}` });
      if (to) edges.push({ id: `divert-out:${key}`, source: id, target: referenced('address', to), label: `divert ${key} forwards to address ${to}` });
    } else if (f.section === 'BRIDGE' && !nodes.has(`bridge:${key}`)) {
      const id = `bridge:${key}`;
      const from = text(f.observed, 'queue-name');
      const to = text(f.observed, 'forwarding-address');
      nodes.set(id, {
        id,
        kind: 'bridge',
        name: key,
        state: 'OBSERVED_ONLY',
        connects:
          from && to ? `forwards queue ${from} to address ${to}` : 'the nodes did not report what it connects',
        fault: null,
        edit: null,
      });
      if (from) edges.push({ id: `bridge-in:${key}`, source: referenced('queue', from), target: id, label: `queue ${from} feeds bridge ${key}` });
      if (to) {
        const targetNodeId = `target:${to}`;
        if (!nodes.has(targetNodeId)) {
          nodes.set(targetNodeId, {
            id: targetNodeId,
            kind: 'target',
            name: to,
            state: 'REFERENCED',
            connects: 'on another broker; Studio does not read it from here',
            fault: null,
            edit: null,
          });
        }
        edges.push({ id: `bridge-out:${key}`, source: id, target: targetNodeId, label: `bridge ${key} forwards to ${to}` });
      }
    }
  }

  const ids = new Set(nodes.keys());
  return { nodes: [...nodes.values()], edges: edges.filter((e) => ids.has(e.source) && ids.has(e.target)) };
}

/** The sentence a screen reader hears for an element: what it is, what it connects, and its state. */
export function nodeSentence(node: RoutingNodeView): string {
  const parts = [`${KIND_WORDS[node.kind]} ${node.name}`, node.connects, STATE_WORDS[node.state]];
  if (node.fault) parts.push(node.fault);
  return `${parts.join('. ')}.`;
}

/** The addresses a bounded view can be anchored on, in the order they read best. */
export function anchorCandidates(graph: RoutingGraph): RoutingNodeView[] {
  return graph.nodes.filter((n) => n.kind === 'address').sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * The region around `anchorId`: everything within reach, breadth first, up to
 * `limit` elements. Breadth first because the elements nearest the address the
 * operator chose are the ones they came to see; a depth-first walk would spend
 * the budget on one long chain.
 */
export function regionAround(
  graph: RoutingGraph,
  anchorId: string,
  limit: number = GRAPH_BOUND,
): { graph: RoutingGraph; hidden: number } {
  if (graph.nodes.length <= limit) return { graph, hidden: 0 };
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const anchor = byId.has(anchorId) ? anchorId : (anchorCandidates(graph)[0]?.id ?? graph.nodes[0]?.id);
  if (!anchor) return { graph, hidden: 0 };

  const neighbours = new Map<string, string[]>();
  for (const e of graph.edges) {
    neighbours.set(e.source, [...(neighbours.get(e.source) ?? []), e.target]);
    neighbours.set(e.target, [...(neighbours.get(e.target) ?? []), e.source]);
  }
  const kept = new Set<string>([anchor]);
  const queue = [anchor];
  while (queue.length && kept.size < limit) {
    const next = queue.shift()!;
    for (const n of neighbours.get(next) ?? []) {
      if (kept.size >= limit) break;
      if (!kept.has(n)) {
        kept.add(n);
        queue.push(n);
      }
    }
  }
  return {
    graph: {
      nodes: graph.nodes.filter((n) => kept.has(n.id)),
      edges: graph.edges.filter((e) => kept.has(e.source) && kept.has(e.target)),
    },
    hidden: graph.nodes.length - kept.size,
  };
}
