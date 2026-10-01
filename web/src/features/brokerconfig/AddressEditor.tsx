import { useEffect, useRef } from 'react';
import { ActionIcon, Button, Checkbox, Group, Select, Switch, Text, TextInput } from '@mantine/core';
import { useForm, type UseFormReturnType } from '@mantine/form';
import { IconX } from '@tabler/icons-react';

import type { ConfigAddressView, ConfigDeclarationView, ConfigQueueView } from './api.ts';
import { keyTaken, removeItem, upsertAddress } from './document.ts';
import { FieldRow } from '../../ui/FieldRow.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { Section } from '../../ui/Section.tsx';
import { EditorDrawer } from './EditorDrawer.tsx';
import { useReseedOnOpen } from './useReseedOnOpen.ts';
import { useSaveDocument } from './useSaveDocument.ts';

type RoutingType = 'ANYCAST' | 'MULTICAST';

/** A queue being edited; the row key identifies it across edits and removals and is never saved. */
type QueueRow = { rowKey: number; queue: ConfigQueueView };

let nextRowKey = 0;
const newRow = (queue: ConfigQueueView): QueueRow => ({ rowKey: nextRowKey++, queue });
const toRows = (queues: ConfigQueueView[]): QueueRow[] => queues.map(newRow);

interface FormState {
  name: string;
  routingTypes: RoutingType[];
  queues: QueueRow[];
}

const seedOf = (source: Partial<ConfigAddressView> | null): FormState => ({
  name: source?.name ?? '',
  routingTypes: source?.routingTypes ?? ['ANYCAST'],
  queues: toRows(source?.queues ?? []),
});

/**
 * Edit one declared address and the queues bound to it. Declaring a queue
 * creates it where it is missing; nothing here ever deletes one — removing a
 * queue from the declaration stops Studio checking for it and no more
 * (ADR-0067 D6). The editor says so where the operator would expect a delete.
 */
export function AddressEditor({
  declaration,
  item,
  prefill,
  opened,
  onClose,
}: Readonly<{
  declaration: ConfigDeclarationView;
  item: ConfigAddressView | null;
  /** What a new address opens with — the routing builder's "Add queue" opens one with a queue to name. */
  prefill?: Partial<ConfigAddressView>;
  opened: boolean;
  onClose: () => void;
}>) {
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);
  const source = item ?? prefill ?? null;

  // Once a save was refused, every change checks the whole form again, so a message goes the moment its
  // cause does, including for a queue added or removed (which no single field's change covers).
  const refused = useRef(false);
  const form: UseFormReturnType<FormState> = useForm<FormState>({
    initialValues: seedOf(source),
    validateInputOnBlur: true,
    validateInputOnChange: true,
    onValuesChange: () => {
      if (refused.current) form.validate();
    },
    // Each message sits beside the field it is about, a queue's on that queue's row.
    validate: ({ name, routingTypes, queues }) => {
      const errors: Record<string, string> = {};
      const n = name.trim();
      if (!n) errors.name = 'An address name is required.';
      else if (keyTaken(declaration.document.addresses, (i) => i.name, n, item?.name)) {
        errors.name = `"${n}" is already declared. Edit that address instead.`;
      }
      if (routingTypes.length === 0) errors.routingTypes = 'An address has at least one routing type.';
      const seen = new Set<string>();
      queues.forEach(({ queue }, i) => {
        const q = queue.name.trim();
        if (!q) errors[`queues.${i}.queue.name`] = 'Every queue needs a name.';
        else if (seen.has(q)) errors[`queues.${i}.queue.name`] = 'Queue names must be unique on an address.';
        else if (!routingTypes.includes(queue.routingType)) {
          errors[`queues.${i}.queue.routingType`] = "A queue's routing type must be one the address supports.";
        }
        seen.add(q);
      });
      return errors;
    },
  });
  useReseedOnOpen(form, opened, source, seedOf);
  useEffect(() => {
    refused.current = false;
  }, [opened, item]);
  const { routingTypes, queues } = form.values;

  const submit = form.onSubmit(
    (values) => {
      const next: ConfigAddressView = {
        name: values.name.trim(),
        routingTypes: values.routingTypes,
        queues: values.queues.map(({ queue }) => ({
          ...queue,
          name: queue.name.trim(),
          filter: queue.filter?.trim() || null,
        })),
      };
      save(upsertAddress(declaration.document, next, item?.name), `${item ? 'Edited' : 'Added'} address ${next.name}`);
    },
    (errors) => {
      refused.current = true;
      focusFirstInvalid(form.getInputNode)(errors);
    },
  );

  const remove = () =>
    save(
      removeItem(declaration.document, 'addresses', item!.name),
      `Removed address ${item!.name} from the declaration`,
    );

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
      hint={Object.keys(form.errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button
            variant="subtle"
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
      <TextInput label="Address" {...form.getInputProps('name')} required />
      <Checkbox.Group
        label="Routing types"
        description="ANYCAST delivers each message to one consumer; MULTICAST to every subscriber."
        {...form.getInputProps('routingTypes')}
      >
        <Group gap="lg" mt="xs">
          <Checkbox value="ANYCAST" label="Anycast" />
          <Checkbox value="MULTICAST" label="Multicast" />
        </Group>
      </Checkbox.Group>

      <Section
        headingLevel={3}
        title="Queues"
        actions={
          <Button
            variant="default"
            size="xs"
            onClick={() =>
              form.insertListItem(
                'queues',
                newRow({ name: '', routingType: routingTypes[0] ?? 'ANYCAST', durable: true }),
              )
            }
          >
            Add queue
          </Button>
        }
      >
        {queues.length === 0 ? (
          <Text size="sm" c="dimmed">
            No queues declared on this address. An apply creates the address alone.
          </Text>
        ) : null}
        {queues.map(({ rowKey, queue: q }, i) => (
          <FieldRow key={rowKey}>
            <TextInput label="Queue name" {...form.getInputProps(`queues.${i}.queue.name`)} />
            <Select
              label="Routing"
              data={routingTypes.map((t) => ({ value: t, label: t }))}
              {...form.getInputProps(`queues.${i}.queue.routingType`)}
              allowDeselect={false}
            />
            <Switch label="Durable" {...form.getInputProps(`queues.${i}.queue.durable`, { type: 'checkbox' })} />
            <ActionIcon
              variant="subtle"
              aria-label={`Remove queue ${q.name || i + 1} from the declaration`}
              title="Stops Studio checking for it. Nothing on the broker is deleted."
              onClick={() => form.removeListItem('queues', i)}
            >
              <IconX size="0.875rem" />
            </ActionIcon>
          </FieldRow>
        ))}
        <Text size="sm" c="dimmed">
          A declared queue is created where missing. One that exists with a different configuration is reported, never
          changed — edit it from the Queues view. Nothing here deletes a queue.
        </Text>
      </Section>
    </EditorDrawer>
  );
}
