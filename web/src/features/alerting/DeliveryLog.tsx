import { Button, Drawer, Stack, Text } from '@mantine/core';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import { useChannelDeliveries, useRetryDelivery, type AlertDeliveryView, type NotificationChannelView } from './api.ts';
import { deliveryColumns } from './columns.ts';

const RETRY: ActionVerb = { verb: 'Retry', past: 'Retried', progressive: 'Retrying' };

const rowKey = (d: AlertDeliveryView) => String(d.seq);

/**
 * A channel's delivery log, newest first (ADR-0105): what was sent, how many attempts it
 * took, and why the last one failed. A failed delivery can be put back on the queue once
 * the channel is fixed.
 */
export function DeliveryLog({
  channel,
  onClose,
  canWrite,
}: Readonly<{
  channel: NotificationChannelView | null;
  onClose: () => void;
  canWrite: boolean;
}>) {
  const zone = useDisplayZone();
  const deliveries = useChannelDeliveries(channel?.id ?? null);
  const retry = useRetryDelivery(channel?.id ?? '');

  const retryDelivery = (d: AlertDeliveryView) => {
    const subject = `delivery ${d.seq}`;
    retry.mutate(d.seq, {
      onSuccess: () => notify.succeeded({ action: RETRY, subject }),
      onError: (error) =>
        notify.settle(error, {
          action: RETRY,
          subject,
          cause: error.message,
          next: 'It was not queued again. Fix the channel, then retry.',
        }),
    });
  };

  // Built each render: the cells carry what is gated and busy right now.
  const columns = deliveryColumns(zone, (d) =>
    d.state === 'DEAD' ? (
      <Button
        size="compact-xs"
        variant="default"
        disabled={!canWrite}
        loading={retry.isPending && retry.variables === d.seq}
        onClick={() => retryDelivery(d)}
        aria-label={`Retry delivery ${d.seq}`}
      >
        Retry
      </Button>
    ) : null,
  );

  return (
    <Drawer
      opened={channel !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={channel ? `Deliveries to ${channel.name}` : ''}
    >
      <Stack gap="sm">
        <Text size="xs" c="dimmed">
          The newest 100 deliveries. A delivery covers everything one rule changed in one evaluation, however many
          subjects.
        </Text>
        {canWrite ? null : <Text size="sm">Retrying a delivery needs the alert:write permission.</Text>}
        <DataTable
          variant="static"
          label="Deliveries"
          storageKey="alerting.deliveries"
          columns={columns}
          data={deliveries.data ?? []}
          rowKey={rowKey}
          loading={deliveries.isPending}
          error={
            deliveries.isError ? (
              <ErrorState error={deliveries.error} onRetry={() => void deliveries.refetch()} />
            ) : undefined
          }
          empty={
            <EmptyState
              kind="empty"
              title="Nothing has been sent to this channel yet"
              description="A delivery is queued when a rule bound to this channel fires or resolves; use “Send a test” on the channel to try it now."
            />
          }
        />
      </Stack>
    </Drawer>
  );
}
