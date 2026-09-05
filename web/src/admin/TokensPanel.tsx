import { useState } from 'react';
import { ActionIcon, Alert, Badge, Button, CopyButton, Group, Modal, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconCopy, IconTrash } from '@tabler/icons-react';

import { useCreateToken, useRevokeToken, useTokens } from '../api/client.ts';

/** Personal API tokens, always scoped to the caller's own account (api-tokens spec). */
export function TokensPanel() {
  const tokens = useTokens();
  const create = useCreateToken();
  const revoke = useRevokeToken();

  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [mintedValue, setMintedValue] = useState<string | null>(null);

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Tokens authenticate as you, narrowed to no more than your own grants.
        </Text>
        <Button size="xs" onClick={() => setCreating(true)}>
          New token
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Prefix</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Last used</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {(tokens.data ?? []).map((t) => (
            <Table.Tr key={t.id}>
              <Table.Td>
                <Text size="sm">{t.name}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="xs" ff="monospace" c="dimmed">
                  {t.prefix}
                </Text>
              </Table.Td>
              <Table.Td>
                {t.revokedAt ? (
                  <Badge size="xs" color="red" variant="light">
                    revoked
                  </Badge>
                ) : t.expiresAt && new Date(t.expiresAt) < new Date() ? (
                  <Badge size="xs" color="orange" variant="light">
                    expired
                  </Badge>
                ) : (
                  <Badge size="xs" color="green" variant="light">
                    active
                  </Badge>
                )}
              </Table.Td>
              <Table.Td>
                <Text size="xs" c="dimmed">
                  {t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleString() : 'never'}
                </Text>
              </Table.Td>
              <Table.Td>
                {!t.revokedAt ? (
                  <ActionIcon variant="subtle" color="red" onClick={() => revoke.mutate(t.id)} aria-label={`Revoke ${t.name}`}>
                    <IconTrash size={16} />
                  </ActionIcon>
                ) : null}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      <Modal
        opened={creating}
        onClose={() => {
          setCreating(false);
          setMintedValue(null);
          setName('');
        }}
        title="New API token"
      >
        {mintedValue ? (
          <Stack gap="sm">
            <Alert color="yellow">This value is shown once. Copy it now — it cannot be retrieved again.</Alert>
            <Group>
              <TextInput value={mintedValue} readOnly style={{ flex: 1 }} ff="monospace" />
              <CopyButton value={mintedValue}>
                {({ copy }) => (
                  <ActionIcon onClick={copy} aria-label="Copy token">
                    <IconCopy size={16} />
                  </ActionIcon>
                )}
              </CopyButton>
            </Group>
          </Stack>
        ) : (
          <Stack gap="sm">
            <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
            <Text size="xs" c="dimmed">
              This token inherits none of your grants until scoped via the API — see{' '}
              <Text component="span" ff="monospace" size="xs">
                POST /api/v1/tokens
              </Text>
              .
            </Text>
            <Button
              loading={create.isPending}
              onClick={() =>
                create.mutate(
                  { name, expiresAt: undefined, grants: [] },
                  { onSuccess: (created) => setMintedValue(created.value) },
                )
              }
            >
              Create
            </Button>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}
