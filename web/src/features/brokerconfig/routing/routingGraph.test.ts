import { describe, expect, it } from 'vitest';

import type { ConfigDeclarationView, ConfigDriftFindingView, ConfigNodeStateView } from '../api.ts';
import {
  addressId,
  anchorCandidates,
  buildRoutingGraph,
  composes,
  ENDS,
  nodeSentence,
  queueId,
  regionAround,
  STARTS,
  targetLabel,
  type RoutingGraph,
  type RoutingKind,
} from './routingGraph.ts';

const KINDS: RoutingKind[] = ['address', 'queue', 'divert', 'bridge', 'target'];

describe('composes', () => {
  it('proposes a divert between two addresses and a bridge from a queue to a target, and nothing else', () => {
    const pairs = KINDS.flatMap((from) => KINDS.map((to) => [from, to, composes(from, to)] as const)).filter(
      ([, , what]) => what !== null,
    );
    expect(pairs).toEqual([
      ['address', 'address', 'divert'],
      ['queue', 'target', 'bridge'],
    ]);
  });

  it('offers a start or an end only on a kind that takes part in some composition', () => {
    for (const kind of KINDS) {
      expect(STARTS.has(kind)).toBe(KINDS.some((to) => composes(kind, to) !== null));
      expect(ENDS.has(kind)).toBe(KINDS.some((from) => composes(from, kind) !== null));
    }
  });
});

type Doc = Partial<ConfigDeclarationView['document']>;

const finding = (over: Partial<ConfigDriftFindingView>): ConfigDriftFindingView => ({
  kind: 'MISSING',
  section: 'ADDRESS',
  key: 'orders',
  detail: '',
  declared: {},
  observed: {},
  ...over,
});

const node = (over: Partial<ConfigNodeStateView> = {}): ConfigNodeStateView => ({
  nodeId: '00000000-0000-0000-0000-000000000001',
  nodeName: 'n1',
  live: true,
  state: 'IN_SYNC',
  findings: [],
  ...over,
});

const declaration = (document: Doc, nodes: ConfigNodeStateView[] = [node()]): ConfigDeclarationView =>
  ({
    declared: true,
    revision: 1,
    document: {
      version: 1,
      addresses: [],
      addressSettings: [],
      securitySettings: [],
      diverts: [],
      bridges: [],
      ...document,
    },
    nodes,
  }) as unknown as ConfigDeclarationView;

const address = (name: string, queues: string[] = [], routingTypes: ('ANYCAST' | 'MULTICAST')[] = ['ANYCAST']) => ({
  name,
  routingTypes,
  queues: queues.map((q) => ({ name: q, routingType: 'ANYCAST' as const, durable: true })),
});

const divert = (name: string, from: string, to: string, exclusive = false) => ({
  name,
  address: from,
  forwardingAddress: to,
  exclusive,
  transformerProperties: {},
});

const bridge = (
  name: string,
  queueName: string,
  over: Partial<{ staticConnectors: string[]; discoveryGroupName: string | null }> = {},
) => ({
  name,
  queueName,
  forwardingAddress: 'remote.orders',
  staticConnectors: [],
  ...over,
});

const byId = (g: RoutingGraph, id: string) => g.nodes.find((n) => n.id === id)!;

