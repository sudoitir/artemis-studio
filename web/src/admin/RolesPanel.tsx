import { useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import {
  useCreateRole,
  useDeleteRole,
  usePermissionsCatalogue,
  useRoles,
  useUpdateRole,
  type RoleView,
} from '../api/client.ts';

/** Custom role CRUD; built-in roles (ADMIN/OPERATOR/VIEWER) are read-only (authorization spec). */
export function RolesPanel() {
  const roles = useRoles();
  const catalogue = usePermissionsCatalogue();
  const create = useCreateRole();
  const update = useUpdateRole();
  const remove = useDeleteRole();

  const [editing, setEditing] = useState<RoleView | 'new' | null>(null);
  const [name, setName] = useState('');
  const [permissions, setPermissions] = useState<string[]>([]);

  function openNew() {
    setEditing('new');
    setName('');
    setPermissions([]);
  }

  function openEdit(role: RoleView) {
    setEditing(role);
    setName(role.name);
    setPermissions(role.permissions);
  }

  function togglePermission(action: string) {
    setPermissions((prev) => (prev.includes(action) ? prev.filter((p) => p !== action) : [...prev, action]));
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {(roles.data ?? []).length} role{(roles.data ?? []).length === 1 ? '' : 's'}
        </Text>
        <Button size="xs" onClick={openNew}>
          New role
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Permissions</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(roles.data ?? []).map((r) => (
            <Table.Tr key={r.id}>
              <Table.Td>
                <Group gap={6}>
                  <Text size="sm">{r.name}</Text>
                  {r.builtin ? (
                    <Badge size="xs" variant="light">
                      built-in
                    </Badge>
                  ) : null}
                </Group>
              </Table.Td>
              <Table.Td>
                <Text size="xs" c="dimmed" lineClamp={1}>
                  {r.permissions.join(', ')}
                </Text>
              </Table.Td>
              <Table.Td>
                {r.builtin ? null : (
                  <Group gap={4}>
                    <ActionIcon variant="subtle" onClick={() => openEdit(r)} aria-label={`Edit ${r.name}`}>
                      <IconPencil size={16} />
                    </ActionIcon>
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      onClick={() => remove.mutate(r.id, { onError: (e) => notifications.show({ message: e.message, color: 'red' }) })}
                      aria-label={`Delete ${r.name}`}
                    >
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Group>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal
        opened={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'New role' : `Edit "${name}"`}
        size="md"
      >
        <Stack gap="sm">
          <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
          <Stack gap={4}>
            <Text size="sm" fw={500}>
              Permissions
            </Text>
            {(catalogue.data ?? []).map((p) => (
              <Checkbox
                key={p.action}
                label={`${p.action} — ${p.label}`}
                checked={permissions.includes(p.action)}
                onChange={() => togglePermission(p.action)}
              />
            ))}
          </Stack>
          <Button
            loading={create.isPending || update.isPending}
            onClick={() => {
              const body = { name, permissions };
              if (editing === 'new') {
                create.mutate(body, {
                  onSuccess: () => setEditing(null),
                  onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                });
              } else if (editing) {
                update.mutate(
                  { roleId: editing.id, body },
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
