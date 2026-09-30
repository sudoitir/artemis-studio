import { describe, expect, it } from 'vitest';

import { layout, isBrokerNode, LIVE_Y, BACKUP_Y, GROUP_PAD, DENSE_THRESHOLD, COL_W } from './layout.ts';
import type { Node } from '@xyflow/react';
import type { BrokerNodeData, TopologyLayout } from './layout.ts';
import type { HealthView, NodeEndpointView, TopologyView } from './api.ts';

function endpoint(over: Partial<NodeEndpointView>): NodeEndpointView {
  return {
    id: over.id ?? crypto.randomUUID(),
    name: over.name ?? 'node',
    artemisNodeId: over.artemisNodeId ?? 'NID',
    jolokiaUrl: over.jolokiaUrl ?? 'http://node:8161/jolokia',
    coreUrl: over.coreUrl ?? 'node:61616',
    haRole: over.haRole ?? 'PRIMARY',
    state: over.state ?? 'STARTED',
    active: over.active ?? false,
    replicaSync: over.replicaSync ?? null,
    version: over.version ?? '2.44.0',
    versionSupport: over.versionSupport ?? 'SUPPORTED',
    lastError: over.lastError ?? null,
    lastSeenAt: over.lastSeenAt ?? new Date().toISOString(),
    discovered: false,
    manualOverride: false,
    manageable: over.manageable ?? true,
  };
}

function topo(...nodes: TopologyView['nodes']): TopologyView {
  return { clusterId: 'c', nodes };
}

function health(over: Partial<HealthView> = {}): HealthView {
  return {
    clusterId: 'c',
    level: over.level ?? 'OK',
    liveEndpointNames: over.liveEndpointNames ?? [],
    splitBrain: over.splitBrain ?? 'NONE',
    replicationBehind: over.replicationBehind ?? false,
    notes: over.notes ?? [],
  };
}

/** Endpoint boxes only — the pair groups that contain them are filtered out. */
function boxes(model: TopologyLayout): Node<BrokerNodeData>[] {
  return model.nodes.filter(isBrokerNode);
}

function groups(model: TopologyLayout) {
  return model.nodes.filter((n) => !isBrokerNode(n));
}

function box(model: TopologyLayout, id: string): Node<BrokerNodeData> {
  return boxes(model).find((n) => n.id === id)!;
}

