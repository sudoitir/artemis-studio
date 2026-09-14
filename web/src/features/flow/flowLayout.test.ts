import { describe, expect, it } from 'vitest';

import type { FlowGraphView } from './api.ts';
import { layoutSignature, pathThrough, toElkGraph, toReactFlow, COLUMNS } from './flowLayout.ts';
import { runLayout } from './useFlowLayout.ts';

const node = (id: string, kind: string, label: string, over: object = {}) => ({ id, kind, label, faults: [], ...over });
const edge = (source: string, target: string, kind: string, rate?: number) => ({
  id: `${kind}:${source}->${target}`,
  kind,
  source,
  target,
  rate,
  rateSource: kind === 'ROUTE' ? 'QUEUE_METRIC' : 'SAMPLER',
  stale: false,
  faults: [],
});

function graph(rate = 10): FlowGraphView {
  return {
    nodes: [
      node('consumer:billing', 'CONSUMER', 'billing'),
      node('queue:ORDERS', 'QUEUE', 'ORDERS', { messageCount: 50 }),
      node('queue:AUDIT', 'QUEUE', 'AUDIT', { messageCount: 200 }),
      node('address:ORDERS', 'ADDRESS', 'ORDERS'),
      node('producer:order-svc', 'PRODUCER', 'order-svc'),
      node('producer:web', 'PRODUCER', 'web'),
      node('consumer:auditor', 'CONSUMER', 'auditor'),
    ],
    edges: [
      edge('producer:order-svc', 'address:ORDERS', 'PRODUCE', rate),
      edge('producer:web', 'address:ORDERS', 'PRODUCE', 1),
      edge('address:ORDERS', 'queue:ORDERS', 'ROUTE', rate),
      edge('address:ORDERS', 'queue:AUDIT', 'ROUTE', rate),
      edge('queue:ORDERS', 'consumer:billing', 'CONSUME', rate),
      edge('queue:AUDIT', 'consumer:auditor', 'CONSUME'),
    ],
  } as FlowGraphView;
}

describe('flow layout', () => {
  it('places producers, addresses, queues and consumers in strictly ordered columns', async () => {
    const g = graph();
    const positions = await runLayout(toElkGraph(g));
    const x = (kind: string) => g.nodes!.filter((n) => n.kind === kind).map((n) => positions[n.id!].x);

    for (let i = 1; i < COLUMNS.length; i++) {
      expect(Math.max(...x(COLUMNS[i - 1]))).toBeLessThan(Math.min(...x(COLUMNS[i])));
    }
  });

  it('puts other nodes and remote brokers in a column after the consumers', async () => {
    const g = graph();
    g.nodes!.push(node('remote:node:abc', 'REMOTE', 'node-b', { role: 'CLUSTER_NODE' }) as never);
    g.nodes!.push(node('queue:$.artemis.internal.sf.demo.abc', 'QUEUE', 'sf', { role: 'STORE_AND_FORWARD' }) as never);
    g.edges!.push(edge('queue:$.artemis.internal.sf.demo.abc', 'remote:node:abc', 'CLUSTER_HOP', 7) as never);
    const positions = await runLayout(toElkGraph(g));

    const consumers = g.nodes!.filter((n) => n.kind === 'CONSUMER').map((n) => positions[n.id!].x);
    expect(positions['remote:node:abc'].x).toBeGreaterThan(Math.max(...consumers));
    const model = toReactFlow(g, positions, new Map(), null);
    expect(model.nodes.find((n) => n.id === 'remote:node:abc')?.type).toBe('remote');
  });

  it('keeps the same layout signature when only rates change', () => {
    expect(layoutSignature(graph(10))).toBe(layoutSignature(graph(9_000)));
    const grown = graph();
    grown.nodes!.push(node('queue:NEW', 'QUEUE', 'NEW') as never);
    expect(layoutSignature(grown)).not.toBe(layoutSignature(graph()));
  });

  it('emphasises the whole path through a node and dims the rest', async () => {
    const g = graph();
    const positions = await runLayout(toElkGraph(g));
    const path = pathThrough(g, 'queue:ORDERS');

    expect([...path].sort()).toEqual(
      ['address:ORDERS', 'consumer:billing', 'producer:order-svc', 'producer:web', 'queue:ORDERS'].sort(),
    );
    const model = toReactFlow(g, positions, new Map(), path);
    expect(model.nodes.find((n) => n.id === 'queue:AUDIT')?.data.dimmed).toBe(true);
    expect(model.nodes.find((n) => n.id === 'queue:ORDERS')?.data.dimmed).toBe(false);
    expect(model.edges.find((e) => e.target === 'queue:AUDIT')?.data?.dimmed).toBe(true);
  });

  it('labels each column once, and scales queue depth against the deepest shown queue', async () => {
    const g = graph();
    const model = toReactFlow(g, await runLayout(toElkGraph(g)), new Map(), null);

    expect(model.nodes.filter((n) => n.type === 'lane').map((n) => n.data.title)).toEqual([
      'Producers',
      'Addresses',
      'Queues',
      'Consumers',
    ]);
    expect(model.nodes.find((n) => n.id === 'queue:AUDIT')?.data.depth).toBe(1);
    expect(model.nodes.find((n) => n.id === 'queue:ORDERS')?.data.depth).toBe(0.25);
  });

  it('leaves out a node that has no position yet, with its edges', () => {
    const g = graph();
    const model = toReactFlow(g, { 'producer:web': { x: 0, y: 0 }, 'address:ORDERS': { x: 300, y: 0 } }, new Map(), null);

    expect(model.nodes.filter((n) => n.type !== 'lane').map((n) => n.id).sort()).toEqual([
      'address:ORDERS',
      'producer:web',
    ]);
    expect(model.edges.map((e) => e.id)).toEqual(['PRODUCE:producer:web->address:ORDERS']);
  });
});
