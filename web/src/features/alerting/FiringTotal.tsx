import { Badge } from '@mantine/core';

import { useFiringCounts } from './api.ts';

/** How many alerts are firing across every cluster, in the application header (`shell.header`); nothing while none are. */
export function FiringTotal() {
  const counts = useFiringCounts();
  const total = (counts.data ?? []).reduce((sum, c) => sum + c.firing, 0);
  return total > 0 ? (
    <Badge size="sm" variant="light" color="red" aria-label={`${total} alert${total === 1 ? '' : 's'} firing`}>
      {total} firing
    </Badge>
  ) : null;
}
