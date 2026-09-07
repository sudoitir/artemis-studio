import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  NumberInput,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useParams } from '@tanstack/react-router';

import {
  useCreateIndexSubscription,
  useDeleteIndexSubscription,
  useIndexSubscriptions,
  useUpdateIndexSubscription,
  type SqlIndexSubscriptionView,
} from '../api/client.ts';
import { useCan } from '../auth/useCan.ts';
import { ConfirmByTyping } from '../shared/ConfirmByTyping.tsx';
import classes from './IndexSubscriptions.module.css';

function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let scaled = value / 1024;
  let unit = 0;
  while (scaled >= 1024 && unit < units.length - 1) {
    scaled /= 1024;
    unit += 1;
  }
  return `${scaled.toFixed(scaled < 10 ? 1 : 0)} ${units[unit]}`;
}

function when(iso?: string | null): string {
  if (!iso) return '—';
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? '—' : at.toLocaleString();
}

/**
 * The form that starts storing message payload.
 *
 * <p>What will be kept, and for how long, is stated on the form itself rather than
 * in documentation. An operator cannot consent to storing bodies they were never
 * told were being stored, and this is the last screen before it starts.
 */
function CreateSubscription({ clusterId, canWrite }: { clusterId: string; canWrite: boolean }) {
  const create = useCreateIndexSubscription(clusterId);
  const [pattern, setPattern] = useState('');
  const [retentionDays, setRetentionDays] = useState(7);
  const [intervalMs, setIntervalMs] = useState(5000);

  return (
    <Stack gap="xs" maw={520}>
      <TextInput
        label="Queue or pattern"
        description="Artemis wildcards: * is one level, # is many. ORDER.# captures every ORDER queue."
        placeholder="ORDER.IN"
        value={pattern}
        onChange={(e) => setPattern(e.currentTarget.value)}
        size="xs"
      />
      <Group grow>
        <NumberInput
          label="Keep for (days)"
          min={1}
          max={90}
          value={retentionDays}
          onChange={(v) => setRetentionDays(typeof v === 'number' ? v : 7)}
          size="xs"
        />
        <NumberInput
          label="Read every (ms)"
          min={1000}
          step={1000}
          value={intervalMs}
          onChange={(v) => setIntervalMs(typeof v === 'number' ? v : 5000)}
          size="xs"
        />
      </Group>
      <Alert color="yellow" variant="light" title="This stores message bodies">
        <Text size="sm">
          Studio will keep a copy of every message it observes on{' '}
          {pattern.trim() || 'these queues'} — headers, application properties and the body — in
          its own database for {retentionDays} day{retentionDays === 1 ? '' : 's'}, and then delete
          it. That copy is searchable by anyone who can read messages on this cluster. Capture is
          sampled, so it records what was seen, not everything that passed through.
        </Text>
      </Alert>
      <Group>
        <Button
          size="xs"
          disabled={!canWrite || pattern.trim().length === 0}
          loading={create.isPending}
          onClick={() =>
            create.mutate(
              { queuePattern: pattern.trim(), retentionDays, intervalMs, enabled: true },
              {
                onSuccess: () => {
                  notifications.show({ message: `Now indexing ${pattern.trim()}` });
                  setPattern('');
                },
                onError: (err) => notifications.show({ color: 'red', message: err.message }),
              },
            )
          }
        >
          Start indexing
        </Button>
        {!canWrite ? (
          <Text size="xs" c="dimmed">
            Creating a subscription needs the settings write permission.
          </Text>
        ) : null}
      </Group>
    </Stack>
  );
}

