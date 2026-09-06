import { useRef, useState } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Group,
  Modal,
  NumberInput,
  Radio,
  Stack,
  Switch,
  Text,
  TextInput,
} from '@mantine/core';

import { useCreateQueue, type CreateQueueRequest, type LifecycleOutcomeView } from '../api/client.ts';
import { NodeOutcomeSummary } from '../shared/NodeOutcomeSummary.tsx';
import { AddressPicker } from './AddressPicker.tsx';

/** Only the fields that identify the queue are required; the rest have broker defaults. */
interface FormState {
  address: string;
  name: string;
  routingType: 'ANYCAST' | 'MULTICAST';
  durable: boolean;
  filter: string;
  maxConsumers: number | '';
  purgeOnNoConsumers: boolean;
  exclusive: boolean;
  ringSize: number | '';
}

const EMPTY: FormState = {
  address: '',
  name: '',
  routingType: 'ANYCAST',
  durable: true,
  filter: '',
  maxConsumers: '',
  purgeOnNoConsumers: false,
  exclusive: false,
  ringSize: '',
};

type Errors = Partial<Record<'address' | 'name', string>>;

function validate(form: FormState): Errors {
  const errors: Errors = {};
  if (!form.address.trim()) errors.address = 'An address is required — a queue binds to one.';
  if (!form.name.trim()) errors.name = 'A queue name is required.';
  return errors;
}

/**
 * Create a queue across every live node of a cluster.
 *
 * <p>Previews before it acts: the operator sees which nodes will be targeted, in
 * the same summary component the result is rendered with, so the two are
 * comparable. Creation is not destructive, so there is no typed confirmation —
 * the preview is the safety step.
 */
