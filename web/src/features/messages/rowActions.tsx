import { IconMail } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import type { ActionProps, QueueTarget } from '../../kernel/actions/types.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import { gateFor } from '../../ui/capabilityGate.ts';

/** "Browse messages" on a queue's row: the message browser for that queue. */
export function BrowseQueueMessages({ clusterId, target }: ActionProps<QueueTarget>) {
  const navigate = useNavigate();
  const { can, loading } = useCan();
  const gate = gateFor(can('message:read', clusterId), 'Browse messages', undefined, loading);
  const path = `queues/${encodeURIComponent(target.queueName)}/messages`;
  return (
    <ActionMenuItem
      label="Browse messages"
      icon={<IconMail size={16} aria-hidden />}
      verdict={gate}
      href={clusterHref(clusterId, path)}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/${path}` })}
    />
  );
}
