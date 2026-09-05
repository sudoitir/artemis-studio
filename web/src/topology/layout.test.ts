import { describe, expect, it } from 'vitest';

import { layout, isBrokerNode, LIVE_Y, BACKUP_Y, GROUP_PAD } from './layout.ts';
import type { Node } from '@xyflow/react';
import type { BrokerNodeData, TopologyLayout } from './layout.ts';
import type { HealthView, NodeEndpointView, TopologyView } from '../api/client.ts';

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
        endpoints: [
          endpoint({ id: 'x', name: 'broker-2:61616', jolokiaUrl: null, manageable: false }),
        ],
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

  it('an empty topology lays out nothing', () => {
    const model = layout(topo(), health());
    expect(model.nodes).toHaveLength(0);
    expect(model.edges).toHaveLength(0);
  });
});
