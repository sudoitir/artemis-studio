import { useState } from 'react';
import { ActionIcon, Button, Group, Modal, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import { useAuthProviders } from '../../kernel/auth/api.ts';
import { useCreateGroupMapping, useDeleteGroupMapping, useGroupMappings, useRoles, useSetDefaultRole } from './api.ts';

/** Group -> role mappings for each external identity provider, re-applied on every sign-in (ADR-0073). */
export function GroupMappingPanel() {
  const providers = useAuthProviders();
  const external = (providers.data ?? []).filter((p) => p.id !== 'local');
  const [picked, setPicked] = useState<string | null>(null);
  const providerId = picked ?? external[0]?.id ?? null;

  if (providers.isSuccess && external.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No external identity provider is configured, so there are no groups to map. An OpenID Connect provider
        appears here once
        <Text component="span" ff="monospace" size="sm">
          {' '}
          spring.security.oauth2.client.registration.*
        </Text>{' '}
        is set.
      </Text>
    );
  }

  return (
    <Stack gap="md">
      <Select
        label="Identity provider"
        data={external.map((p) => ({ value: p.id, label: p.label }))}
        value={providerId}
        onChange={setPicked}
        allowDeselect={false}
        maw={320}
      />
      {providerId ? <ProviderMappings key={providerId} providerId={providerId} /> : null}
    </Stack>
  );
}

function ProviderMappings({ providerId }: { providerId: string }) {
  const mappings = useGroupMappings(providerId);
  const roles = useRoles();
  const create = useCreateGroupMapping(providerId);
  const remove = useDeleteGroupMapping(providerId);
  const setDefault = useSetDefaultRole(providerId);

  const [adding, setAdding] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [roleId, setRoleId] = useState<string | null>(null);
  const [invalid, setInvalid] = useState<string | null>(null);

  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));
  const onError = (e: Error) => notifications.show({ message: e.message, color: 'red' });

  const submit = () => {
    if (!groupName.trim() || !roleId) {
      setInvalid('Enter the group name and choose the role it grants.');
      return;
    }
    create.mutate(
      { groupName: groupName.trim(), roleId, scopeType: 'GLOBAL' },
      {
        onSuccess: () => {
          setAdding(false);
          setGroupName('');
          setRoleId(null);
          setInvalid(null);
        },
        onError,
      },
    );
  };

  return (
    <Stack gap="md">
      <Select
        label="Default role"
        description="Granted when a user's groups match no mapping. With none, that user is refused sign-in."
        data={roleOptions}
        value={mappings.data?.defaultRoleId ?? null}
        onChange={(value) => setDefault.mutate({ roleId: value }, { onError })}
        placeholder="None: refuse unmapped users"
        clearable
        maw={420}
      />

      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Applied to a user&apos;s grants every time they sign in through this provider.
        </Text>
        <Button size="xs" onClick={() => setAdding(true)}>
          New mapping
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Group</Table.Th>
            <Table.Th>Role</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(mappings.data?.mappings ?? []).map((m) => (
            <Table.Tr key={m.id}>
              <Table.Td>{m.groupName}</Table.Td>
              <Table.Td>{m.roleName}</Table.Td>
              <Table.Td>
                <ActionIcon
                  variant="subtle"
                  color="red"
                  onClick={() => remove.mutate(m.id, { onError })}
                  aria-label={`Delete mapping for ${m.groupName}`}
                >
                  <IconTrash size={16} />
                </ActionIcon>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal opened={adding} onClose={() => setAdding(false)} title="New group mapping">
        <Stack gap="sm">
          <TextInput
            label="Group"
            value={groupName}
            onChange={(e) => setGroupName(e.currentTarget.value)}
            required
          />
          <Select label="Role" data={roleOptions} value={roleId} onChange={setRoleId} required />
          {invalid ? (
            <Text size="sm" c="red" role="alert">
              {invalid}
            </Text>
          ) : null}
          <Button loading={create.isPending} onClick={submit}>
            Add mapping
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
