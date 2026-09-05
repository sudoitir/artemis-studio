import { useMemo } from 'react';

import { useFiringAlerts, useHealth, useTopology } from '../api/client.ts';
import { layout } from './layout.ts';
import { TopologyCanvas } from './TopologyCanvas.tsx';

const NODE_SUBJECT_PREFIX = 'node:';

/**
 * The cross-node topology, in one renderer. Replaces the Phase 1 `PairSpine` and
 * inherits its grammar (see {@link layout}). Failover animates the promoted box
 * across the axis; split-brain is a layout break (both boxes above), not just a
 * colour. Data-fetching wrapper around the pure {@link TopologyCanvas} — see
 * design.md Decision 6 for why the render half was split out.
 */
export function TopologyGraph({ clusterId }: { clusterId: string }) {
  const topology = useTopology(clusterId);
  const health = useHealth(clusterId);
  const firing = useFiringAlerts(clusterId);

  const firingNodeIds = useMemo(() => {
    const ids = new Set<string>();
    for (const f of firing.data ?? []) {
      if (f.subjectKey.startsWith(NODE_SUBJECT_PREFIX)) {
        ids.add(f.subjectKey.slice(NODE_SUBJECT_PREFIX.length));
      }
    }
    return ids;
  }, [firing.data]);

  const model = useMemo(() => {
    if (!topology.data || !health.data) return null;
    return layout(topology.data, health.data, firingNodeIds);
  }, [topology.data, health.data, firingNodeIds]);

  if (!model) return null;

  return <TopologyCanvas model={model} />;
}
