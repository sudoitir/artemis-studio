import { Button, Drawer, Stack, Table, Text } from '@mantine/core';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { useChannelDeliveries, useRetryDelivery, type NotificationChannelView } from './api.ts';
import { deliveryState } from './channelKinds.ts';

/**
 * A channel's delivery log, newest first (ADR-0105): what was sent, how many attempts it
 * took, and why the last one failed. A failed delivery can be put back on the queue once
 * the channel is fixed.
 */
export function DeliveryLog({
  channel,
  onClose,
  canWrite,
  announce,
}: {
  channel: NotificationChannelView | null;
  onClose: () => void;
  canWrite: boolean;
  announce: (message: string) => void;
}) {
  const deliveries = useChannelDeliveries(channel?.id ?? null);
  const retry = useRetryDelivery(channel?.id ?? '');

  return (
    <Drawer
      opened={channel !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={channel ? `Deliveries to ${channel.name}` : ''}
    >
      {deliveries.isPending ? (
        <Text size="sm" c="dimmed">
          Loading deliveries…
        </Text>
      ) : deliveries.isError ? (
        <Text size="sm" c="red">
          The delivery log could not be loaded: {deliveries.error.message}
        </Text>
      ) : (deliveries.data ?? []).length === 0 ? (
        <Text size="sm" c="dimmed">
          Nothing has been sent to this channel yet. A delivery is queued when a rule bound to it fires or resolves;
          use “Send a test” to try it now.
        </Text>
      ) : (
        <Stack gap="xs">
          <Text size="xs" c="dimmed">
            The newest 100 deliveries. A delivery covers everything one rule changed in one evaluation, however many
            subjects.
          </Text>
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Queued</Table.Th>
                <Table.Th>Notification</Table.Th>
                <Table.Th>State</Table.Th>
                <Table.Th ta="end">Attempts</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(deliveries.data ?? []).map((d) => (
                <Table.Tr key={d.seq}>
                  <Table.Td style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                    {absoluteLabel(d.createdAt)}
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{d.summary}</Text>
                    {d.lastError ? (
                      <Text size="xs" c={d.state === 'SENT' ? 'dimmed' : 'red'}>
                        {d.state === 'SENT' ? 'Earlier attempt: ' : 'Last error: '}
                        {d.lastError}
                      </Text>
                    ) : null}
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c={d.state === 'DEAD' ? 'red' : undefined}>
                      {deliveryState(d.state)}
                    </Text>
                    <Text size="xs" c="dimmed" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {d.state === 'SENT'
                        ? absoluteLabel(d.deliveredAt)
                        : d.state === 'PENDING'
                          ? `next ${absoluteLabel(d.nextAttemptAt)}`
                          : 'gave up'}
                    </Text>
                  </Table.Td>
                  <Table.Td ta="end" style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {d.attempts}
                  </Table.Td>
                  <Table.Td>
                    {d.state === 'DEAD' ? (
                      <Button
                        size="compact-xs"
                        variant="default"
                        disabled={!canWrite}
                        title={canWrite ? undefined : 'Retrying needs alert:write'}
                        loading={retry.isPending && retry.variables === d.seq}
                        onClick={() =>
                          retry.mutate(d.seq, {
                            onSuccess: () => announce(`Delivery ${d.seq} queued again.`),
                            onError: (e) => announce(`Delivery ${d.seq} was not queued: ${e.message}`),
                          })
                        }
                        aria-label={`Retry delivery ${d.seq}`}
                      >
                        Retry
                      </Button>
                    ) : null}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      )}
    </Drawer>
  );
}
