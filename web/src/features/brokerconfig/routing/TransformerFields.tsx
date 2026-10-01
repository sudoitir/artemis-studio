import { useState } from 'react';
import { ActionIcon, Button, Group, Stack, Text, TextInput } from '@mantine/core';
import { IconX } from '@tabler/icons-react';

import { DataTable, type Column } from '../../../ui/table/index.ts';

/**
 * A transformer: the class the broker loads and the properties that configure it.
 * Shared by the divert and the bridge editors, which is what closes the gap where
 * a divert's properties were read and then written back untouched.
 *
 * <p>Studio cannot verify the class is on a broker's classpath — no management
 * operation reports what a broker has loaded. The three honest options were to
 * hide the field, to disable it, or to state the uncertainty; ADR-0090 and
 * ADR-0049 D5 take the third. The statement is plain text beside the field, so it
 * is reachable from the keyboard rather than hanging off a hover.
 */
export const TRANSFORMER_REACH =
  'Studio cannot check that this class is on each broker’s classpath before the configuration is applied. ' +
  'A node that cannot load it fails that step and names the class.';

export interface TransformerValue {
  className: string;
  properties: Record<string, string>;
}

/** One property of the transformer, as the table draws it. */
interface PropertyRow {
  name: string;
  value: string;
}

/** The properties table: the name, an input for the value, and the control that drops it. */
function propertyColumns(value: TransformerValue, onChange: (next: TransformerValue) => void): Column<PropertyRow>[] {
  const drop = (name: string) => {
    const next = { ...value.properties };
    delete next[name];
    onChange({ ...value, properties: next });
  };
  return [
    { id: 'name', header: 'Property', accessor: (r) => r.name, kind: 'identifier', priority: 'essential', wrap: true },
    {
      id: 'value',
      header: 'Value',
      accessor: (r) => r.value,
      cell: (r) => (
        <TextInput
          size="xs"
          aria-label={`Value of transformer property ${r.name}`}
          value={r.value}
          onChange={(e) => onChange({ ...value, properties: { ...value.properties, [r.name]: e.currentTarget.value } })}
        />
      ),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'remove',
      header: 'Remove',
      accessor: () => '',
      cell: (r) => (
        <ActionIcon
          variant="subtle"
          size="sm"
          aria-label={`Remove transformer property ${r.name}`}
          onClick={() => drop(r.name)}
        >
          <IconX size="0.875rem" />
        </ActionIcon>
      ),
      kind: 'status',
      priority: 'essential',
      wrap: true,
    },
  ];
}

export function TransformerFields({
  value,
  onChange,
  what,
}: Readonly<{
  value: TransformerValue;
  onChange: (next: TransformerValue) => void;
  /** "diverted" or "forwarded" — what happens to the messages this transformer sees. */
  what: 'diverted' | 'forwarded';
}>) {
  const [key, setKey] = useState('');
  const [val, setVal] = useState('');
  const entries = Object.entries(value.properties);

  const add = () => {
    const k = key.trim();
    if (!k) return;
    onChange({ ...value, properties: { ...value.properties, [k]: val } });
    setKey('');
    setVal('');
  };

  return (
    <Stack gap="xs">
      <TextInput
        label="Transformer class"
        description={`A class on the broker's classpath that transforms each ${what} message. ${TRANSFORMER_REACH}`}
        value={value.className}
        onChange={(e) => onChange({ ...value, className: e.currentTarget.value })}
      />

      {entries.length > 0 ? (
        <DataTable
          variant="static"
          label="Transformer properties"
          columns={propertyColumns(value, onChange)}
          data={entries.map(([name, v]) => ({ name, value: v }))}
          rowKey={(r) => r.name}
          height={{ maxRows: entries.length }}
          empty={null}
        />
      ) : null}

      <Group align="flex-end" gap="xs">
        <TextInput
          label="Add a transformer property"
          description="Passed to the class as it is loaded. Properties on their own configure nothing — a class is required."
          size="xs"
          value={key}
          onChange={(e) => setKey(e.currentTarget.value)}
          flex={1}
        />
        <TextInput
          label="Value"
          size="xs"
          value={val}
          onChange={(e) => setVal(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              add();
            }
          }}
          flex={1}
        />
        <Button variant="default" size="xs" onClick={add}>
          Add property
        </Button>
      </Group>
      {value.className.trim() === '' && entries.length > 0 ? (
        <Text size="sm" c="dimmed">
          These properties are not applied while no transformer class is declared.
        </Text>
      ) : null}
    </Stack>
  );
}
