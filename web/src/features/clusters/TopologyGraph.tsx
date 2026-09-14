import { useMemo, useState } from 'react';

import { AddManagementUrl } from './AddManagementUrl.tsx';
import { useHealth, useRediscover, useTopology } from './api.ts';
import { layout } from './layout.ts';
import { TopologyActions, TopologyCanvas } from './TopologyCanvas.tsx';

/**
 * The cross-node topology, in one renderer. Replaces the Phase 1 `PairSpine` and
 * inherits its grammar (see {@link layout}). Each logical node is a group, so
 * split-brain is a layout break inside one group (both boxes above its axis), not
 * just a colour. Data-fetching wrapper around the pure {@link TopologyCanvas} —
 * see design.md Decision 6 for why the render half was split out.
 */
export function TopologyGraph({ clusterId }: { clusterId: string }) {
  const topology = useTopology(clusterId);
  const health = useHealth(clusterId);
  const rediscover = useRediscover(clusterId);
  const [addingFor, setAddingFor] = useState<string | null>(null);

  const model = useMemo(() => {
    if (!topology.data || !health.data) return null;
    return layout(topology.data, health.data);
  }, [topology.data, health.data]);

  const actions = useMemo(
    () => ({
      clusterId,
      addManagementUrl: (endpointId: string) => setAddingFor(endpointId),
      rediscover: () => rediscover.mutate(),
    }),
    [clusterId, rediscover],
  );

  if (!model) return null;

  const endpoint =
    (addingFor &&
      topology.data?.nodes.flatMap((n) => n.endpoints).find((e) => e.id === addingFor)) ||
    null;

  return (
    <TopologyActions value={actions}>
      <TopologyCanvas model={model} />
      <AddManagementUrl
        clusterId={clusterId}
        endpoint={endpoint}
        opened={addingFor !== null}
        onClose={() => setAddingFor(null)}
      />
    </TopologyActions>
  );
}
