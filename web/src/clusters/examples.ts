import type { HealthView, LogicalNodeView, NodeEndpointView, TopologyView } from '../api/client.ts';

export type ExampleShape = 'single' | 'pair' | 'cluster';

export interface ExampleTopology {
  shape: ExampleShape;
  title: string;
  hint: string;
  topology: TopologyView;
}

function endpoint(over: Partial<NodeEndpointView>): NodeEndpointView {
  return {
    id: crypto.randomUUID(),
    name: 'broker-1',
    artemisNodeId: null,
    jolokiaUrl: null,
    coreUrl: null,
    haRole: 'PRIMARY',
    state: 'STARTED',
    active: true,
    replicaSync: null,
    version: null,
    lastError: null,
    lastSeenAt: null,
    discovered: false,
    manualOverride: false,
    manageable: true,
    ...over,
  };
}

function logical(over: Partial<LogicalNodeView>): LogicalNodeView {
  return { artemisNodeId: null, splitBrain: 'NONE', replicationBehind: false, endpoints: [], ...over };
}

const SINGLE: TopologyView = {
  clusterId: 'example-single',
  nodes: [logical({ artemisNodeId: 'node-a', endpoints: [endpoint({ name: 'broker-1', haRole: 'PRIMARY' })] })],
};

const PAIR: TopologyView = {
  clusterId: 'example-pair',
  nodes: [
    logical({
      artemisNodeId: 'node-a',
      endpoints: [
        endpoint({ name: 'broker-1', haRole: 'PRIMARY' }),
        endpoint({ name: 'broker-2', haRole: 'BACKUP', active: false, replicaSync: true }),
      ],
    }),
  ],
};

const CLUSTER: TopologyView = {
  clusterId: 'example-cluster',
  nodes: [
    logical({ artemisNodeId: 'node-a', endpoints: [endpoint({ name: 'broker-1', haRole: 'PRIMARY' })] }),
    logical({ artemisNodeId: 'node-b', endpoints: [endpoint({ name: 'broker-2', haRole: 'PRIMARY' })] }),
    logical({ artemisNodeId: 'node-c', endpoints: [endpoint({ name: 'broker-3', haRole: 'PRIMARY' })] }),
  ],
};

/** Every node in every example is UNKNOWN — layout() already renders that as a
 * neutral, dimmed mark (--as-node-unmanaged), never a warning colour. */
export const EXAMPLE_HEALTH: HealthView = {
  clusterId: 'example',
  level: 'UNKNOWN',
  liveEndpointNames: [],
  splitBrain: 'NONE',
  replicationBehind: false,
  notes: [],
};

export const EXAMPLES: ExampleTopology[] = [
  { shape: 'single', title: 'Single broker', hint: 'One management URL.', topology: SINGLE },
  {
    shape: 'pair',
    title: 'Live + backup pair',
    hint: 'One URL — Studio finds the backup.',
    topology: PAIR,
  },
  {
    shape: 'cluster',
    title: 'Clustered (3 nodes)',
    hint: 'One URL per node you can reach.',
    topology: CLUSTER,
  },
];
