import { Badge } from '@mantine/core';

/** Words carry the meaning; the warning colour is redundant emphasis. */
export function UnverifiedBadge() {
  return (
    <Badge size="xs" variant="light" color="yellow" c="var(--as-warning)">
      Unverified
    </Badge>
  );
}
