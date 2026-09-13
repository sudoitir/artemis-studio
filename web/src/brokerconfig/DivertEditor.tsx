import { useEffect, useRef, useState } from 'react';
import { Button, Collapse, Select, Stack, Switch, Text, TextInput } from '@mantine/core';

import type { ConfigDeclarationView, ConfigDivertView } from '../api/client.ts';
import { keyTaken, removeItem, upsertDivert } from './document.ts';
import { EditorDrawer } from './EditorDrawer.tsx';
import { useSaveDocument } from './useSaveDocument.ts';

interface Errors {
  name?: string;
  address?: string;
  forwardingAddress?: string;
}

/**
 * Edit one declared divert. Changing an existing divert on the broker is a
 * delete and a create — there is no update — and the plan says so as a Medium
 * hazard; the editor states it here so it is not a surprise there.
 */
export function DivertEditor({
  declaration,
  item,
  opened,
  onClose,
}: {
  declaration: ConfigDeclarationView;
  item: ConfigDivertView | null;
  opened: boolean;
  onClose: () => void;
}) {
  const [name, setName] = useState(item?.name ?? '');
  const [address, setAddress] = useState(item?.address ?? '');
  const [forwardingAddress, setForwardingAddress] = useState(item?.forwardingAddress ?? '');
  const [filter, setFilter] = useState(item?.filter ?? '');
  const [exclusive, setExclusive] = useState(item?.exclusive ?? false);
  const [routingType, setRoutingType] = useState<string>(item?.routingType ?? '');
  const [transformerClassName, setTransformerClassName] = useState(item?.transformerClassName ?? '');
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const forwardRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!opened) return;
    setName(item?.name ?? '');
    setAddress(item?.address ?? '');
    setForwardingAddress(item?.forwardingAddress ?? '');
    setFilter(item?.filter ?? '');
    setExclusive(item?.exclusive ?? false);
    setRoutingType(item?.routingType ?? '');
    setTransformerClassName(item?.transformerClassName ?? '');
    setTouched({});
    setSubmitted(false);
    setAdvanced(false);
  }, [opened, item]);

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
      transformerClassName: transformerClassName.trim() || null,
      transformerProperties: item?.transformerProperties ?? {},
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
      <TextInput
        ref={forwardRef}
        label="To address"
        description="Must exist — declared here, or already on every live node with a queue — or the diverted messages are dropped."
        value={forwardingAddress}
        onChange={(e) => setForwardingAddress(e.currentTarget.value)}
        onBlur={() => setTouched((t) => ({ ...t, forwardingAddress: true }))}
        error={errorFor('forwardingAddress')}
        required
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
            <TextInput
              label="Transformer class"
              description="A class on the broker's classpath that transforms each diverted message."
              value={transformerClassName}
              onChange={(e) => setTransformerClassName(e.currentTarget.value)}
            />
          </Stack>
        </Collapse>
      </div>
    </EditorDrawer>
  );
}
