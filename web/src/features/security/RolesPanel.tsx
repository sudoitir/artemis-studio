import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import { useCreateRole, useDeleteRole, usePermissionsCatalogue, useRoles, useUpdateRole, type RoleView } from './api.ts';
import { diffRoles } from './diffRoles.ts';
import { PermissionPicker } from './PermissionPicker.tsx';

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

  const [comparing, setComparing] = useState(false);

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {(roles.data ?? []).length} role{(roles.data ?? []).length === 1 ? '' : 's'}
        </Text>
        <Group gap="xs">
          <Button size="xs" variant="default" onClick={() => setComparing(true)}>
            Compare roles
          </Button>
          <Button size="xs" onClick={openNew}>
            New role
          </Button>
        </Group>
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
        size="lg"
      >
        <Stack gap="sm">
          <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
          {catalogue.isError ? (
            <Alert color="red" variant="light" title="Could not load the permission catalogue" role="alert">
              <Stack gap="xs" align="flex-start">
                <Text size="sm">{catalogue.error.message}</Text>
                <Button size="xs" variant="light" onClick={() => void catalogue.refetch()}>
                  Retry
                </Button>
              </Stack>
            </Alert>
          ) : catalogue.isPending ? (
            <Group gap="xs">
              <Loader size="xs" />
              <Text size="sm">Loading permissions…</Text>
            </Group>
          ) : (
            <PermissionPicker catalogue={catalogue.data ?? []} value={permissions} onChange={setPermissions} />
          )}
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

      <CompareRolesModal opened={comparing} onClose={() => setComparing(false)} roles={roles.data ?? []} />
    </Stack>
  );
}

function CompareRolesModal({ opened, onClose, roles }: { opened: boolean; onClose: () => void; roles: RoleView[] }) {
  const [a, setA] = useState<string | null>(null);
  const [b, setB] = useState<string | null>(null);
  const options = roles.map((r) => ({ value: r.id, label: r.name }));
  const roleA = roles.find((r) => r.id === a);
  const roleB = roles.find((r) => r.id === b);
  const diff = roleA && roleB ? diffRoles(roleA.permissions, roleB.permissions) : null;

  return (
    <Modal opened={opened} onClose={onClose} title="Compare roles" size="lg">
      <Stack gap="sm">
        <SimpleGrid cols={2}>
          <Select label="First role" data={options} value={a} onChange={setA} searchable />
          <Select label="Second role" data={options} value={b} onChange={setB} searchable />
        </SimpleGrid>
        {!diff ? (
          <Text size="sm" c="dimmed">
            Pick two roles to see the permissions only one of them holds.
          </Text>
        ) : diff.onlyA.length === 0 && diff.onlyB.length === 0 ? (
          <Text size="sm" role="status">
            {roleA!.name} and {roleB!.name} hold the same permissions.
          </Text>
        ) : (
          <SimpleGrid cols={2} role="status">
            {[
              { name: roleA!.name, only: diff.onlyA },
              { name: roleB!.name, only: diff.onlyB },
            ].map((side) => (
              <Stack key={side.name} gap={4}>
                <Text size="sm" fw={500}>
                  Only in {side.name} ({side.only.length})
                </Text>
                {side.only.length === 0 ? (
                  <Text size="sm" c="dimmed">
                    Nothing
                  </Text>
                ) : (
                  <ul aria-label={`Only in ${side.name}`} style={{ margin: 0, paddingInlineStart: '1.2em' }}>
                    {side.only.map((p) => (
                      <li key={p}>
                        <Text size="sm" ff="monospace">
                          {p}
                        </Text>
                      </li>
                    ))}
                  </ul>
                )}
              </Stack>
            ))}
          </SimpleGrid>
        )}
      </Stack>
    </Modal>
  );
}