describe('topology layout', () => {
  it('a healthy pair: live above the axis, standby below, solid edge', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', haRole: 'PRIMARY', active: true }),
          endpoint({ id: 'b', name: 'backup', haRole: 'BACKUP', active: false, replicaSync: true }),
        ],
      }),
      health(),
    );

    const live = box(model, 'p');
    const standby = box(model, 'b');
    expect(live.position.y).toBe(LIVE_Y);
    expect(standby.position.y).toBe(BACKUP_Y);
    expect(model.edges).toHaveLength(1);
    expect(model.edges[0].style?.strokeDasharray).toBeUndefined();
    expect(groups(model)).toHaveLength(1);
    expect(groups(model)[0].data.axisStatus).toBe('ok');
  });

  it('replication behind: dashed edge, offset standby, behind axis', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'NONE',
        replicationBehind: true,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', haRole: 'PRIMARY', active: true }),
          endpoint({ id: 'b', name: 'backup', haRole: 'BACKUP', active: false, replicaSync: false }),
        ],
      }),
      health({ level: 'DEGRADED', replicationBehind: true }),
    );

    expect(model.edges[0].style?.strokeDasharray).toBe('6 4');
    expect(box(model, 'b').data.offset).toBe(true);
    expect(box(model, 'b').data.kind).toBe('behind');
    expect(groups(model)[0].data.axisStatus).toBe('behind');
  });

  it('split-brain critical: both boxes above the axis, no edge', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'CRITICAL',
        replicationBehind: false,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', haRole: 'PRIMARY', active: true }),
          endpoint({ id: 'b', name: 'backup', haRole: 'PRIMARY', active: true }),
        ],
      }),
      health({ level: 'CRITICAL', splitBrain: 'CRITICAL' }),
    );

    expect(boxes(model)).toHaveLength(2);
    expect(boxes(model).every((n) => n.position.y === LIVE_Y)).toBe(true);
    expect(model.edges).toHaveLength(0);
    expect(groups(model)).toHaveLength(1);
    expect(groups(model)[0].data.axisStatus).toBe('critical');
  });

  it('unmanaged backup: rendered as an unmanaged node type', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', haRole: 'PRIMARY', active: true }),
          endpoint({
            id: 'b',
            name: 'backup:61616',
            haRole: 'BACKUP',
            active: false,
            jolokiaUrl: null,
            manageable: false,
          }),
        ],
      }),
      health(),
    );

    const backup = box(model, 'b');
    expect(backup.type).toBe('unmanaged');
    expect(backup.data.kind).toBe('unmanaged');
  });

  it('one group per logical node, and every box is its child', () => {
    const model = layout(
      topo(
        {
          artemisNodeId: 'NID-A',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [
            endpoint({ id: 'a1', name: 'a-primary', active: true }),
            endpoint({ id: 'a2', name: 'a-backup', active: false, replicaSync: true }),
          ],
        },
        {
          artemisNodeId: 'NID-B',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [endpoint({ id: 'b1', name: 'b-primary', active: true })],
        },
      ),
      health(),
    );

    expect(groups(model)).toHaveLength(2);
    expect(groups(model).map((g) => g.id)).toEqual(['pair:NID-A', 'pair:NID-B']);
    expect(box(model, 'a1').parentId).toBe('pair:NID-A');
    expect(box(model, 'a2').parentId).toBe('pair:NID-A');
    expect(box(model, 'b1').parentId).toBe('pair:NID-B');
    expect(boxes(model).every((n) => n.extent === 'parent')).toBe(true);
  });

  it('a parent always precedes its children in the node array', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', active: true }),
          endpoint({ id: 'b', name: 'backup', active: false, replicaSync: true }),
        ],
      }),
      health(),
    );

    const groupIndex = model.nodes.findIndex((n) => n.id === 'pair:NID');
    const childIndexes = ['p', 'b'].map((id) => model.nodes.findIndex((n) => n.id === id));
    expect(groupIndex).toBeGreaterThanOrEqual(0);
    expect(childIndexes.every((i) => i > groupIndex)).toBe(true);
  });

  it('split-brain: both boxes sit above the group axis, inside one group', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'CRITICAL',
        replicationBehind: false,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', active: true }),
          endpoint({ id: 'b', name: 'backup', active: true }),
        ],
      }),
      health({ level: 'CRITICAL', splitBrain: 'CRITICAL' }),
    );

    expect(groups(model)).toHaveLength(1);
    expect(boxes(model).every((n) => n.parentId === 'pair:NID')).toBe(true);
    expect(boxes(model).every((n) => n.position.y === LIVE_Y)).toBe(true);
    // The group widens to hold both boxes rather than letting one escape it.
    const width = groups(model)[0].style?.width as number;
    const rightmost = Math.max(...boxes(model).map((n) => n.position.x));
    expect(width).toBeGreaterThan(rightmost + GROUP_PAD);
  });

  it('a lone unmanaged endpoint still produces a group', () => {
    const model = layout(
      topo({
        artemisNodeId: null,
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [endpoint({ id: 'x', name: 'broker-2:61616', jolokiaUrl: null, manageable: false })],
      }),
      health(),
    );

    expect(groups(model)).toHaveLength(1);
    expect(boxes(model)).toHaveLength(1);
    expect(box(model, 'x').parentId).toBe(groups(model)[0].id);
    expect(box(model, 'x').data.kind).toBe('unmanaged');
  });

  it('the screen-reader summary carries the cluster-level roll-up', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'NONE',
        replicationBehind: true,
        endpoints: [
          endpoint({ id: 'p', name: 'primary', active: true }),
          endpoint({ id: 'b', name: 'backup', active: false, replicaSync: false }),
        ],
      }),
      health({ level: 'DEGRADED', replicationBehind: true }),
    );

    expect(model.summary).toContain('Replication is not caught up');
  });

  it('a node outside the supported range says so in words, to sighted and screen-reader users alike', () => {
    const model = layout(
      topo(
        {
          artemisNodeId: 'A',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [
            endpoint({ id: 'old', name: 'old', active: true, version: '2.31.2', versionSupport: 'BELOW_MINIMUM' }),
          ],
        },
        {
          artemisNodeId: 'B',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [
            endpoint({ id: 'new', name: 'new', active: true, version: '2.60.0', versionSupport: 'NEWER_THAN_TESTED' }),
          ],
        },
        {
          artemisNodeId: 'C',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [endpoint({ id: 'ok', name: 'ok', active: true })],
        },
      ),
      health(),
    );

    expect(box(model, 'old').data.versionNote).toBe('unsupported release: older than Studio supports');
    expect(box(model, 'old').data.srSentence).toContain('Artemis 2.31.2, unsupported release');
    expect(box(model, 'new').data.versionNote).toBe('newer release than Studio has tested');
    expect(box(model, 'ok').data.versionNote).toBeNull();
  });

  it('an empty topology lays out nothing', () => {
    const model = layout(topo(), health());
    expect(model.nodes).toHaveLength(0);
    expect(model.edges).toHaveLength(0);
  });
});

