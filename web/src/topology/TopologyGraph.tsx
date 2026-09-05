import { useMemo } from 'react';

import { useHealth, useTopology } from '../api/client.ts';
import { layout } from './layout.ts';
import { TopologyCanvas } from './TopologyCanvas.tsx';

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

  const model = useMemo(() => {
    if (!topology.data || !health.data) return null;
    return layout(topology.data, health.data);
  }, [topology.data, health.data]);

  if (!model) return null;

  return <TopologyCanvas model={model} />;
}
