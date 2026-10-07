import { describe, expect, it } from 'vitest';

import { nodeFacts, type NodeFacts } from './nodeFacts.ts';
import { sortTopology, topologyColumns } from './topologyColumns.tsx';
import type { LogicalNodeView, NodeEndpointView } from './api.ts';

function facts(over: Partial<NodeEndpointView>): NodeFacts {
  const e: NodeEndpointView = {
    id: 'e',
    name: 'node',
    artemisNodeId: 'NID',
    jolokiaUrl: 'http://node:8161/jolokia',
    coreUrl: 'node:61616',
    haRole: 'PRIMARY',
    state: 'STARTED',
    active: true,
    replicaSync: null,
    version: '2.44.0',
    versionSupport: 'SUPPORTED',
    lastError: null,
    lastSeenAt: '2026-09-30T14:00:00Z',
    urlSource: null,
    urlProblem: null,
    coreUrlManual: false,
    manageable: true,
    ...over,
  };
  const logical: LogicalNodeView = {
    artemisNodeId: 'NID',
    splitBrain: 'NONE',
    replicationBehind: false,
    endpoints: [e],
  };
  return nodeFacts(e, logical);
}

const NOW = Date.parse('2026-09-30T14:01:00Z');

describe('topologyColumns', () => {
  const columns = topologyColumns(NOW);

  it('lists the node, role, pair, liveness, version, address, last seen and node id, the node first and essential', () => {
    expect(columns.map((c) => c.header)).toEqual([
      'Node',
      'Role',
      'Pair',
      'Liveness',
      'Version',
      'Address',
      'Last seen',
      'Node ID',
    ]);
    expect(columns[0]).toMatchObject({ kind: 'identifier', priority: 'essential' });
    expect(columns.slice(1).every((c) => c.priority !== 'essential')).toBe(true);
    expect(new Set(columns.map((c) => c.sortKey)).size).toBe(columns.length);
  });

  it('reads every value from the node facts, in words', () => {
    const f = facts({ name: 'alpha', version: '2.31.2', versionSupport: 'BELOW_MINIMUM', lastError: 'refused' });
    const value = (id: string) => columns.find((c) => c.id === id)!.accessor(f);
    expect(value('node')).toBe('alpha');
    expect(value('role')).toBe('Primary');
    expect(value('pair')).toBe('no pair (standalone)');
    expect(value('liveness')).toBe('Unreachable: refused');
    expect(value('version')).toBe('2.31.2 (unsupported)');
    expect(value('address')).toBe('node:8161');
    expect(value('seen')).toBe('1m ago');
    expect(value('nodeId')).toBe('NID');
  });

  it('says so, rather than showing nothing, when a fact is missing', () => {
    const f = facts({ version: null, jolokiaUrl: null, coreUrl: null, artemisNodeId: null, lastSeenAt: null });
    const value = (id: string) => columns.find((c) => c.id === id)!.accessor({ ...f, artemisNodeId: null });
    expect(value('version')).toBe('Unknown');
    expect(value('address')).toBe('Not reported');
    expect(value('seen')).toBe('Not seen yet');
    expect(value('nodeId')).toBe('Not reported');
  });
});

describe('sortTopology', () => {
  const rows = [
    facts({ id: 'b', name: 'bravo', lastSeenAt: '2026-09-30T14:00:30Z' }),
    facts({ id: 'a', name: 'alpha', lastSeenAt: '2026-09-30T14:00:50Z' }),
    facts({ id: 'c', name: 'charlie', lastSeenAt: null }),
  ];

  it('keeps the graph order with no sort', () => {
    expect(sortTopology(rows, undefined)).toBe(rows);
  });

  it('sorts by a column, ascending and descending', () => {
    expect(sortTopology(rows, 'node').map((r) => r.name)).toEqual(['alpha', 'bravo', 'charlie']);
    expect(sortTopology(rows, '-node').map((r) => r.name)).toEqual(['charlie', 'bravo', 'alpha']);
  });

  it('sorts last seen by the instant, a node that never answered counting as the oldest', () => {
    expect(sortTopology(rows, '-seen').map((r) => r.name)).toEqual(['alpha', 'bravo', 'charlie']);
    expect(sortTopology(rows, 'seen').map((r) => r.name)).toEqual(['charlie', 'bravo', 'alpha']);
  });

  it('ignores a sort it does not know', () => {
    expect(sortTopology(rows, 'bogus')).toBe(rows);
  });
});
