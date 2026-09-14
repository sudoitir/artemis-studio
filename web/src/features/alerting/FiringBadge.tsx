import { Badge } from '@mantine/core';

import { useFiringCounts } from './api.ts';

/** How many of this cluster's alerts are firing, on its Alerts nav entry; nothing while none are. */
export function FiringBadge({ clusterId }: { clusterId: string }) {
  const counts = useFiringCounts();
  const firing = counts.data?.find((c) => c.clusterId === clusterId)?.firing ?? 0;
  return firing > 0 ? (
    <Badge size="xs" variant="filled" color="red" circle>
      {firing}
    </Badge>
  ) : null;
}
