import { useState, type ReactNode } from 'react';
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
  Switch,
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
} from './api.ts';
import { diffRoles } from './diffRoles.ts';
import { PermissionPicker } from './PermissionPicker.tsx';

/**
 * Role CRUD (authorization spec). A built-in role (ADMIN/OPERATOR/VIEWER) keeps its name and permissions and is shown
 * as facts; the one thing an administrator can change on it is whether it requires two-step verification.
 */
export function RolesPanel() {
  const roles = useRoles();
  const catalogue = usePermissionsCatalogue();
  const create = useCreateRole();
  const update = useUpdateRole();
  const remove = useDeleteRole();

  const [editing, setEditing] = useState<RoleView | 'new' | null>(null);
  const [name, setName] = useState('');
  const [permissions, setPermissions] = useState<string[]>([]);
  const [requiresMfa, setRequiresMfa] = useState(false);

  function openNew() {
    setEditing('new');
    setName('');
    setPermissions([]);
    setRequiresMfa(false);
  }

  function openEdit(role: RoleView) {
    setEditing(role);
    setName(role.name);
    setPermissions(role.permissions);
    setRequiresMfa(role.requiresMfa);
  }

  const editedRole = editing !== 'new' && editing !== null ? editing : null;
  // Saving ends the sessions of the role's members when what they signed in with has changed under them.
  const endsSessions = editedRole !== null && (!editedRole.builtin || requiresMfa !== editedRole.requiresMfa);

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
            <Table.Th>Two-step verification</Table.Th>
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
                <Text size="sm" c={r.requiresMfa ? undefined : 'dimmed'}>
                  {r.requiresMfa ? 'Required' : 'Not required'}
                </Text>
              </Table.Td>
              <Table.Td>
                <Group gap={4}>
                  <ActionIcon variant="subtle" onClick={() => openEdit(r)} aria-label={`Edit ${r.name}`}>
                    <IconPencil size={16} />
                  </ActionIcon>
                  {r.builtin ? null : (
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      onClick={() =>
                        remove.mutate(r.id, {
                          onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                        })
                      }
                      aria-label={`Delete ${r.name}`}
                    >
                      <IconTrash size={16} />
                    </ActionIcon>
                  )}
                </Group>
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
          {editedRole?.builtin ? (
            <>
              <Stack gap={2}>
                <Text size="sm" fw={500}>
                  Name
                </Text>
                <Text size="sm">{editedRole.name}</Text>
              </Stack>
              <Stack gap={4}>
                <Text size="sm" fw={500}>
                  Permissions
                </Text>
                <Text size="xs" c="dimmed">
                  Built-in roles keep their name and permissions; only the setting below can change.
                </Text>
                <Text size="xs" ff="monospace">
                  {editedRole.permissions.join(', ')}
                </Text>
              </Stack>
            </>
          ) : (
            <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
          )}
          {editedRole?.builtin
            ? null
            : (catalogueNotice(catalogue) ?? (
                <PermissionPicker catalogue={catalogue.data ?? []} value={permissions} onChange={setPermissions} />
              ))}
          <Stack gap={4}>
            <Switch
              label="Require two-step verification"
              description="Applies to local accounts. Single sign-on users rely on their identity provider."
              checked={requiresMfa}
              onChange={(e) => setRequiresMfa(e.currentTarget.checked)}
            />
            {endsSessions ? (
              <Text size="xs" c="dimmed">
                Saving signs out everyone who holds this role.
              </Text>
            ) : null}
          </Stack>
          <Button
            loading={create.isPending || update.isPending}
            onClick={() => {
              const body = { name, permissions, requiresMfa };
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

/** What differs between two roles, or how to pick them. */
function RoleComparison({ a, b }: Readonly<{ a: RoleView | undefined; b: RoleView | undefined }>) {
  if (!a || !b) {
    return (
      <Text size="sm" c="dimmed">
        Pick two roles to see the permissions only one of them holds.
      </Text>
    );
  }
  const diff = diffRoles(a.permissions, b.permissions);
  if (diff.onlyA.length === 0 && diff.onlyB.length === 0) {
    return (
      <Text size="sm" role="status">
        {a.name} and {b.name} hold the same permissions.
      </Text>
    );
  }
  return (
    <SimpleGrid cols={2} role="status">
      {[
        { name: a.name, only: diff.onlyA },
        { name: b.name, only: diff.onlyB },
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
  );
}

/** What stands in for the permission picker while the catalogue loads or fails to load. */
function catalogueNotice(catalogue: ReturnType<typeof usePermissionsCatalogue>): ReactNode {
  if (catalogue.isError) {
    return (
      <Alert color="red" variant="light" title="Could not load the permission catalogue" role="alert">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{catalogue.error.message}</Text>
          <Button size="xs" variant="light" onClick={() => void catalogue.refetch()}>
            Retry
          </Button>
        </Stack>
      </Alert>
    );
  }
  if (catalogue.isPending) {
    return (
      <Group gap="xs">
        <Loader size="xs" />
        <Text size="sm">Loading permissions…</Text>
      </Group>
    );
  }
  return null;
}

function CompareRolesModal({
  opened,
  onClose,
  roles,
}: Readonly<{ opened: boolean; onClose: () => void; roles: RoleView[] }>) {
  const [a, setA] = useState<string | null>(null);
  const [b, setB] = useState<string | null>(null);
  const options = roles.map((r) => ({ value: r.id, label: r.name }));
  const roleA = roles.find((r) => r.id === a);
  const roleB = roles.find((r) => r.id === b);

  return (
    <Modal opened={opened} onClose={onClose} title="Compare roles" size="lg">
      <Stack gap="sm">
        <SimpleGrid cols={2}>
          <Select label="First role" data={options} value={a} onChange={setA} searchable />
          <Select label="Second role" data={options} value={b} onChange={setB} searchable />
        </SimpleGrid>
        <RoleComparison a={roleA} b={roleB} />
      </Stack>
    </Modal>
  );
}
