import { Stack } from '@mantine/core';
import { Outlet, useParams } from '@tanstack/react-router';

import { useFeatures } from '../features.ts';
import { useSlot } from '../slots.ts';
import { useClusterStream } from '../stream/useClusterStream.ts';

/**
 * One cluster's screen: the header its features contribute, then the routed view.
 *
 * It mounts the cluster's one SSE stream, subscribed to the topics of every enabled feature
 * (ADR-0018, ADR-0070). A view never opens a second one for a topic a feature handles: a second
 * `EventSource` is a second connection, and it fights over the shared stream-status store. The
 * stream reconnects indefinitely and reports its state to the header's freshness indicator
 * (ADR-0052); while it is down the per-hook refetch intervals keep every view updating.
 */
export function ClusterLayout() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const topics = [...new Set(useFeatures().flatMap((feature) => Object.keys(feature.streamTopics ?? {})))].sort();
  useClusterStream(clusterId, topics);
  const header = useSlot('cluster.header');

  return (
    <Stack gap="lg">
      {header.map(({ id, Component }) => (
        <Component key={id} clusterId={clusterId} />
      ))}
      <Outlet />
    </Stack>
  );
}
