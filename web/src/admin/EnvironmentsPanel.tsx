import { useState } from 'react';
import { ActionIcon, Button, ColorSwatch, Group, Modal, NumberInput, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import {
  useCreateEnvironment,
  useDeleteEnvironment,
  useEnvironments,
  useUpdateEnvironment,
  type EnvironmentView,
} from '../api/client.ts';

/** Environment grouping CRUD (environments spec). Cluster assignment happens from the cluster's own settings. */
export function EnvironmentsPanel() {
  const environments = useEnvironments();
  const create = useCreateEnvironment();
  const update = useUpdateEnvironment();
  const remove = useDeleteEnvironment();

  const [editing, setEditing] = useState<EnvironmentView | 'new' | null>(null);
  const [name, setName] = useState('');
  const [colour, setColour] = useState('#4c6ef5');
  const [sortOrder, setSortOrder] = useState(0);

  function openNew() {
    setEditing('new');
    setName('');
    setColour('#4c6ef5');
    setSortOrder((environments.data ?? []).length);
  }

  function openEdit(env: EnvironmentView) {
    setEditing(env);
    setName(env.name);
    setColour(env.colour ?? '#4c6ef5');
    setSortOrder(env.sortOrder);
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {(environments.data ?? []).length} environment{(environments.data ?? []).length === 1 ? '' : 's'}
        </Text>
        <Button size="xs" onClick={openNew}>
          New environment
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Colour</Table.Th>
            <Table.Th>Name</Table.Th>
            <Table.Th>Order</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(environments.data ?? []).map((e) => (
            <Table.Tr key={e.id}>
              <Table.Td>
                <ColorSwatch color={e.colour ?? 'var(--as-border)'} size={16} />
              </Table.Td>
              <Table.Td>
                <Text size="sm">{e.name}</Text>
              </Table.Td>
              <Table.Td>{e.sortOrder}</Table.Td>
              <Table.Td>
                <Group gap={4}>
                  <ActionIcon variant="subtle" onClick={() => openEdit(e)} aria-label={`Edit ${e.name}`}>
                    <IconPencil size={16} />
                  </ActionIcon>
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    onClick={() => remove.mutate(e.id)}
                    aria-label={`Delete ${e.name}`}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={editing === 'new' ? 'New environment' : `Edit "${name}"`}>
        <Stack gap="sm">
          <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
          <TextInput label="Colour" value={colour} onChange={(e) => setColour(e.currentTarget.value)} placeholder="#4c6ef5" />
          <NumberInput label="Sort order" value={sortOrder} onChange={(v) => setSortOrder(Number(v) || 0)} />
          <Button
            loading={create.isPending || update.isPending}
            onClick={() => {
              const body = { name, colour, sortOrder };
              if (editing === 'new') {
                create.mutate(body, {
                  onSuccess: () => setEditing(null),
                  onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                });
              } else if (editing) {
                update.mutate(
                  { environmentId: editing.id, body },
                  {
                    onSuccess: () => setEditing(null),
                    onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                  },
                );
              }
            }}
          >
            Save
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
