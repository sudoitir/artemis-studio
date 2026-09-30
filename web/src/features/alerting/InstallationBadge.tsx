import { Badge } from '@mantine/core';

/** Marks a rule or firing that belongs to Studio itself, not to a cluster (ADR-0135); nothing for a cluster's own. */
export function InstallationBadge({ clusterId }: { clusterId: string | null | undefined }) {
  return clusterId ? null : (
    <Badge size="xs" variant="outline" color="gray">
      Installation
    </Badge>
  );
}
