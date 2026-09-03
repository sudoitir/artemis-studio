import { test } from 'node:test';
import assert from 'node:assert/strict';

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

test('a healthy pair: live above the axis, standby below, solid edge', () => {
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
  assert.equal(live.position.y, LIVE_Y);
  assert.equal(standby.position.y, BACKUP_Y);
  assert.equal(model.edges.length, 1);
  assert.equal(model.edges[0].style?.strokeDasharray, undefined);
  assert.equal(model.axisStatus, 'ok');
});

test('replication behind: dashed edge, offset standby, behind axis', () => {
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

  assert.equal(model.edges[0].style?.strokeDasharray, '6 4');
  assert.equal(model.nodes.find((n) => n.id === 'b')!.data.offset, true);
  assert.equal(model.axisStatus, 'behind');
});

test('split-brain critical: both boxes above the axis, no edge', () => {
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

  assert.equal(model.nodes.length, 2);
  assert.ok(model.nodes.every((n) => n.position.y === LIVE_Y));
  assert.equal(model.edges.length, 0);
  assert.equal(model.axisStatus, 'critical');
});

test('unmanaged backup: rendered as an unmanaged node type', () => {
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
  assert.equal(backup.type, 'unmanaged');
  assert.equal(backup.data.kind, 'unmanaged');
});
