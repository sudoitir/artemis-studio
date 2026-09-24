import { IconTransfer } from '@tabler/icons-react';

import { useCluster } from '../clusters/index.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import type { ActionProps, QueueTarget } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { gateFor, type GateVerdict } from '../../ui/capabilityGate.ts';
import { TransferDialog } from './TransferDialog.tsx';

/**
 * "Transfer messages…" on a queue's row: every message the queue holds, to a queue on another node
 * or cluster, through the same preview and resumable run as a selection on the messages screen
 * (ADR-0097). An empty queue is a reason, stated; an unknown depth is not.
 */
export function TransferQueueMessages({ clusterId, target, host }: ActionProps<QueueTarget>) {
  const { can, loading } = useCan();
  const cluster = useCluster(clusterId);
  const total = target.snapshot ? target.snapshot.totalMessageCount : null;
  const permitted = gateFor(
    can('message:move', clusterId) || can('message:read', clusterId),
    'Browse messages',
    undefined,
    loading || cluster.isPending,
  );
  const gate: GateVerdict =
    permitted.kind === 'allowed' && total === 0
      ? { kind: 'blocked', reason: 'There are no messages to transfer: the queue is empty.' }
      : permitted;
  return (
    <ActionMenuItem
      label="Transfer messages…"
      icon={<IconTransfer size={16} aria-hidden />}
      verdict={gate}
      onExplain={(verdict) => host.explain(verdict, 'transferring these messages')}
      onSelect={() =>
        host.open(TransferDialog, {
          clusterId,
          queueName: target.queueName,
          selection: { kind: 'all' },
          total,
          onStarted: () => {},
        })
      }
    />
  );
}
