import { VisuallyHidden } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { useFiringCounts } from './api.ts';

/** How many of this cluster's alerts, and the installation's (ADR-0135), are firing, on its Alerts nav entry; nothing while none are. */
export function FiringBadge({ clusterId }: Readonly<{ clusterId: string }>) {
  const counts = useFiringCounts();
  const firing = (counts.data ?? [])
    .filter((c) => c.clusterId === clusterId || !c.clusterId)
    .reduce((sum, c) => sum + c.firing, 0);
  return firing > 0 ? (
    <>
      <StatusBadge tone="danger">{String(firing)}</StatusBadge>
      <VisuallyHidden> {firing === 1 ? 'alert' : 'alerts'} firing</VisuallyHidden>
    </>
  ) : null;
}
