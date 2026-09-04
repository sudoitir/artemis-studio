import { describe, expect, it } from 'vitest';

import { layout, LIVE_Y, BACKUP_Y } from './layout.ts';
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

    const live = model.nodes.find((n) => n.id === 'p')!;
    const standby = model.nodes.find((n) => n.id === 'b')!;
    expect(live.position.y).toBe(LIVE_Y);
    expect(standby.position.y).toBe(BACKUP_Y);
    expect(model.edges).toHaveLength(1);
    expect(model.edges[0].style?.strokeDasharray).toBeUndefined();
    expect(model.axisStatus).toBe('ok');
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
    expect(model.nodes.find((n) => n.id === 'b')!.data.offset).toBe(true);
    expect(model.axisStatus).toBe('behind');
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

    expect(model.nodes).toHaveLength(2);
    expect(model.nodes.every((n) => n.position.y === LIVE_Y)).toBe(true);
    expect(model.edges).toHaveLength(0);
    expect(model.axisStatus).toBe('critical');
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

    const backup = model.nodes.find((n) => n.id === 'b')!;
    expect(backup.type).toBe('unmanaged');
    expect(backup.data.kind).toBe('unmanaged');
  });
});