describe('buildRoutingGraph: addresses and queues', () => {
  it('draws an address with its queues, bound by an edge, in words', () => {
    const g = buildRoutingGraph(
      declaration({ addresses: [address('orders', ['orders.q1', 'orders.q2'], ['ANYCAST', 'MULTICAST'])] }),
    );
    expect(byId(g, addressId('orders'))).toMatchObject({
      kind: 'address',
      state: 'BOTH',
      connects: 'routes ANYCAST and MULTICAST to 2 queues',
      edit: { section: 'addresses', item: 'orders' },
    });
    expect(byId(g, queueId('orders.q1'))).toMatchObject({
      kind: 'queue',
      connects: 'bound to address orders, anycast',
      edit: { section: 'addresses', item: 'orders' },
    });
    expect(g.edges.map((e) => e.label)).toEqual([
      'address orders routes to queue orders.q1',
      'address orders routes to queue orders.q2',
    ]);
  });

  it('reads one queue in the singular and none as unbound', () => {
    const g = buildRoutingGraph(declaration({ addresses: [address('a', ['q']), address('b')] }));
    expect(byId(g, addressId('a')).connects).toBe('routes ANYCAST to 1 queue');
    expect(byId(g, addressId('b')).connects).toBe('routes ANYCAST, with no queue declared on it');
  });

  it('is unevaluated until a live node has been evaluated', () => {
    const doc = { addresses: [address('orders')] };
    expect(byId(buildRoutingGraph(declaration(doc, [])), addressId('orders')).state).toBe('UNEVALUATED');
    expect(byId(buildRoutingGraph(declaration(doc, [node({ live: false })])), addressId('orders')).state).toBe(
      'UNEVALUATED',
    );
    expect(byId(buildRoutingGraph(declaration(doc, [node({ state: 'UNREACHABLE' })])), addressId('orders')).state).toBe(
      'UNEVALUATED',
    );
  });

  it('is declared-only when an evaluated node misses it, and each item is judged on its own finding', () => {
    const d = declaration({ addresses: [address('orders', ['orders.q'])] }, [
      node({ state: 'DRIFTED', findings: [finding({ section: 'QUEUE', key: 'orders.q' })] }),
    ]);
    const g = buildRoutingGraph(d);
    expect(byId(g, addressId('orders')).state).toBe('BOTH');
    expect(byId(g, queueId('orders.q')).state).toBe('DECLARED_ONLY');
  });
});

describe('buildRoutingGraph: diverts', () => {
  it('draws a copying and a taking divert between the addresses it names', () => {
    const g = buildRoutingGraph(
      declaration({
        addresses: [address('a'), address('b')],
        diverts: [divert('copy', 'a', 'b'), divert('take', 'a', 'b', true)],
      }),
    );
    expect(byId(g, 'divert:copy').connects).toBe('copies messages from address a to address b');
    expect(byId(g, 'divert:take').connects).toBe('takes messages from address a to address b');
    const out = g.edges.find((e) => e.id === 'divert-out:take')!;
    expect(out).toMatchObject({ source: 'divert:take', target: addressId('b'), chip: 'takes' });
    expect(g.edges.find((e) => e.id === 'divert-out:copy')!.chip).toBe('copies');
    expect(g.edges.find((e) => e.id === 'divert-in:copy')).toMatchObject({
      source: addressId('a'),
      target: 'divert:copy',
    });
  });

  it('marks an address named only by a divert as referenced', () => {
    const g = buildRoutingGraph(declaration({ diverts: [divert('d', 'ghost.in', 'ghost.out')] }));
    expect(byId(g, addressId('ghost.in'))).toMatchObject({ state: 'REFERENCED', edit: null });
    expect(byId(g, addressId('ghost.out')).state).toBe('REFERENCED');
    expect(g.edges).toHaveLength(2);
  });
});

