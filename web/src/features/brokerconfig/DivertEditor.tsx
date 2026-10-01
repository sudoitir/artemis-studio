import { useEffect, useMemo, useState } from 'react';
import { Button, Collapse, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { focusFirstInvalid } from '../../ui/formErrors.ts';

import type { ConfigDeclarationView, ConfigDivertView } from './api.ts';
import { keyTaken, removeItem, upsertDivert } from './document.ts';
import { EditorDrawer } from './EditorDrawer.tsx';
import { ForwardingAddressField } from './ForwardingAddressField.tsx';
import { TransformerFields, type TransformerValue } from './routing/TransformerFields.tsx';
import { useReseedOnOpen } from './useReseedOnOpen.ts';
import { useSaveDocument } from './useSaveDocument.ts';

interface FormState {
  name: string;
  address: string;
  forwardingAddress: string;
  filter: string;
  exclusive: boolean;
  routingType: string;
  transformer: TransformerValue;
}

const seedOf = ({ item, prefill }: { item: ConfigDivertView | null; prefill?: DivertPrefill }): FormState => ({
  name: item?.name ?? '',
  address: item?.address ?? prefill?.address ?? '',
  forwardingAddress: item?.forwardingAddress ?? prefill?.forwardingAddress ?? '',
  filter: item?.filter ?? '',
  exclusive: item?.exclusive ?? false,
  routingType: item?.routingType ?? '',
  transformer: { className: item?.transformerClassName ?? '', properties: { ...item?.transformerProperties } },
});

/** What a drag on the routing canvas prefills a new divert with. */
export interface DivertPrefill {
  address?: string;
  forwardingAddress?: string;
}

/**
 * Edit one declared divert. Changing an existing divert on the broker is a
 * delete and a create — there is no update — and the plan says so as a Medium
 * hazard; the editor states it here so it is not a surprise there.
 *
 * <p>The transformer is the shared `TransformerFields`, so a divert's properties
 * are authored rather than read and written back untouched (ADR-0090 D6).
 */
export function DivertEditor({
  declaration,
  item,
  prefill,
  opened,
  onClose,
}: Readonly<{
  declaration: ConfigDeclarationView;
  item: ConfigDivertView | null;
  prefill?: DivertPrefill;
  opened: boolean;
  onClose: () => void;
}>) {
  const [advanced, setAdvanced] = useState(false);
  const [creatingQueue, setCreatingQueue] = useState(false);
  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);
  const source = useMemo(() => ({ item, prefill }), [item, prefill]);

  const form = useForm<FormState>({
    initialValues: seedOf(source),
    validateInputOnBlur: true,
    validate: {
      name: (v) => {
        const n = v.trim();
        if (!n) return 'A divert name is required.';
        return keyTaken(declaration.document.diverts, (i) => i.name, n, item?.name)
          ? `"${n}" is already declared. Edit that divert instead.`
          : null;
      },
      address: (v) => (v.trim() ? null : 'A source address is required — the divert takes messages from it.'),
      forwardingAddress: (v) =>
        v.trim()
          ? null
          : 'A forwarding address is required. Messages diverted to an address with no queue are dropped.',
    },
  });
  useReseedOnOpen(form, opened, source, seedOf);
  const { exclusive } = form.values;

  useEffect(() => {
    if (!opened) return;
    setAdvanced(false);
    setCreatingQueue(false);
  }, [opened, item, prefill]);

  const submit = form.onSubmit((values) => {
    const next: ConfigDivertView = {
      name: values.name.trim(),
      address: values.address.trim(),
      forwardingAddress: values.forwardingAddress.trim(),
      filter: values.filter.trim() || null,
      exclusive: values.exclusive,
      routingType: (values.routingType || null) as ConfigDivertView['routingType'],
      transformerClassName: values.transformer.className.trim() || null,
      transformerProperties: values.transformer.properties,
    };
    save(upsertDivert(declaration.document, next, item?.name), `${item ? 'Edited' : 'Added'} divert ${next.name}`);
  }, focusFirstInvalid(form.getInputNode));

  const remove = () => save(removeItem(declaration.document, 'diverts', item!.name), `Removed divert ${item!.name}`);

  return (
    <EditorDrawer
      opened={opened}
      onClose={() => {
        reset();
        onClose();
      }}
      title={item ? `Divert ${item.name}` : 'New divert'}
      closeOnEscape={!creatingQueue}
      error={error}
      submitting={isPending}
      submitLabel={`Save as revision ${declaration.revision + 1}`}
      onSubmit={submit}
      hint={Object.keys(form.errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button variant="subtle" size="xs" onClick={remove} loading={isPending}>
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput label="Name" {...form.getInputProps('name')} required />
      <TextInput
        label="From address"
        description="Messages arriving here are diverted."
        {...form.getInputProps('address')}
        required
      />
      <ForwardingAddressField
        declaration={declaration}
        {...form.getInputProps('forwardingAddress')}
        value={form.values.forwardingAddress}
        onChange={(value) => form.setFieldValue('forwardingAddress', value)}
        onCreatingChange={setCreatingQueue}
      />
      <Switch
        label="Exclusive"
        description={
          exclusive
            ? 'Takes the message: the original address no longer receives it. A High hazard when the address has traffic.'
            : 'Copies the message: the original address still receives it.'
        }
        {...form.getInputProps('exclusive', { type: 'checkbox' })}
      />
      {item ? (
        <Text size="xs" c="dimmed">
          A changed divert is applied as a delete and a create, in that order; there is a moment between the two when
          nothing is diverted. The plan lists it as a Medium hazard.
        </Text>
      ) : null}

      <div>
        <Button variant="subtle" size="xs" px={0} onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
          {advanced ? 'Hide advanced configuration' : 'Advanced configuration'}
        </Button>
        <Collapse expanded={advanced}>
          <Stack gap="sm" mt="sm">
            <TextInput
              label="Filter"
              description="A selector; only matching messages are diverted. Empty diverts all."
              {...form.getInputProps('filter')}
            />
            <Select
              label="Routing type"
              description="How the diverted copy is routed. Not declared keeps the broker's default (STRIP)."
              data={[
                { value: '', label: 'not declared' },
                { value: 'STRIP', label: 'STRIP' },
                { value: 'PASS', label: 'PASS' },
                { value: 'ANYCAST', label: 'ANYCAST' },
                { value: 'MULTICAST', label: 'MULTICAST' },
              ]}
              {...form.getInputProps('routingType')}
              allowDeselect={false}
            />
            <TransformerFields
              value={form.values.transformer}
              onChange={(next) => form.setFieldValue('transformer', next)}
              what="diverted"
            />
          </Stack>
        </Collapse>
      </div>
    </EditorDrawer>
  );
}
