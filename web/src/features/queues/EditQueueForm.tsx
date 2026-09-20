import { useEffect, useState } from 'react';
import { Alert, Button, Group, Modal, NumberInput, Skeleton, Stack, Switch, Text, TextInput } from '@mantine/core';

import {
  useQueueConfiguration,
  useUpdateQueue,
  type LifecycleOutcomeView,
  type QueueConfiguration,
  type QueueView,
  type UpdateQueueRequest,
} from './api.ts';
import { NodeOutcomeSummary } from '../../ui/NodeOutcomeSummary.tsx';
import classes from './EditQueueForm.module.css';

/**
 * The configuration the nodes agree on, or the first node's when they differ —
 * the form says so above the fields, because seeding from one node silently is
 * how an operator applies one node's value to all of them without knowing.
 */
function currentValues(config: QueueConfiguration | undefined): Record<string, unknown> | undefined {
  return config?.nodes?.find((n) => !n.unavailableReason)?.values;
}

/** A configuration value as the text input wants it. */
function str(value: unknown): string {
  return value === null || value === undefined ? '' : String(value);
}

/** A configuration value as the number input wants it. */
function num(value: unknown): number | '' {
  return typeof value === 'number' ? value : '';
}

/**
 * Change the configuration of a live queue.
 *
 * <p>The form opens on what the queue runs now, read from the nodes that have it,
 * and sends only the fields the operator changed. It used to open blank under
 * "leave blank to keep the current value", which could not say what the queue ran
 * and — reopened after an update — could not say whether the update had taken.
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
  const [purgeOnNoConsumers, setPurge] = useState(false);
  const [exclusive, setExclusive] = useState(false);
  const [ringSize, setRingSize] = useState<number | ''>('');
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);

  const update = useUpdateQueue(clusterId, queue.queueName);
  // What the queue runs right now, per node. The form is seeded from it, so the
  // operator edits the real configuration rather than guessing at blank inputs —
  // and after an update the reopened form shows what was written.
  const configuration = useQueueConfiguration(clusterId, queue.queueName, opened);
  const current = currentValues(configuration.data);
  const seeded = configuration.data?.nodes?.find((n) => !n.unavailableReason);
  const seededFrom = seeded?.nodeName ?? '';
  // Every other node whose configuration differs from the one the form shows.
  const disagreeing = (configuration.data?.nodes ?? [])
    .filter((n) => !n.unavailableReason && n.nodeId !== seeded?.nodeId)
    .filter((n) => JSON.stringify(n.values) !== JSON.stringify(seeded?.values))
    .map((n) => n.nodeName);

  useEffect(() => {
    if (!current) return;
    setFilter(str(current['filter-string']));
    setMaxConsumers(num(current['max-consumers']));
    setPurge(current['purge-on-no-consumers'] === true);
    setExclusive(current['exclusive'] === true);
    setRingSize(num(current['ring-size']));
    // Seeding is keyed by the values themselves, so an applied update reseeds the
    // form from what the broker now reports rather than from the operator's typing.
  }, [configuration.dataUpdatedAt, configuration.data]); // eslint-disable-line react-hooks/exhaustive-deps

  // Only what the operator actually changed is sent. The server reads the queue's
  // current configuration and merges this over it, because the broker's update
  // replaces rather than merges and would otherwise clear every omitted field.
  const body = (): UpdateQueueRequest => ({
    filter: current && str(current['filter-string']) === filter.trim() ? undefined : filter.trim(),
    maxConsumers:
      maxConsumers === '' || (current && num(current['max-consumers']) === maxConsumers)
        ? undefined
        : Number(maxConsumers),
    purgeOnNoConsumers:
      current && (current['purge-on-no-consumers'] === true) === purgeOnNoConsumers ? undefined : purgeOnNoConsumers,
    exclusive: current && (current['exclusive'] === true) === exclusive ? undefined : exclusive,
    nonDestructive: undefined,
    ringSize: ringSize === '' || (current && num(current['ring-size']) === ringSize) ? undefined : Number(ringSize),
  });

  const changed = Object.values(body()).some((v) => v !== undefined);

  const close = () => {
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

        {configuration.isError ? (
          <Alert color="yellow" variant="light" title="Could not read what this queue runs" role="alert">
            {configuration.error.message} The fields below start empty; applying one writes it to every live node.
          </Alert>
        ) : null}
        {disagreeing.length > 0 ? (
          <Alert color="yellow" variant="light" title="The nodes do not agree">
            {disagreeing.join(', ')} {disagreeing.length === 1 ? 'runs' : 'run'} a different configuration from{' '}
            {seededFrom}. The fields below show {seededFrom}; applying writes them to every live node.
          </Alert>
        ) : null}

        {configuration.isPending ? (
          <Skeleton height={180} />
        ) : (
          <>
            <TextInput
              label="Filter"
              description="A JMS selector. Empty means no filter."
              placeholder="colour = 'red'"
              value={filter}
              onChange={(e) => setFilter(e.currentTarget.value)}
            />
            <NumberInput
              label="Max consumers"
              description="-1 for unlimited."
              value={maxConsumers}
              onChange={(v) => setMaxConsumers(v === '' ? '' : Number(v))}
              allowDecimal={false}
              min={-1}
            />
            <NumberInput
              label="Ring size"
              description="-1 for unlimited."
              value={ringSize}
              onChange={(v) => setRingSize(v === '' ? '' : Number(v))}
              allowDecimal={false}
              min={-1}
            />
            <Switch
              label="Purge when the last consumer disconnects"
              checked={purgeOnNoConsumers}
              onChange={(e) => setPurge(e.currentTarget.checked)}
            />
            <Switch
              label="Exclusive"
              description="Route to one consumer at a time."
              checked={exclusive}
              onChange={(e) => setExclusive(e.currentTarget.checked)}
            />
          </>
        )}

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
