import { IconChartSankey, IconPlugConnected } from '@tabler/icons-react';
import { useNavigate } from '@tanstack/react-router';

import type {
  ActionProps,
  AddressTarget,
  ClientTarget,
  ConnectionTarget,
  DivertTarget,
  QueueTarget,
} from '../../kernel/actions/types.ts';
import { ResourceActions } from '../../kernel/actions/ResourceActions.tsx';
import { clusterHref } from '../../kernel/routing/href.ts';
import { ActionMenuItem } from '../../ui/ActionMenuItem.tsx';
import type { FlowNodeView } from './api.ts';

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

/** A flow client: focus the view on it. */
export function FocusClient({ clusterId, target }: ActionProps<ClientTarget>) {
  const navigate = useNavigate();
  const focus = `client:${target.label}`;
  return (
    <ActionMenuItem
      label="Focus the view on this"
      icon={<IconChartSankey size={16} aria-hidden />}
      href={clusterHref(clusterId, 'flow', { focus })}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/flow`, search: { focus } as never })}
    />
  );
}

/** A flow client's connections, found by what Flow names it by: its client id, user or host. */
export function ClientConnections({ clusterId, target }: ActionProps<ClientTarget>) {
  const navigate = useNavigate();
  return (
    <ActionMenuItem
      label="Open its connections"
      icon={<IconPlugConnected size={16} aria-hidden />}
      href={clusterHref(clusterId, 'connections', { q: target.label })}
      onSelect={() => navigate({ to: `/clusters/${clusterId}/connections`, search: { q: target.label } as never })}
    />
  );
}

/**
 * A flow node's menu: its resource's Open and Copy items only (flow-visualization spec: menus in
 * the flow view navigate and never act).
 */
export function FlowNodeActions({
  clusterId,
  node,
  restoreFocus,
}: {
  clusterId: string;
  node: FlowNodeView;
  restoreFocus?: () => void;
}) {
  const label = node.label ?? '';
  switch (node.kind) {
    case 'QUEUE':
      return <ResourceActions kind="queue" clusterId={clusterId} target={{ queueName: label }} mode="navigate" restoreFocus={restoreFocus} />;
    case 'ADDRESS':
      return <ResourceActions kind="address" clusterId={clusterId} target={{ address: label }} mode="navigate" restoreFocus={restoreFocus} />;
    default:
      return <ResourceActions kind="client" clusterId={clusterId} target={{ label }} mode="navigate" restoreFocus={restoreFocus} />;
  }
}