describe('topology layout: endpoint states', () => {
  const lone = (e: Partial<NodeEndpointView>, serving = false) =>
    box(
      layout(
        topo({
          artemisNodeId: 'NID',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [{ ...endpoint({ id: 'e', ...e, active: serving }), ...e }],
        }),
        health(),
      ),
      'e',
    );

  it('a stopped endpoint reads stopped, and one with an error reads unreachable', () => {
    expect(lone({ state: 'STOPPED' }).data).toMatchObject({ kind: 'down', statusWord: 'stopped' });
    const failing = lone({ lastError: 'connection refused' }, true);
    expect(failing.data).toMatchObject({ kind: 'down', statusWord: 'unreachable', lastError: 'connection refused' });
  });

  it('a live endpoint states its version in the screen-reader sentence, and a versionless one does not', () => {
    const live = lone({ name: 'a', version: '2.44.0' }, true);
    expect(live.data).toMatchObject({ kind: 'live', statusWord: 'live', srSentence: 'a: live, Artemis 2.44.0.' });
    const bare = lone({ name: 'a', version: null }, true);
    expect(bare.data).toMatchObject({ version: null, srSentence: 'a: live.' });
  });

  it('a standby whose replica is not in sync is behind, otherwise in sync', () => {
    expect(lone({ replicaSync: false }).data).toMatchObject({ kind: 'behind', statusWord: 'not caught up' });
    expect(lone({ replicaSync: true }).data).toMatchObject({ kind: 'standby', statusWord: 'standby' });
  });

  it('an unmanaged endpoint says it has no management URL', () => {
    expect(lone({ manageable: false })).toMatchObject({
      type: 'unmanaged',
      data: { unmanaged: true, statusWord: 'discovered — no management URL' },
    });
  });

  it('shows the management host and port, falling back to the raw text, then the core URL', () => {
    expect(lone({ jolokiaUrl: 'http://broker.example:8161/jolokia' }).data.address).toBe('broker.example:8161');
    expect(lone({ jolokiaUrl: 'https://broker.example/jolokia' }).data.address).toBe('broker.example');
    expect(lone({ jolokiaUrl: 'not a url' }).data.address).toBe('not a url');
    expect(lone({ jolokiaUrl: null, coreUrl: 'core:61616' }).data.address).toBe('core:61616');
    expect(lone({ jolokiaUrl: null, coreUrl: null }).data.address).toBeNull();
  });

  it('a pair with no serving endpoint still lays out its backup, without an edge', () => {
    const model = layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [endpoint({ id: 'b', active: false })],
      }),
      health(),
    );
    expect(box(model, 'b').position.y).toBe(BACKUP_Y);
    expect(model.edges).toHaveLength(0);
  });
});

describe('topology layout: axis and summary', () => {
  const pair = (splitBrain: string, replicationBehind = false) =>
    layout(
      topo({
        artemisNodeId: 'NID',
        splitBrain,
        replicationBehind,
        endpoints: [endpoint({ id: 'p', active: true }), endpoint({ id: 'b', active: false })],
      }),
      health(),
    );

  it('names the axis by what is wrong with the pair', () => {
    expect(groups(pair('NONE', false))[0].data).toMatchObject({ axisStatus: 'ok', axisNote: 'shared NodeID' });
    expect(groups(pair('NONE', true))[0].data).toMatchObject({ axisStatus: 'behind', axisNote: 'replication behind' });
    expect(groups(pair('SUSPECTED'))[0].data).toMatchObject({
      axisStatus: 'suspected',
      axisNote: 'checking — two nodes reporting active',
    });
    expect(groups(pair('CRITICAL'))[0].data).toMatchObject({
      axisStatus: 'critical',
      axisNote: 'two nodes live in one pair',
    });
  });

  it('a node with no NodeID is grouped under a placeholder id', () => {
    const model = layout(
      topo({ artemisNodeId: null, splitBrain: 'NONE', replicationBehind: false, endpoints: [] }),
      health(),
    );
    expect(groups(model)[0]).toMatchObject({ id: 'pair:—', data: { shortId: '—' } });
  });

  it('packs pairs left to right in NodeID order, a split brain taking more room', () => {
    const model = layout(
      topo(
        {
          artemisNodeId: 'B',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [endpoint({ id: 'b1', active: true })],
        },
        {
          artemisNodeId: 'A',
          splitBrain: 'CRITICAL',
          replicationBehind: false,
          endpoints: [endpoint({ id: 'a1', active: true }), endpoint({ id: 'a2', active: true })],
        },
      ),
      health(),
    );
    const [first, second] = groups(model);
    expect(first.id).toBe('pair:A');
    expect(second.id).toBe('pair:B');
    expect(second.position.x).toBeGreaterThan(first.position.x + 2 * (COL_W - 44));
  });

  it('adds the split-brain sentences and lists standbys in the summary', () => {
    const t = topo({
      artemisNodeId: 'NIDNIDNID',
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [endpoint({ id: 'p', name: 'primary', active: true }), endpoint({ id: 'b', name: 'backup' })],
    });
    expect(layout(t, health({ level: 'CRITICAL', splitBrain: 'CRITICAL' })).summary).toBe(
      'Cluster health critical. Split-brain confirmed. node NIDNIDNI: primary live, backup standby.',
    );
    expect(layout(t, health({ level: 'DEGRADED', splitBrain: 'SUSPECTED' })).summary).toContain(
      'Split-brain suspected.',
    );
    expect(layout(t, health()).summary).toBe('Cluster health ok. node NIDNIDNI: primary live, backup standby.');
  });

  it('says none is live when nothing serves, and names an unknown node', () => {
    const t = topo({
      artemisNodeId: null,
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [endpoint({ id: 'x', name: 'x' })],
    });
    expect(layout(t, health()).summary).toContain('node unknown: none live, x standby');
  });
});

