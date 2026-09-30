import { Alert, List, Stack, Text, VisuallyHidden } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { useCluster, useDeleteCluster } from './api.ts';
import styles from './RemoveClusterSection.module.css';

/**
 * Settings section: remove this cluster from Studio (cluster-registration spec). It lives here, last in the
 * cluster's settings, rather than above every view of the cluster. What goes and what stays is stated before
 * the typed name arms the button (non-negotiable #2).
 */
export function RemoveClusterSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  const remove = useDeleteCluster();
  const navigate = useNavigate();
  const { can, loading } = useCan();
  // While grants load the control is offered; the server is the enforcement point.
  const denied = !loading && !can('cluster:write', clusterId);
  const name = cluster.data?.name;

  return (
    <div className={styles.danger}>
      <Stack gap="sm">
        <Text size="sm">
          Removing <strong>{name ?? 'this cluster'}</strong> deletes what Studio keeps for it:
        </Text>
        <List size="sm" spacing={2}>
          <List.Item>its registration, nodes and stored broker credentials;</List.Item>
          <List.Item>
            its alert rules, request-reply flows, message-index subscriptions and configuration history.
          </List.Item>
        </List>
        <Text size="sm">
          Nothing on the broker changes: queues, messages and any capture diverts stay. The audit trail is kept.
          Registering the cluster again starts from scratch.
        </Text>

        {denied ? (
          <Text size="sm" c="dimmed">
            You cannot remove this cluster: that needs the <code>cluster:write</code> permission on it.
          </Text>
        ) : null}

        {remove.isError ? (
          <Alert color="red" variant="light" title="The cluster was not removed">
            {remove.error.message} It is still registered; try again, or check that you still hold{' '}
            <code>cluster:write</code> on it.
          </Alert>
        ) : null}

        {name ? (
          <ConfirmByTyping
            token={name}
            confirmLabel="Remove cluster"
            loading={remove.isPending}
            disabled={denied}
            onConfirm={() =>
              remove.mutate(clusterId, {
                onSuccess: () => {
                  notifications.show({ color: 'gray', title: 'Cluster removed', message: name });
                  void navigate({ to: '/' });
                },
              })
            }
          />
        ) : null}

        <VisuallyHidden aria-live="polite">
          {remove.isPending ? `Removing ${name}` : ''}
          {remove.isError ? `${name} was not removed` : ''}
          {remove.isSuccess ? `${name} removed` : ''}
        </VisuallyHidden>
      </Stack>
    </div>
  );
}