describe('buildRoutingGraph: bridges', () => {
  it('draws a bridge from its queue to a target reached over its static connectors', () => {
    const g = buildRoutingGraph(
      declaration({
        addresses: [address('orders', ['orders.q'])],
        bridges: [bridge('br', 'orders.q', { staticConnectors: ['dc2', 'dc3'] })],
      }),
    );
    expect(byId(g, 'bridge:br')).toMatchObject({
      kind: 'bridge',
      connects: 'forwards queue orders.q to address remote.orders over dc2, dc3',
      edit: { section: 'bridges', item: 'br' },
    });
    expect(byId(g, 'target:remote.orders over dc2, dc3')).toMatchObject({
      kind: 'target',
      name: 'remote.orders',
      state: 'REFERENCED',
      connects: 'on another broker, reached over dc2, dc3; Studio does not read it from here',
    });
    expect(g.edges.find((e) => e.id === 'bridge-out:br')!.label).toBe(
      'bridge br forwards to remote.orders over dc2, dc3',
    );
  });

  it('falls back to the discovery group, then to no route, and shares a target between bridges', () => {
    const g = buildRoutingGraph(
      declaration({
        bridges: [bridge('a', 'q1', { discoveryGroupName: 'grp' }), bridge('b', 'q2'), bridge('c', 'q3')],
      }),
    );
    expect(byId(g, 'bridge:a').connects).toMatch(/ over grp$/);
    expect(byId(g, 'bridge:b').connects).toBe('forwards queue q2 to address remote.orders');
    expect(byId(g, 'target:remote.orders').connects).toBe('on another broker; Studio does not read it from here');
    expect(g.nodes.filter((n) => n.kind === 'target').map((n) => n.id)).toEqual([
      'target:remote.orders over grp',
      'target:remote.orders',
    ]);
    expect(byId(g, queueId('q1')).state).toBe('REFERENCED');
  });

  it('carries a bridge that matches but is not forwarding as a fault, not as drift', () => {
    const notConnected = finding({ section: 'BRIDGE', key: 'br', kind: 'NOT_CONNECTED' });
    const g = buildRoutingGraph(
      declaration({ bridges: [bridge('br', 'q')] }, [
        node({ nodeName: 'n1', findings: [notConnected] }),
        node({ nodeName: 'n2', findings: [notConnected] }),
        node({ nodeName: 'n3' }),
      ]),
    );
    expect(byId(g, 'bridge:br')).toMatchObject({ state: 'BOTH', fault: 'not forwarding on n1, n2' });
    expect(nodeSentence(byId(g, 'bridge:br'))).toMatch(
      /declared and observed on the brokers\. not forwarding on n1, n2\.$/,
    );
  });
});

describe('buildRoutingGraph: observed and not declared', () => {
  const undeclared = (section: string, key: string, observed: Record<string, unknown>) =>
    node({ findings: [finding({ kind: 'UNDECLARED', section, key, observed })] });

  it('draws an observed divert as far as the nodes reported it', () => {
    const g = buildRoutingGraph(
      declaration({}, [undeclared('DIVERT', 'stray', { address: 'in', 'forwarding-address': 'out' })]),
    );
    expect(byId(g, 'divert:stray')).toMatchObject({
      state: 'OBSERVED_ONLY',
      connects: 'moves messages from address in to address out',
      edit: null,
    });
    expect(g.edges.map((e) => e.id).sort()).toEqual(['divert-in:stray', 'divert-out:stray']);
    expect(byId(g, addressId('in')).state).toBe('REFERENCED');
  });

  it('says so when an observed divert or bridge reports nothing about what it connects', () => {
    const g = buildRoutingGraph(declaration({}, [undeclared('DIVERT', 'd', {}), undeclared('BRIDGE', 'b', {})]));
    expect(byId(g, 'divert:d').connects).toBe('the nodes did not report what it connects');
    expect(byId(g, 'bridge:b').connects).toBe('the nodes did not report what it connects');
    expect(g.edges).toEqual([]);
  });

  it('draws an observed bridge with its queue and target, reusing a target already drawn', () => {
    const g = buildRoutingGraph(
      declaration({}, [
        undeclared('BRIDGE', 'b1', { 'queue-name': 'q', 'forwarding-address': 'remote' }),
        undeclared('BRIDGE', 'b2', { 'queue-name': 'q', 'forwarding-address': 'remote' }),
      ]),
    );
    expect(byId(g, 'bridge:b1')).toMatchObject({
      state: 'OBSERVED_ONLY',
      connects: 'forwards queue q to address remote',
    });
    expect(g.nodes.filter((n) => n.kind === 'target')).toHaveLength(1);
    expect(g.edges.find((e) => e.id === 'bridge-in:b1')).toMatchObject({ source: queueId('q'), target: 'bridge:b1' });
    expect(g.edges.find((e) => e.id === 'bridge-out:b2')!.target).toBe('target:remote');
  });

  it('ignores an undeclared item the declaration already has, one without a key, and other sections', () => {
    const g = buildRoutingGraph(
      declaration({ diverts: [divert('d', 'a', 'b')], bridges: [bridge('br', 'q')] }, [
        node({
          findings: [
            finding({ kind: 'UNDECLARED', section: 'DIVERT', key: 'd' }),
            finding({ kind: 'UNDECLARED', section: 'BRIDGE', key: 'br' }),
            finding({ kind: 'UNDECLARED', section: 'DIVERT', key: null }),
            finding({ kind: 'UNDECLARED', section: 'ADDRESS', key: 'x' }),
          ],
        }),
      ]),
    );
    expect(byId(g, 'divert:d').state).toBe('BOTH');
    expect(g.nodes.map((n) => n.id).sort()).toEqual(
      ['address:a', 'address:b', 'bridge:br', 'divert:d', 'queue:q', 'target:remote.orders'].sort(),
    );
  });

  it('counts an undeclared item once however many nodes report it', () => {
    const one = undeclared('DIVERT', 'stray', { address: 'in', 'forwarding-address': 'out' });
    const g = buildRoutingGraph(declaration({}, [one, { ...one, nodeName: 'n2' }]));
    expect(g.nodes.filter((n) => n.kind === 'divert')).toHaveLength(1);
  });
});