describe('topology layout: reduced detail', () => {
  const logical = (id: string, over: Partial<TopologyView['nodes'][number]> = {}): TopologyView['nodes'][number] => ({
    artemisNodeId: id,
    splitBrain: 'NONE',
    replicationBehind: false,
    endpoints: [
      endpoint({ id: `${id}-p`, name: `${id}-primary`, active: true }),
      endpoint({ id: `${id}-b`, name: `${id}-backup` }),
    ],
    ...over,
  });
  const many = (n: number) => Array.from({ length: n }, (_, i) => logical(`N${String(i).padStart(3, '0')}`));

  it('keeps the full layout at the threshold and collapses one past it', () => {
    expect(layout(topo(...many(DENSE_THRESHOLD)), health()).dense).toBe(false);
    const model = layout(topo(...many(DENSE_THRESHOLD + 1)), health());
    expect(model.dense).toBe(true);
    expect(model.edges).toHaveLength(0);
    expect(model.nodes).toHaveLength(DENSE_THRESHOLD + 1);
    expect(groups(model)).toHaveLength(0);
  });

  it('wraps the collapsed boxes into a grid of eight columns', () => {
    const model = layout(topo(...many(DENSE_THRESHOLD + 1)), health());
    const at = (i: number) => model.nodes[i].position;
    expect(at(0)).toEqual({ x: 0, y: 0 });
    expect(at(7)).toEqual({ x: 7 * COL_W, y: 0 });
    expect(at(8)).toEqual({ x: 0, y: 140 });
  });

  it('a collapsed pair says in words what the axis would have shown', () => {
    const t = (over: Partial<TopologyView['nodes'][number]>) =>
      layout(topo(...many(DENSE_THRESHOLD), logical('ZZZ', over)), health()).nodes.at(-1) as Node<BrokerNodeData>;

    expect(t({}).data).toMatchObject({
      kind: 'live',
      statusWord: 'serving · 1 standby',
      name: 'ZZZ-primary',
      shortId: 'ZZZ',
      nodeIds: ['ZZZ-p', 'ZZZ-b'],
      srSentence: 'Node ZZZ: serving · 1 standby. 2 endpoints.',
    });
    expect(t({ replicationBehind: true }).data).toMatchObject({ kind: 'behind', statusWord: 'replication behind' });
    expect(t({ splitBrain: 'SUSPECTED' }).data).toMatchObject({ kind: 'down', statusWord: 'split brain suspected' });
    expect(
      t({
        splitBrain: 'CRITICAL',
        endpoints: [endpoint({ id: 'a', active: true }), endpoint({ id: 'b', active: true })],
      }).data,
    ).toMatchObject({ kind: 'down', statusWord: 'split brain — 2 serving' });
    expect(t({ endpoints: [endpoint({ id: 'only', name: 'only' })] }).data).toMatchObject({
      kind: 'down',
      statusWord: 'nothing serving',
      name: 'only',
      srSentence: 'Node ZZZ: nothing serving. 1 endpoint.',
    });
    const anonymous = layout(
      topo(logical('ZZZ', { endpoints: [], artemisNodeId: null }), ...many(DENSE_THRESHOLD)),
      health(),
    ).nodes[0] as Node<BrokerNodeData>;
    expect(anonymous.data).toMatchObject({ name: '—', shortId: '—', nodeIds: [] });
  });
});