export function CreateQueueForm({
  clusterId,
  opened,
  onClose,
}: {
  clusterId: string;
  opened: boolean;
  onClose: () => void;
}) {
  const [form, setForm] = useState<FormState>(EMPTY);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [preview, setPreview] = useState<LifecycleOutcomeView | null>(null);
  const [result, setResult] = useState<LifecycleOutcomeView | null>(null);
  const [advanced, setAdvanced] = useState(false);

  const addressRef = useRef<HTMLInputElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);

  const create = useCreateQueue(clusterId);
  const errors = validate(form);
  const hasErrors = Object.keys(errors).length > 0;

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    // A field the operator is fixing should stop complaining as they fix it.
    setPreview(null);
    setResult(null);
  };

  // Shown only once the field has been left, or once a submit was attempted —
  // never while the operator is still typing into an empty form.
  const errorFor = (field: keyof Errors) =>
    touched[field] || submitted ? errors[field] : undefined;

  const body = (): CreateQueueRequest => ({
    address: form.address.trim(),
    name: form.name.trim(),
    routingType: form.routingType,
    durable: form.durable,
    filter: form.filter.trim() || undefined,
    maxConsumers: form.maxConsumers === '' ? undefined : Number(form.maxConsumers),
    purgeOnNoConsumers: form.purgeOnNoConsumers,
    exclusive: form.exclusive,
    nonDestructive: undefined,
    ringSize: form.ringSize === '' ? undefined : Number(form.ringSize),
    autoCreateAddress: true,
  });

  /** Focus the first field the operator has to fix, rather than making them hunt. */
  const focusFirstInvalid = (found: Errors) => {
    if (found.address) addressRef.current?.focus();
    else if (found.name) nameRef.current?.focus();
  };

  const submit = (dryRun: boolean) => {
    setSubmitted(true);
    const found = validate(form);
    if (Object.keys(found).length > 0) {
      focusFirstInvalid(found);
      return;
    }
    create.mutate(
      { body: body(), dryRun },
      {
        onSuccess: (outcome) => {
          if (outcome.dryRun) setPreview(outcome);
          else setResult(outcome);
        },
      },
    );
  };

  const close = () => {
    setForm(EMPTY);
    setTouched({});
    setSubmitted(false);
    setPreview(null);
    setResult(null);
    setAdvanced(false);
    create.reset();
    onClose();
  };

  return (
    <Modal opened={opened} onClose={close} title="New queue" size="lg">
      <Stack gap="md">
        <AddressPicker
          clusterId={clusterId}
          value={form.address}
          onChange={(value) => set('address', value)}
          onBlur={() => setTouched((t) => ({ ...t, address: true }))}
          error={errorFor('address')}
          inputRef={addressRef}
          label="Address"
          description="Messages are sent to an address; the queue binds to it. An address that does not exist yet is created with the queue."
        />

        <TextInput
          ref={nameRef}
          label="Queue name"
          value={form.name}
          onChange={(e) => set('name', e.currentTarget.value)}
          onBlur={() => setTouched((t) => ({ ...t, name: true }))}
          error={errorFor('name')}
          required
        />

        <Radio.Group
          label="Routing type"
          description="ANYCAST delivers each message to one consumer; MULTICAST delivers to every subscriber. This cannot be changed once the queue exists."
          value={form.routingType}
          onChange={(value) => set('routingType', value as FormState['routingType'])}
        >
          <Group gap="lg" mt="xs">
            <Radio value="ANYCAST" label="Anycast" />
            <Radio value="MULTICAST" label="Multicast" />
          </Group>
        </Radio.Group>

        <Switch
          label="Durable"
          description="A durable queue and its messages survive a broker restart."
          checked={form.durable}
          onChange={(e) => set('durable', e.currentTarget.checked)}
        />

        <div>
          <Button
            variant="subtle"
            size="xs"
            px={0}
            onClick={() => setAdvanced((a) => !a)}
            aria-expanded={advanced}
          >
            {advanced ? 'Hide advanced configuration' : 'Advanced configuration'}
          </Button>
          <Collapse expanded={advanced}>
            <Stack gap="sm" mt="sm">
              <TextInput
                label="Filter"
                description="A JMS selector; only matching messages are accepted. Leave empty to accept all."
                placeholder="colour = 'red'"
                value={form.filter}
                onChange={(e) => set('filter', e.currentTarget.value)}
              />
              <NumberInput
                label="Max consumers"
                description="-1 for unlimited."
                value={form.maxConsumers}
                onChange={(v) => set('maxConsumers', v === '' ? '' : Number(v))}
                allowDecimal={false}
                min={-1}
              />
              <NumberInput
                label="Ring size"
                description="Retain at most this many messages. -1 for unlimited."
                value={form.ringSize}
                onChange={(v) => set('ringSize', v === '' ? '' : Number(v))}
                allowDecimal={false}
                min={-1}
              />
              <Switch
                label="Purge when the last consumer disconnects"
                checked={form.purgeOnNoConsumers}
                onChange={(e) => set('purgeOnNoConsumers', e.currentTarget.checked)}
              />
              <Switch
                label="Exclusive"
                description="Route to one consumer at a time."
                checked={form.exclusive}
                onChange={(e) => set('exclusive', e.currentTarget.checked)}
              />
            </Stack>
          </Collapse>
        </div>

        {create.isError ? (
          <Alert color="red" variant="light" title={create.error.title} role="alert">
            {create.error.message}
          </Alert>
        ) : null}

        {/* Preview and result share one live region, so a screen reader is told
            the outcome rather than only the sighted operator seeing it. */}
        <div aria-live="polite">
          {preview ? (
            <Stack gap="xs">
              <Text size="xs" c="dimmed">
                Nothing has been created yet. This is what would happen:
              </Text>
              <NodeOutcomeSummary outcome={preview} />
            </Stack>
          ) : null}
          {result ? <NodeOutcomeSummary outcome={result} /> : null}
        </div>

        <Group justify="space-between">
          {/* Never silently disabled: it stays enabled and validates on activation,
              so the operator finds out what is wrong by trying. */}
          <Text size="xs" c="dimmed">
            {hasErrors && submitted ? 'Fix the fields above to continue.' : ''}
          </Text>
          <Group gap="xs">
            <Button variant="default" size="xs" onClick={close}>
              {result ? 'Close' : 'Cancel'}
            </Button>
            <Button
              variant="default"
              size="xs"
              loading={create.isPending && create.variables?.dryRun === true}
              onClick={() => submit(true)}
            >
              Preview
            </Button>
            <Button
              size="xs"
              loading={create.isPending && create.variables?.dryRun !== true}
              disabled={result !== null}
              onClick={() => submit(false)}
            >
              Create queue
            </Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
