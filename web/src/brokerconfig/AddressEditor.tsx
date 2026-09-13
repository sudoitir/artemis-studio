import { useEffect, useRef, useState } from 'react';
import { ActionIcon, Button, Checkbox, Group, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { IconX } from '@tabler/icons-react';

import type { ConfigAddressView, ConfigDeclarationView, ConfigQueueView } from '../api/client.ts';
import { keyTaken, removeItem, upsertAddress } from './document.ts';
import { EditorDrawer } from './EditorDrawer.tsx';
import { useSaveDocument } from './useSaveDocument.ts';

type RoutingType = 'ANYCAST' | 'MULTICAST';

interface Errors {
  name?: string;
  routingTypes?: string;
  queues?: string;
}

/**
 * Edit one declared address and the queues bound to it. Declaring a queue
 * creates it where it is missing; nothing here ever deletes one — removing a
 * queue from the declaration stops Studio checking for it and no more
 * (ADR-0067 D6). The editor says so where the operator would expect a delete.
 */
export function AddressEditor({
  declaration,
  item,
  opened,
  onClose,
}: {
  declaration: ConfigDeclarationView;
  item: ConfigAddressView | null;
  opened: boolean;
  onClose: () => void;
}) {
  const [name, setName] = useState(item?.name ?? '');
  const [routingTypes, setRoutingTypes] = useState<RoutingType[]>(item?.routingTypes ?? ['ANYCAST']);
  const [queues, setQueues] = useState<ConfigQueueView[]>(item?.queues ?? []);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!opened) return;
    setName(item?.name ?? '');
    setRoutingTypes(item?.routingTypes ?? ['ANYCAST']);
    setQueues(item?.queues ?? []);
    setTouched({});
    setSubmitted(false);
  }, [opened, item]);

  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  const validate = (): Errors => {
    const errors: Errors = {};
    const n = name.trim();
    if (!n) errors.name = 'An address name is required.';
    else if (keyTaken(declaration.document.addresses, (i) => i.name, n, item?.name)) {
      errors.name = `"${n}" is already declared. Edit that address instead.`;
    }
    if (routingTypes.length === 0) errors.routingTypes = 'An address has at least one routing type.';
    const names = queues.map((q) => q.name.trim());
    if (names.some((q) => !q)) errors.queues = 'Every queue needs a name.';
    else if (new Set(names).size !== names.length) errors.queues = 'Queue names must be unique on an address.';
    else if (queues.some((q) => !routingTypes.includes(q.routingType))) {
      errors.queues = "A queue's routing type must be one the address supports.";
    }
    return errors;
  };
  const errors = validate();
  const errorFor = (field: keyof Errors) => (touched[field] || submitted ? errors[field] : undefined);

  const submit = () => {
    setSubmitted(true);
    if (Object.keys(errors).length > 0) {
      nameRef.current?.focus();
      return;
    }
    const next: ConfigAddressView = {
      name: name.trim(),
      routingTypes,
      queues: queues.map((q) => ({ ...q, name: q.name.trim(), filter: q.filter?.trim() || null })),
    };
    save(upsertAddress(declaration.document, next, item?.name), `${item ? 'Edited' : 'Added'} address ${next.name}`);
  };

  const remove = () =>
    save(removeItem(declaration.document, 'addresses', item!.name), `Removed address ${item!.name} from the declaration`);

  const setQueue = (index: number, patch: Partial<ConfigQueueView>) =>
    setQueues((qs) => qs.map((q, i) => (i === index ? { ...q, ...patch } : q)));

  return (
    <EditorDrawer
      opened={opened}
      onClose={() => {
        reset();
        onClose();
      }}
      title={item ? `Address ${item.name}` : 'New address'}
      error={error}
      submitting={isPending}
      submitLabel={`Save as revision ${declaration.revision + 1}`}
      onSubmit={submit}
      hint={submitted && Object.keys(errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button
            variant="subtle"
            color="red"
            size="xs"
            onClick={remove}
            loading={isPending}
            title="Stops Studio checking for it. Nothing on the broker is deleted."
          >
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput
        ref={nameRef}
        label="Address"
        value={name}
        onChange={(e) => setName(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, name: true }))}
        error={errorFor('name')}
        required
      />
      <Checkbox.Group
        label="Routing types"
        description="ANYCAST delivers each message to one consumer; MULTICAST to every subscriber."
        value={routingTypes}
        onChange={(v) => setRoutingTypes(v as RoutingType[])}
        error={errorFor('routingTypes')}
      >
        <Group gap="lg" mt="xs">
          <Checkbox value="ANYCAST" label="Anycast" />
          <Checkbox value="MULTICAST" label="Multicast" />
        </Group>
      </Checkbox.Group>

      <Stack gap="xs">
        <Group justify="space-between">
          <Text size="sm" fw={600}>
            Queues
          </Text>
          <Button
            variant="default"
            size="xs"
            onClick={() =>
              setQueues((qs) => [...qs, { name: '', routingType: routingTypes[0] ?? 'ANYCAST', durable: true }])
            }
          >
            Add queue
          </Button>
        </Group>
        {errorFor('queues') ? (
          <Text size="xs" c="var(--as-danger)">
            {errorFor('queues')}
          </Text>
        ) : null}
        {queues.length === 0 ? (
          <Text size="xs" c="dimmed">
            No queues declared on this address. An apply creates the address alone.
          </Text>
        ) : null}
        {queues.map((q, i) => (
          <Group key={i} align="flex-end" gap="xs" wrap="nowrap">
            <TextInput
              label="Queue name"
              value={q.name}
              onChange={(e) => setQueue(i, { name: e.currentTarget.value })}
              style={{ flex: 1 }}
            />
            <Select
              label="Routing"
              data={routingTypes.map((t) => ({ value: t, label: t }))}
              value={q.routingType}
              onChange={(v) => setQueue(i, { routingType: (v ?? 'ANYCAST') as RoutingType })}
              allowDeselect={false}
              w={130}
            />
            <Switch
              label="Durable"
              checked={q.durable}
              onChange={(e) => setQueue(i, { durable: e.currentTarget.checked })}
              mb={6}
            />
            <ActionIcon
              variant="subtle"
              aria-label={`Remove queue ${q.name || i + 1} from the declaration`}
              title="Stops Studio checking for it. Nothing on the broker is deleted."
              onClick={() => setQueues((qs) => qs.filter((_, j) => j !== i))}
              mb={4}
            >
              <IconX size={14} />
            </ActionIcon>
          </Group>
        ))}
        <Text size="xs" c="dimmed">
          A declared queue is created where missing. One that exists with a different configuration is reported, never
          changed — edit it from the Queues view. Nothing here deletes a queue.
        </Text>
      </Stack>
    </EditorDrawer>
  );
}