describe('targetLabel and nodeSentence', () => {
  it('names a target with or without the route it is reached over', () => {
    expect(targetLabel('x', 'dc2')).toBe('x over dc2');
    expect(targetLabel('x', null)).toBe('x');
  });

  it('reads a node as what it is, what it connects, and its state', () => {
    const g = buildRoutingGraph(declaration({ addresses: [address('orders')] }, []));
    expect(nodeSentence(byId(g, addressId('orders')))).toBe(
      'Address orders. routes ANYCAST, with no queue declared on it. declared; no live node has been evaluated yet.',
    );
  });
});

describe('anchorCandidates and regionAround', () => {
  const chain = (n: number): RoutingGraph => ({
    nodes: Array.from({ length: n }, (_, i) => ({
      id: `address:a${String(i).padStart(2, '0')}`,
      kind: 'address' as const,
      name: `a${String(i).padStart(2, '0')}`,
      state: 'BOTH' as const,
      connects: '',
      fault: null,
      edit: null,
    })),
    edges: Array.from({ length: n - 1 }, (_, i) => ({
      id: `e${i}`,
      source: `address:a${String(i).padStart(2, '0')}`,
      target: `address:a${String(i + 1).padStart(2, '0')}`,
      label: '',
    })),
  });

  it('offers only addresses as anchors, alphabetically', () => {
    const g = buildRoutingGraph(declaration({ addresses: [address('b', ['q']), address('a')] }));
    expect(anchorCandidates(g).map((n) => n.name)).toEqual(['a', 'b']);
  });

  it('returns the whole graph within the limit', () => {
    const g = chain(5);
    expect(regionAround(g, 'address:a00', 5)).toEqual({ graph: g, hidden: 0 });
  });

  it('keeps the elements nearest the anchor, breadth first, and counts the rest as hidden', () => {
    const { graph, hidden } = regionAround(chain(10), 'address:a05', 4);
    expect(graph.nodes.map((n) => n.name)).toEqual(['a03', 'a04', 'a05', 'a06']);
    expect(graph.edges).toHaveLength(3);
    expect(hidden).toBe(6);
  });

  it('anchors on the first address when the anchor is unknown, and on the first node when there is no address', () => {
    expect(regionAround(chain(10), 'address:nope', 3).graph.nodes.map((n) => n.name)).toEqual(['a00', 'a01', 'a02']);
    const noAddress: RoutingGraph = {
      nodes: chain(3).nodes.map((n) => ({ ...n, kind: 'queue' as const })),
      edges: [],
    };
    expect(regionAround(noAddress, 'x', 2)).toEqual({ graph: { nodes: [noAddress.nodes[0]], edges: [] }, hidden: 2 });
  });

  it('returns the graph unchanged when it is over the limit but empty of anchors', () => {
    const empty: RoutingGraph = { nodes: [], edges: [] };
    expect(regionAround(empty, 'x', -1)).toEqual({ graph: empty, hidden: 0 });
  });
});
