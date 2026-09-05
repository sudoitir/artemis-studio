import { useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Modal,
  PasswordInput,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';

import {
  useAddGrant,
  useCreateUser,
  useRemoveGrant,
  useRoles,
  useSetUserDisabled,
  useUsers,
} from '../api/client.ts';

/** User accounts and their role grants (authorization spec). Requires `user:admin`. */
export function UsersPanel() {
  const users = useUsers();
  const roles = useRoles();
  const createUser = useCreateUser();
  const setDisabled = useSetUserDisabled();
  const addGrant = useAddGrant();
  const removeGrant = useRemoveGrant();

  const [createOpen, setCreateOpen] = useState(false);
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const [grantingFor, setGrantingFor] = useState<string | null>(null);
  const [roleId, setRoleId] = useState<string | null>(null);

  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {(users.data ?? []).length} user{(users.data ?? []).length === 1 ? '' : 's'}
        </Text>
        <Button size="xs" onClick={() => setCreateOpen(true)}>
          New user
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Username</Table.Th>
            <Table.Th>Source</Table.Th>
            <Table.Th>Grants</Table.Th>
            <Table.Th>Enabled</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(users.data ?? []).map((u) => (
            <Table.Tr key={u.id}>
              <Table.Td>
                <Text size="sm">{u.username}</Text>
                {u.mustChangePassword ? (
                  <Text size="xs" c="dimmed">
                    must change password
                  </Text>
                ) : null}
              </Table.Td>
              <Table.Td>
                <Badge size="xs" variant="light">
                  {u.authSource}
                </Badge>
              </Table.Td>
              <Table.Td>
                <Group gap={4} wrap="wrap">
                  {u.grants.map((g) => (
                    <Badge
                      key={`${g.roleId}-${g.scopeType}-${g.scopeId ?? 'global'}`}
                      size="xs"
                      variant="outline"
                      style={{ cursor: 'pointer' }}
                      rightSection="×"
                      onClick={() =>
                        removeGrant.mutate({
                          userId: u.id,
                          roleId: g.roleId,
                          scopeType: g.scopeType,
                          scopeId: g.scopeId ?? undefined,
                        })
                      }
                    >
                      {g.roleName}
                      {g.scopeType !== 'GLOBAL' ? ` (${g.scopeType.toLowerCase()})` : ''}
                    </Badge>
                  ))}
                  <Badge
                    size="xs"
                    variant="light"
                    color="pine"
                    style={{ cursor: 'pointer' }}
                    onClick={() => setGrantingFor(u.id)}
                  >
                    + grant
                  </Badge>
                </Group>
              </Table.Td>
              <Table.Td>
                <Switch
                  checked={!u.disabled}
                  onChange={() => setDisabled.mutate({ userId: u.id, disabled: !u.disabled })}
                  size="sm"
                  aria-label={`${u.disabled ? 'Enable' : 'Disable'} ${u.username}`}
                />
              </Table.Td>
              <Table.Td />
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal opened={createOpen} onClose={() => setCreateOpen(false)} title="New user">
        <Stack gap="sm">
          <TextInput label="Username" value={username} onChange={(e) => setUsername(e.currentTarget.value)} required />
          <TextInput label="Email" value={email} onChange={(e) => setEmail(e.currentTarget.value)} />
          <PasswordInput
            label="Initial password"
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
            description="The user will be required to change it on first login."
            required
          />
          <Button
            loading={createUser.isPending}
            onClick={() =>
              createUser.mutate(
                { username, email: email || undefined, password },
                {
                  onSuccess: () => {
                    setCreateOpen(false);
                    setUsername('');
                    setEmail('');
                    setPassword('');
                    notifications.show({ message: `Created ${username}`, color: 'green' });
                  },
                  onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                },
              )
            }
          >
            Create
          </Button>
        </Stack>
      </Modal>

      <Modal opened={grantingFor !== null} onClose={() => setGrantingFor(null)} title="Grant a role">
        <Stack gap="sm">
          <Select
            label="Role"
            data={roleOptions}
            value={roleId}
            onChange={setRoleId}
            placeholder="Select a role"
          />
          <Text size="xs" c="dimmed">
            Granted globally. Use the API to scope a grant to one environment or cluster.
          </Text>
          <Button
            disabled={!roleId}
            loading={addGrant.isPending}
            onClick={() => {
              if (!grantingFor || !roleId) return;
              addGrant.mutate(
                { userId: grantingFor, body: { roleId, scopeType: 'GLOBAL' } },
                {
                  onSuccess: () => {
                    setGrantingFor(null);
                    setRoleId(null);
                  },
                  onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                },
              );
            }}
          >
            Grant
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