/** One subscription: what it holds, whether it is capturing, and how to be rid of it. */
function SubscriptionRow({
  clusterId,
  subscription,
  canWrite,
}: {
  clusterId: string;
  subscription: SqlIndexSubscriptionView;
  canWrite: boolean;
}) {
  const update = useUpdateIndexSubscription(clusterId);
  const remove = useDeleteIndexSubscription(clusterId);
  const [confirming, setConfirming] = useState(false);

  const id = subscription.id ?? '';
  const pattern = subscription.queuePattern ?? '';
  const held = subscription.messagesHeld ?? 0;
  const retention = subscription.retentionDays ?? 0;

  return (
    <Table.Tr>
      <Table.Td>
        <Stack gap={2}>
          <Text size="sm" fw={600}>
            {pattern}
          </Text>
          <Text size="xs" c="dimmed">
            capturing since {when(subscription.captureFrom)}
            {subscription.createdBy ? ` · created by ${subscription.createdBy}` : ''}
          </Text>
        </Stack>
      </Table.Td>
      <Table.Td>
        <Stack gap={2}>
          <Text size="sm" className={classes.numeric}>
            {held.toLocaleString()} message{held === 1 ? '' : 's'}
          </Text>
          <Text size="xs" c="dimmed" className={classes.numeric}>
            {bytes(subscription.bytesHeld ?? 0)} of payload
            {subscription.oldestObservedAt ? ` · oldest ${when(subscription.oldestObservedAt)}` : ''}
          </Text>
        </Stack>
      </Table.Td>
      <Table.Td>
        <Badge size="sm" variant="light" color="gray">
          {retention} day{retention === 1 ? '' : 's'}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Switch
          size="xs"
          label={subscription.enabled ? 'Capturing' : 'Paused'}
          checked={subscription.enabled ?? false}
          disabled={!canWrite || update.isPending}
          onChange={(e) => update.mutate({ id, body: { enabled: e.currentTarget.checked } })}
        />
      </Table.Td>
      <Table.Td>
        {confirming ? (
          <Stack gap={4}>
            {/* The blast radius, stated before the action can be armed: this
                destroys captured payload that cannot be observed again. */}
            <Text size="xs">
              This deletes the subscription and the {held.toLocaleString()} captured message
              {held === 1 ? '' : 's'} it holds. They cannot be recovered — a consumed message
              cannot be observed a second time.
            </Text>
            <ConfirmByTyping
              token={pattern}
              confirmLabel="Delete and destroy captured messages"
              loading={remove.isPending}
              onConfirm={() =>
                remove.mutate(id, {
                  onSuccess: (result) => {
                    notifications.show({
                      message: `Deleted ${pattern} — ${result.messagesDestroyed.toLocaleString()} captured messages destroyed`,
                    });
                    setConfirming(false);
                  },
                  onError: (err) => notifications.show({ color: 'red', message: err.message }),
                })
              }
            />
            <Button size="compact-xs" variant="subtle" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
          </Stack>
        ) : (
          <Button
            size="compact-xs"
            color="red"
            variant="light"
            disabled={!canWrite}
            onClick={() => setConfirming(true)}
          >
            Delete
          </Button>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

/**
 * Index subscriptions for this cluster (ADR-0059). The index is opt-in, per queue,
 * retention-bounded and disposable, and this screen is where all four of those are
 * true or not.
 */
export function IndexSubscriptions() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const subscriptions = useIndexSubscriptions(clusterId);
  const { can, loading } = useCan();
  // While grants are still loading the control is offered: refusing before the
  // answer has arrived is a claim that was never checked.
  const canWrite = loading || can('settings:write', clusterId);

  if (subscriptions.isError) {
    return (
      <Alert color="red" variant="light" title={subscriptions.error.title}>
        {subscriptions.error.message}
      </Alert>
    );
  }
  if (subscriptions.isPending) {
    return <Loader size="sm" />;
  }

  const rows = subscriptions.data ?? [];

  return (
    <Stack gap="md">
      {rows.length === 0 ? (
        <Alert color="gray" variant="light" title="Nothing is being indexed">
          No queue on this cluster is captured, so the SQL Console answers every query from the
          live brokers and cannot find a message that has already been consumed. Index a queue
          below to change that — it stores message payload, so it is a deliberate choice rather
          than a default.
        </Alert>
      ) : (
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Queues</Table.Th>
              <Table.Th>Held</Table.Th>
              <Table.Th>Retention</Table.Th>
              <Table.Th>State</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((subscription) => (
              <SubscriptionRow
                key={subscription.id}
                clusterId={clusterId}
                subscription={subscription}
                canWrite={canWrite}
              />
            ))}
          </Table.Tbody>
        </Table>
      )}

      <CreateSubscription clusterId={clusterId} canWrite={canWrite} />
    </Stack>
  );
}
