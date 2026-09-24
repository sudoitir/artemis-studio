import { IconEraser } from '@tabler/icons-react';

import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { ActionProps, QueueTarget } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';
import { BulkPreviewDialog } from './BulkPreviewDialog.tsx';
import { OPERATIONS } from './words.ts';

/**
 * "Purge messages…" on a queue's row. A cluster-wide purge with a per-node outcome is a bulk run of
 * one queue (ADR-0093): the same preview, blast radius, cap and audit, confirmed by the queue's name.
 * Accepting it opens the run, so focus is not handed back to a row the operator has left.
 */
export function PurgeQueue({ clusterId, target, host }: ActionProps<QueueTarget>) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const op = OPERATIONS.PURGE;
  const gate = gateFor(
    can(op.permission, clusterId),
    op.permissionLabel,
    cluster.data?.capabilities.managementWrite,
    loading || cluster.isPending,
  );
  return (
    <ActionMenuItem
      label="Purge messages…"
      icon={<IconEraser size={16} aria-hidden />}
      tone="danger"
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'purging this queue')}
      onSelect={() =>
        host.open(BulkPreviewDialog, {
          clusterId,
          operation: 'PURGE',
          selection: { kind: 'names', names: [target.queueName] },
          onStarted: () => {},
        })
      }
    />
  );
}
