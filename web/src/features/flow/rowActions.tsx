import { IconChartSankey } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import type {
  ActionProps,
  AddressTarget,
  ConnectionTarget,
  DivertTarget,
  QueueTarget,
} from '../../kernel/actions/types.ts';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';

/** "Show in Flow": the flow view focused on one resource, its upstream and downstream. */
function ShowInFlow({ clusterId, focus, reason }: { clusterId: string; focus: string | null; reason?: string }) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Show in Flow"
      icon={<IconChartSankey size={16} aria-hidden />}
      verdict={focus ? undefined : { kind: 'blocked', reason: reason ?? 'There is nothing to focus the flow on.' }}
      href={focus ? clusterHref(clusterId, 'flow', { focus }) : undefined}
      onSelect={() => focus && navigate({ to: `/clusters/${clusterId}/flow`, search: { focus } as never })}
    />
  );
}

export function QueueInFlow({ clusterId, target }: ActionProps<QueueTarget>) {
  return <ShowInFlow clusterId={clusterId} focus={`queue:${target.queueName}`} />;
}

export function AddressInFlow({ clusterId, target }: ActionProps<AddressTarget>) {
  return <ShowInFlow clusterId={clusterId} focus={`address:${target.address}`} />;
}

export function ConnectionInFlow({ clusterId, target }: ActionProps<ConnectionTarget>) {
  // Flow names a client by its client id by default; a connection without one is found by host.
  const clientId = target.snapshot?.clientId;
  return (
    <ShowInFlow
      clusterId={clusterId}
      focus={clientId ? `client:${clientId}` : null}
      reason="This connection has no client id, which is what Flow groups clients by. Group Flow by host to find it."
    />
  );
}

export function DivertInFlow({ clusterId, target }: ActionProps<DivertTarget>) {
  const address = target.snapshot?.address;
  return <ShowInFlow clusterId={clusterId} focus={address ? `address:${address}` : null} />;
}
