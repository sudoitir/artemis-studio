import { StatusBadge } from '../../ui/StatusBadge.tsx';

/** Marks a rule or firing that belongs to Studio itself, not to a cluster (ADR-0135); nothing for a cluster's own. */
export function InstallationBadge({ clusterId }: Readonly<{ clusterId: string | null | undefined }>) {
  return clusterId ? null : <StatusBadge>Installation</StatusBadge>;
}
