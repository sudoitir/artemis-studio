import { IconChartLine } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import type { ActionProps, QueueTarget } from '../../kernel/actions/types.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';

/** "Open history" on a queue's row: the metrics view scoped to that queue. */
export function OpenQueueHistory({ clusterId, target }: ActionProps<QueueTarget>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open history"
      icon={<IconChartLine size={16} aria-hidden />}
      href={clusterHref(clusterId, 'metrics', { subject: target.queueName })}
      onSelect={() =>
        navigate({ to: `/clusters/${clusterId}/metrics`, search: { subject: target.queueName } as never })
      }
    />
  );
}
