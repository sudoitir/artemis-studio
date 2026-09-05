import { useState } from 'react';
import { ActionIcon, Button, Group, Modal, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';

import { useCreateOidcMapping, useDeleteOidcMapping, useOidcMappings, useRoles } from '../api/client.ts';

/** Claim -> role mapping for SSO logins, re-applied on every login (oidc-sso spec). */
export function OidcMappingPanel() {
  const mappings = useOidcMappings();
  const roles = useRoles();
  const create = useCreateOidcMapping();
  const remove = useDeleteOidcMapping();

  const [adding, setAdding] = useState(false);
  const [claim, setClaim] = useState('groups');
  const [claimValue, setClaimValue] = useState('');
  const [roleId, setRoleId] = useState<string | null>(null);

  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Applied to a user&apos;s grants on every SSO login. No provider is configured until
          <Text component="span" ff="monospace" size="sm">
            {' '}
            spring.security.oauth2.client.registration.*
          </Text>{' '}
          is set.
        </Text>
        <Button size="xs" onClick={() => setAdding(true)}>
          New mapping
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Claim</Table.Th>
            <Table.Th>Value</Table.Th>
            <Table.Th>Role</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(mappings.data ?? []).map((m) => (
            <Table.Tr key={m.id}>
              <Table.Td>
                <Text size="sm" ff="monospace">
                  {m.claim}
                </Text>
              </Table.Td>
              <Table.Td>{m.claimValue}</Table.Td>
              <Table.Td>{m.roleName}</Table.Td>
              <Table.Td>
                <ActionIcon variant="subtle" color="red" onClick={() => remove.mutate(m.id)} aria-label="Delete mapping">
                  <IconTrash size={16} />
                </ActionIcon>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal opened={adding} onClose={() => setAdding(false)} title="New claim mapping">
        <Stack gap="sm">
          <TextInput label="Claim" value={claim} onChange={(e) => setClaim(e.currentTarget.value)} required />
          <TextInput
            label="Claim value"
            value={claimValue}
            onChange={(e) => setClaimValue(e.currentTarget.value)}
            required
          />
          <Select label="Role" data={roleOptions} value={roleId} onChange={setRoleId} placeholder="Select a role" />
          <Button
            disabled={!roleId}
            loading={create.isPending}
            onClick={() => {
              if (!roleId) return;
              create.mutate(
                { claim, claimValue, roleId, scopeType: 'GLOBAL' },
                {
                  onSuccess: () => {
                    setAdding(false);
                    setClaimValue('');
                  },
                  onError: (e) => notifications.show({ message: e.message, color: 'red' }),
                },
              );
            }}
          >
            Add mapping
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}
