import { IconTransfer } from '@tabler/icons-react';

import { useCluster } from '../clusters/index.ts';
import { useResourceGate } from '../../kernel/auth/useResourceGate.ts';
import type { ActionProps, QueueTarget } from '../../kernel/actions/types.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { TransferDialog } from './TransferDialog.tsx';

/**
 * "Transfer messages…" on a queue's row: every message the queue holds, to a queue on another node
 * or cluster, through the same preview and resumable run as a selection on the messages screen
 * (ADR-0097). An empty queue is a reason, stated; an unknown depth is not.
 */
export function TransferQueueMessages({ clusterId, target, host }: Readonly<ActionProps<QueueTarget>>) {
  const cluster = useCluster(clusterId);
  const total = target.snapshot ? target.snapshot.totalMessageCount : null;
  const move = useResourceGate({
    clusterId,
    noun: 'queue',
    permission: 'message:move',
    label: 'Move or retry messages',
    resource: target.snapshot,
    pending: cluster.isPending,
  });
  const read = useResourceGate({
    clusterId,
    noun: 'queue',
    permission: 'message:read',
    label: 'Browse messages',
    resource: target.snapshot,
    pending: cluster.isPending,
  });
  // Either is enough to start from this queue: the move or retry is checked again, with the target, when it runs.
  const permitted = move.verdict.kind === 'allowed' ? move.verdict : read.verdict;
  if (move.hidden && read.hidden) return null;
  const gate: GateVerdict =
    permitted.kind === 'allowed' && total === 0
      ? { kind: 'blocked', reason: 'There are no messages to transfer: the queue is empty.' }
      : permitted;
  return (
    <ActionMenuItem
      label="Transfer messages…"
      icon={<IconTransfer size="1rem" aria-hidden />}
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
