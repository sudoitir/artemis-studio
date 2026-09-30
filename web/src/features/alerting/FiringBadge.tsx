import { Badge } from '@mantine/core';

import { useFiringCounts } from './api.ts';

/** How many of this cluster's alerts, and the installation's (ADR-0133), are firing, on its Alerts nav entry; nothing while none are. */
export function FiringBadge({ clusterId }: { clusterId: string }) {
  const counts = useFiringCounts();
  const firing = (counts.data ?? [])
    .filter((c) => c.clusterId === clusterId || !c.clusterId)
    .reduce((sum, c) => sum + c.firing, 0);
  return firing > 0 ? (
    <Badge size="xs" variant="filled" color="red" circle>
      {firing}
    </Badge>
  ) : null;
}
