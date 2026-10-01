import { Anchor } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { BulkItemView, BulkRunView } from './api.ts';
import { itemStatus, runStatus } from './words.ts';

/** The run's time, as a real link, so each run is reachable from the keyboard. */
export function RunLink({ run, clusterId }: Readonly<{ run: BulkRunView; clusterId: string }>) {
  return (
    <Anchor component={Link} to={`/clusters/${clusterId}/bulk/${run.id}`} size="sm">
      {absoluteLabel(run.startedAt ?? run.createdAt)}
    </Anchor>
  );
}

/** The run's status in words; colour only emphasises a run that went wrong or needs the operator. */
export function RunOutcome({ run }: Readonly<{ run: BulkRunView }>) {
  const { text, tone } = runStatus(run.status);
  return <StatusBadge tone={tone}>{text}</StatusBadge>;
}

/** A queue as a real button, so its node detail is reachable from the keyboard. */
export function QueueButton({ item, onOpen }: Readonly<{ item: BulkItemView; onOpen: (queueName: string) => void }>) {
  return (
    <Anchor
      component="button"
      type="button"
      size="sm"
      truncate
      maw="100%"
      aria-label={`Show node detail for ${item.queueName}`}
      onClick={() => onOpen(item.queueName)}
    >
      {item.queueName}
    </Anchor>
  );
}

/** The queue's outcome in words; colour only emphasises one that was refused, failed or is unknown. */
export function ItemOutcome({ item }: Readonly<{ item: BulkItemView }>) {
  const { text, tone } = itemStatus(item.status);
  return <StatusBadge tone={tone}>{text}</StatusBadge>;
}
