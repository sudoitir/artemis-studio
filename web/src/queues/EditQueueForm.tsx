import { useState } from 'react';
import { Alert, Button, Group, Modal, NumberInput, Stack, Switch, Text, TextInput } from '@mantine/core';

import {
  useUpdateQueue,
  type LifecycleOutcomeView,
  type QueueView,
  type UpdateQueueRequest,
} from '../api/client.ts';
import { NodeOutcomeSummary } from '../shared/NodeOutcomeSummary.tsx';
import classes from './EditQueueForm.module.css';

/**
 * Change the configuration of a live queue.
 *
 * <p>Only the fields the broker will actually accept are offered. Routing type,
 * address, name and durability are fixed for the life of the queue and are shown
 * as such with the reason — presented as *immutable*, which is a different thing
 * from disabled: one can never change, the other is merely unavailable right now,
 * and an operator who cannot tell them apart will keep looking for the way to
 * enable it.
 *
 * <p>The filter <em>is</em> editable. That was verified against a live broker
 * rather than assumed; see ADR-0049 D7.
 */
export function EditQueueForm({
  clusterId,
  queue,
  opened,
  onClose,
}: {
  clusterId: string;
  queue: QueueView;
  opened: boolean;
  onClose: () => void;
}) {
  const [filter, setFilter] = useState('');
  const [maxConsumers, setMaxConsumers] = useState<number | ''>('');
  const [purgeOnNoConsumers, setPurge] = useState<boolean | null>(null);
  const [exclusive, setExclusive] = useState<boolean | null>(null);
  const [ringSize, setRingSize] = useState<number | ''>('');
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);

  const update = useUpdateQueue(clusterId, queue.queueName);

  // Only what the operator actually set is sent. The server reads the queue's
  // current configuration and merges this over it, because the broker's update
  // replaces rather than merges and would otherwise clear every omitted field.
  const body = (): UpdateQueueRequest => ({
    filter: filter.trim() === '' ? undefined : filter.trim(),
    maxConsumers: maxConsumers === '' ? undefined : Number(maxConsumers),
    purgeOnNoConsumers: purgeOnNoConsumers ?? undefined,
    exclusive: exclusive ?? undefined,
    nonDestructive: undefined,
    ringSize: ringSize === '' ? undefined : Number(ringSize),
  });

  const changed = Object.values(body()).some((v) => v !== undefined);

  const close = () => {
    setFilter('');
    setMaxConsumers('');
    setPurge(null);
    setExclusive(null);
    setRingSize('');
    setPreview(null);
    setResult(null);
    update.reset();
    onClose();
  };

  const submit = (dryRun: boolean) =>
    update.mutate(
      { body: body(), dryRun },
      { onSuccess: (o) => (o.dryRun ? setPreview(o) : setResult(o)) },
    );

  return (
    <Modal opened={opened} onClose={close} title={`Edit ${queue.queueName}`} size="lg">
      <Stack gap="md">
        {/* Immutable, not disabled — stated as facts about the queue rather than
            as inputs that happen to be switched off. */}
        <dl className={classes.fixed}>
          <div className={classes.row}>
            <dt className={classes.term}>Address</dt>
            <dd className={classes.value}>{queue.address}</dd>
          </div>
          <div className={classes.row}>
            <dt className={classes.term}>Routing type</dt>
            <dd className={classes.value}>{queue.routingType}</dd>
          </div>
          <div className={classes.row}>
            <dt className={classes.term}>Durable</dt>
            <dd className={classes.value}>{queue.durable ? 'yes' : 'no'}</dd>
          </div>
        </dl>
        <Text size="xs" c="dimmed">
          These are fixed for the life of the queue — the broker refuses to change them on a queue
          that exists. To change one, delete this queue and create a new one.
        </Text>

        <TextInput
          label="Filter"
          description="A JMS selector. Leave blank to keep the current filter; the queue's existing filter is preserved unless you set one here."
          placeholder="colour = 'red'"
          value={filter}
          onChange={(e) => setFilter(e.currentTarget.value)}
        />
        <NumberInput
          label="Max consumers"
          description="Leave blank to keep the current value. -1 for unlimited."
          value={maxConsumers}
          onChange={(v) => setMaxConsumers(v === '' ? '' : Number(v))}
          allowDecimal={false}
          min={-1}
        />
        <NumberInput
          label="Ring size"
          description="Leave blank to keep the current value. -1 for unlimited."
          value={ringSize}
          onChange={(v) => setRingSize(v === '' ? '' : Number(v))}
          allowDecimal={false}
          min={-1}
        />
        <Switch
          label="Purge when the last consumer disconnects"
          checked={purgeOnNoConsumers ?? false}
          onChange={(e) => setPurge(e.currentTarget.checked)}
        />
        <Switch
          label="Exclusive"
          description="Route to one consumer at a time."
          checked={exclusive ?? false}
          onChange={(e) => setExclusive(e.currentTarget.checked)}
        />

        {update.isError ? (
          <Alert color="red" variant="light" title={update.error.title} role="alert">
            {update.error.message}
          </Alert>
        ) : null}

        <div aria-live="polite">
          {preview ? <NodeOutcomeSummary outcome={preview} /> : null}
          {result ? <NodeOutcomeSummary outcome={result} /> : null}
        </div>

        <Group justify="space-between">
          <Text size="xs" c="dimmed">
            {changed ? '' : 'Change at least one field to apply an update.'}
          </Text>
          <Group gap="xs">
            <Button variant="default" size="xs" onClick={close}>
              {result ? 'Close' : 'Cancel'}
            </Button>
            <Button
              variant="default"
              size="xs"
              loading={update.isPending && update.variables?.dryRun === true}
              disabled={!changed}
              onClick={() => submit(true)}
            >
              Preview
            </Button>
            <Button
              size="xs"
              loading={update.isPending && update.variables?.dryRun !== true}
              disabled={!changed || result !== null}
              onClick={() => submit(false)}
            >
              Apply
            </Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
