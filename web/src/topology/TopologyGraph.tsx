import { useMemo, useState } from 'react';

import { useFiringAlerts, useHealth, useRediscover, useTopology } from '../api/client.ts';
import { AddManagementUrl } from '../clusters/AddManagementUrl.tsx';
import { layout } from './layout.ts';
import { TopologyActions, TopologyCanvas } from './TopologyCanvas.tsx';

const NODE_SUBJECT_PREFIX = 'node:';

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
  const firing = useFiringAlerts(clusterId);
  const rediscover = useRediscover(clusterId);
  const [addingFor, setAddingFor] = useState<string | null>(null);

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

  const actions = useMemo(
    () => ({
      addManagementUrl: (endpointId: string) => setAddingFor(endpointId),
      rediscover: () => rediscover.mutate(),
    }),
    [rediscover],
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
