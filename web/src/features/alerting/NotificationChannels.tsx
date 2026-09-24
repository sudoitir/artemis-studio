import { useState } from 'react';
import { ActionIcon, Button, Group, Modal, Stack, Table, Text, Tooltip } from '@mantine/core';
import { IconHistory, IconPencil, IconSend, IconTrash } from '@tabler/icons-react';

import { useCan } from '../../kernel/auth/useCan.ts';
import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import {
  useDeleteNotificationChannel,
  useNotificationChannels,
  useTestNotificationChannel,
  type ChannelTestResultView,
  type NotificationChannelView,
} from './api.ts';
import { ChannelEditor, TestOutcome } from './ChannelEditor.tsx';
import { deliveryState, destination, kindLabel } from './channelKinds.ts';
import { DeliveryLog } from './DeliveryLog.tsx';

/**
 * Global notification channels — Slack, Microsoft Teams, PagerDuty, email and signed
 * webhooks (alerting spec, ADR-0036, ADR-0105). Each row states where it delivers, how
 * many rules route to it, and how its last delivery went, so a channel that has been
 * failing is visible without opening it. Secrets are write-only and never rendered.
 */
export function NotificationChannels() {
  const channels = useNotificationChannels();
  const test = useTestNotificationChannel();
  const { can, loading: grantsLoading } = useCan();
  // While grants load, offer the control; the server is the enforcement point.
  const canWrite = grantsLoading || can('alert:write');
  const now = useServerNow(30_000);

  const [editing, setEditing] = useState<NotificationChannelView | null>(null);
  const [creating, setCreating] = useState(false);
  const [logFor, setLogFor] = useState<NotificationChannelView | null>(null);
  const [deleting, setDeleting] = useState<NotificationChannelView | null>(null);
  const [tested, setTested] = useState<{ channel: NotificationChannelView; result: ChannelTestResultView } | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const writeReason = 'Changing channels needs the alert:write permission.';

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Bind a channel to a rule from the rule form. A rule with no channel still fires and records history.
        </Text>
        <Tooltip label={writeReason} disabled={canWrite}>
          <span>
            <Button onClick={() => setCreating(true)} disabled={!canWrite}>
              Add channel
            </Button>
          </span>
        </Tooltip>
      </Group>

      <div role="status" aria-live="polite">
        <Text size="sm">{announcement}</Text>
      </div>

      {tested ? (
        <Stack gap={4}>
          <Text size="sm" fw={600}>
            Test of {tested.channel.name}
          </Text>
          <TestOutcome result={tested.result} kind={tested.channel.kind} />
        </Stack>
      ) : null}

      {channels.isPending ? (
        <Text size="sm" c="dimmed">
          Loading channels…
        </Text>
      ) : channels.isError ? (
        <Text size="sm" c="red">
          Channels could not be loaded: {channels.error.message}. Reload the page; if it persists, check that Studio can
          reach its database.
        </Text>
      ) : (channels.data ?? []).length === 0 ? (
        <Text size="sm" c="dimmed">
          No notification channels configured — alert rules can still fire and record history, they just won’t deliver
          anywhere until a channel is bound. Add Slack, Microsoft Teams, PagerDuty, email or a signed webhook.
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Name</Table.Th>
              <Table.Th>Kind</Table.Th>
              <Table.Th>Delivers to</Table.Th>
              <Table.Th ta="end">Rules</Table.Th>
              <Table.Th>Last delivery</Table.Th>
              <Table.Th>State</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(channels.data ?? []).map((c) => {
              const h = c.health;
              const failing = h?.lastState === 'DEAD';
              return (
                <Table.Tr key={c.id}>
                  <Table.Td>
                    <Text size="sm" fw={500}>
                      {c.name}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{kindLabel(c.kind)}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {destination(c.kind, c.config)}
                      {c.hasSecret ? '' : c.kind === 'EMAIL' ? ' · no password' : ' · secret not set'}
                    </Text>
                  </Table.Td>
                  <Table.Td ta="end" style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {c.boundRuleCount}
                  </Table.Td>
                  <Table.Td>
                    {h ? (
                      <>
                        <Text size="sm" c={failing ? 'red' : undefined}>
                          {deliveryState(h.lastState)} {elapsedLabel(now - Date.parse(h.lastCreatedAt))} ago
                        </Text>
                        {failing && h.lastError ? (
                          <Text size="xs" c="red" lineClamp={2}>
                            {h.lastError}
                          </Text>
                        ) : null}
                        <Text size="xs" c="dimmed" style={{ fontVariantNumeric: 'tabular-nums' }}>
                          24h: {h.sentLast24h} sent, {h.failedLast24h} failed
                          {h.pending > 0 ? `, ${h.pending} waiting` : ''}
                        </Text>
                      </>
                    ) : (
                      <Text size="xs" c="dimmed">
                        never used
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{c.enabled ? 'enabled' : 'disabled'}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <ActionIcon
                        variant="subtle"
                        onClick={() =>
                          test.mutate(c.id, {
                            onSuccess: (result) => {
                              setTested({ channel: c, result });
                              setAnnouncement(
                                result.delivered
                                  ? `Test delivered to ${c.name}.`
                                  : `Test to ${c.name} not delivered: ${result.error ?? 'no reason given'}.`,
                              );
                            },
                            onError: (e) => setAnnouncement(`Test to ${c.name} could not be sent: ${e.message}`),
                          })
                        }
                        loading={test.isPending && test.variables === c.id}
                        disabled={!canWrite || (test.isPending && test.variables !== c.id)}
                        aria-label={`Send test notification to ${c.name}`}
                        title={canWrite ? undefined : writeReason}
                      >
                        <IconSend size={16} />
                      </ActionIcon>
                      <ActionIcon variant="subtle" onClick={() => setLogFor(c)} aria-label={`Delivery log of ${c.name}`}>
                        <IconHistory size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        onClick={() => setEditing(c)}
                        disabled={!canWrite}
                        aria-label={`Edit ${c.name}`}
                        title={canWrite ? undefined : writeReason}
                      >
                        <IconPencil size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="red"
                        onClick={() => setDeleting(c)}
                        disabled={!canWrite}
                        aria-label={`Delete ${c.name}`}
                        title={canWrite ? undefined : writeReason}
                      >
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      )}

      <ChannelEditor
        opened={creating || editing !== null}
        channel={editing}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSaved={setAnnouncement}
      />
      <DeliveryLog channel={logFor} onClose={() => setLogFor(null)} canWrite={canWrite} announce={setAnnouncement} />
      <DeleteChannel channel={deleting} onClose={() => setDeleting(null)} announce={setAnnouncement} />
    </Stack>
  );
}

/** States what deleting silences before it can be armed, then asks for the name. */
function DeleteChannel({
  channel,
  onClose,
  announce,
}: {
  channel: NotificationChannelView | null;
  onClose: () => void;
  announce: (message: string) => void;
}) {
  const remove = useDeleteNotificationChannel();
  const [error, setError] = useState<string | null>(null);
  const bound = channel?.boundRuleCount ?? 0;

  return (
    <Modal opened={channel !== null} onClose={onClose} title={channel ? `Delete ${channel.name}?` : ''}>
      {channel ? (
        <Stack gap="sm">
          <Text size="sm">
            {bound === 0
              ? 'No rule routes to this channel, so no alert stops being delivered.'
              : `${bound} rule${bound === 1 ? '' : 's'} route${bound === 1 ? 's' : ''} to this channel and will stop delivering to it. Their other channels are unaffected; a rule left with none still fires and records history.`}
          </Text>
          <Text size="sm">Its delivery log is deleted with it. This cannot be undone.</Text>
          {error ? (
            <Text size="sm" c="red" role="alert">
              Not deleted: {error}
            </Text>
          ) : null}
          <ConfirmByTyping
            token={channel.name}
            confirmLabel="Delete channel"
            loading={remove.isPending}
            onConfirm={() =>
              remove.mutate(channel.id, {
                onSuccess: () => {
                  announce(`Deleted "${channel.name}".`);
                  setError(null);
                  onClose();
                },
                onError: (e) => setError(e.message),
              })
            }
          />
        </Stack>
      ) : null}
    </Modal>
  );
}
