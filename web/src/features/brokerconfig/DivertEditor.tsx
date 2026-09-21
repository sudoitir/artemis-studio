import { useEffect, useRef, useState } from 'react';
import { Button, Collapse, Select, Stack, Switch, Text, TextInput } from '@mantine/core';

import type { ConfigDeclarationView, ConfigDivertView } from './api.ts';
import { keyTaken, removeItem, upsertDivert } from './document.ts';
import { EditorDrawer } from './EditorDrawer.tsx';
import { ForwardingAddressField } from './ForwardingAddressField.tsx';
import { TransformerFields, type TransformerValue } from './routing/TransformerFields.tsx';
import { useSaveDocument } from './useSaveDocument.ts';

interface Errors {
  name?: string;
  address?: string;
  forwardingAddress?: string;
}

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
}: {
  declaration: ConfigDeclarationView;
  item: ConfigDivertView | null;
  prefill?: DivertPrefill;
  opened: boolean;
  onClose: () => void;
}) {
  const [name, setName] = useState(item?.name ?? '');
  const [address, setAddress] = useState(item?.address ?? '');
  const [forwardingAddress, setForwardingAddress] = useState(item?.forwardingAddress ?? '');
  const [filter, setFilter] = useState(item?.filter ?? '');
  const [exclusive, setExclusive] = useState(item?.exclusive ?? false);
  const [routingType, setRoutingType] = useState<string>(item?.routingType ?? '');
  const [transformer, setTransformer] = useState<TransformerValue>({ className: '', properties: {} });
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const [creatingQueue, setCreatingQueue] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const forwardRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!opened) return;
    setName(item?.name ?? '');
    setAddress(item?.address ?? prefill?.address ?? '');
    setForwardingAddress(item?.forwardingAddress ?? prefill?.forwardingAddress ?? '');
    setFilter(item?.filter ?? '');
    setExclusive(item?.exclusive ?? false);
    setRoutingType(item?.routingType ?? '');
    setTransformer({
      className: item?.transformerClassName ?? '',
      properties: { ...(item?.transformerProperties ?? {}) },
    });
    setTouched({});
    setSubmitted(false);
    setAdvanced(false);
    setCreatingQueue(false);
  }, [opened, item, prefill]);

  const { save, isPending, error, reset } = useSaveDocument(declaration, onClose);

  const validate = (): Errors => {
    const errors: Errors = {};
    const n = name.trim();
    if (!n) errors.name = 'A divert name is required.';
    else if (keyTaken(declaration.document.diverts, (i) => i.name, n, item?.name)) {
      errors.name = `"${n}" is already declared. Edit that divert instead.`;
    }
    if (!address.trim()) errors.address = 'A source address is required — the divert takes messages from it.';
    if (!forwardingAddress.trim()) {
      errors.forwardingAddress =
        'A forwarding address is required. Messages diverted to an address with no queue are dropped.';
    }
    return errors;
  };
  const errors = validate();
  const errorFor = (field: keyof Errors) => (touched[field] || submitted ? errors[field] : undefined);

  const submit = () => {
    setSubmitted(true);
    if (errors.name) return nameRef.current?.focus();
    if (errors.address) return addressRef.current?.focus();
    if (errors.forwardingAddress) return forwardRef.current?.focus();
    const next: ConfigDivertView = {
      name: name.trim(),
      address: address.trim(),
      forwardingAddress: forwardingAddress.trim(),
      filter: filter.trim() || null,
      exclusive,
      routingType: (routingType || null) as ConfigDivertView['routingType'],
      transformerClassName: transformer.className.trim() || null,
      transformerProperties: transformer.properties,
    };
    save(upsertDivert(declaration.document, next, item?.name), `${item ? 'Edited' : 'Added'} divert ${next.name}`);
  };

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
      hint={submitted && Object.keys(errors).length > 0 ? 'Fix the fields above to continue.' : undefined}
      secondary={
        item ? (
          <Button variant="subtle" color="red" size="xs" onClick={remove} loading={isPending}>
            Remove from declaration
          </Button>
        ) : null
      }
    >
      <TextInput
        ref={nameRef}
        label="Name"
        value={name}
        onChange={(e) => setName(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, name: true }))}
        error={errorFor('name')}
        required
      />
      <TextInput
        ref={addressRef}
        label="From address"
        description="Messages arriving here are diverted."
        value={address}
        onChange={(e) => setAddress(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, address: true }))}
        error={errorFor('address')}
        required
      />
      <ForwardingAddressField
        declaration={declaration}
        inputRef={forwardRef}
        value={forwardingAddress}
        onChange={setForwardingAddress}
        onBlur={() => setTouched((t) => ({ ...t, forwardingAddress: true }))}
        error={errorFor('forwardingAddress')}
        onCreatingChange={setCreatingQueue}
      />
      <Switch
        label="Exclusive"
        description={
          exclusive
            ? 'Takes the message: the original address no longer receives it. A High hazard when the address has traffic.'
            : 'Copies the message: the original address still receives it.'
        }
        checked={exclusive}
        onChange={(e) => setExclusive(e.currentTarget.checked)}
      />
      {item ? (
        <Text size="xs" c="dimmed">
          A changed divert is applied as a delete and a create, in that order; there is a moment between the two
          when nothing is diverted. The plan lists it as a Medium hazard.
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
              value={filter}
              onChange={(e) => setFilter(e.currentTarget.value)}
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
              value={routingType}
              onChange={(v) => setRoutingType(v ?? '')}
              allowDeselect={false}
            />
            <TransformerFields value={transformer} onChange={setTransformer} what="diverted" />
          </Stack>
        </Collapse>
      </div>
    </EditorDrawer>
  );
}
