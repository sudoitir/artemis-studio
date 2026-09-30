import { useState } from 'react';
import { Alert, Badge, Button, Drawer, Loader, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

import { useEffectivePermissions, type EffectivePermissionView } from './api.ts';

function scopeLabel(v: EffectivePermissionView): string {
  if (v.scopeType === 'GLOBAL') return 'Global';
  const kind = v.scopeType === 'CLUSTER' ? 'Cluster' : 'Environment';
  return `${kind} ${v.scopeId?.slice(0, 8) ?? ''}`;
}

/** A user's effective permissions per scope, with the role and wildcard each came through (operator-ui spec). */
export function EffectivePermissionsDrawer({
  user,
  onClose,
}: Readonly<{
  user: { id: string; username: string } | null;
  onClose: () => void;
}>) {
  const result = useEffectivePermissions(user?.id ?? null);
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  const rows = (result.data ?? []).filter(
    (v) => q === '' || v.action.toLowerCase().includes(q) || v.roleName.toLowerCase().includes(q),
  );
  const scopes = [...new Set(rows.map(scopeLabel))];

  return (
    <Drawer
      opened={user !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={user ? `Effective permissions of ${user.username}` : ''}
    >
      {result.isPending ? (
        <Loader size="sm" aria-label="Loading effective permissions" />
      ) : result.isError ? (
        <Alert color="red" variant="light" title="Could not load the effective permissions" role="alert">
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{result.error.message}</Text>
            <Button size="xs" variant="light" onClick={() => void result.refetch()}>
              Retry
            </Button>
          </Stack>
        </Alert>
      ) : result.data.length === 0 ? (
        <Text size="sm">This user holds no role, so they can do nothing. Grant a role from the users table.</Text>
      ) : (
        <Stack gap="md">
          <TextInput
            label="Filter by permission or role"
            leftSection={<IconSearch size={16} />}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
          />
          {rows.length === 0 ? (
            <Stack gap={4} align="flex-start">
              <Text size="sm">Nothing matches “{query}”.</Text>
              <Button size="xs" variant="subtle" onClick={() => setQuery('')}>
                Clear filter
              </Button>
            </Stack>
          ) : (
            scopes.map((scope) => (
              <Stack key={scope} gap={4}>
                <Text size="sm" fw={600}>
                  {scope}
                </Text>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Permission</Table.Th>
                      <Table.Th>Role</Table.Th>
                      <Table.Th>Effect</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {rows
                      .filter((v) => scopeLabel(v) === scope)
                      .map((v) => (
                        <Table.Tr key={`${v.action}-${v.roleId}-${v.via}`}>
                          <Table.Td>
                            <Text size="sm" ff="monospace">
                              {v.action}
                            </Text>
                            {v.description ? (
                              <Text size="xs" c="dimmed">
                                {v.description}
                              </Text>
                            ) : null}
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">{v.roleName}</Text>
                            {v.via !== v.action ? (
                              <Text size="xs" c="dimmed">
                                through <span style={{ fontFamily: 'monospace' }}>{v.via}</span>
                              </Text>
                            ) : null}
                          </Table.Td>
                          <Table.Td>
                            {v.effective ? (
                              <Text size="sm">Granted</Text>
                            ) : (
                              <Stack gap={2}>
                                <Badge size="sm" variant="outline" color="red">
                                  No effect at this scope
                                </Badge>
                                <Text size="xs">{v.reason}</Text>
                              </Stack>
                            )}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                  </Table.Tbody>
                </Table>
              </Stack>
            ))
          )}
        </Stack>
      )}
    </Drawer>
  );
}
