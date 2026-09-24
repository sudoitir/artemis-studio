import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Table, Text, TextInput } from '@mantine/core';

import { needsReauthentication, useGrantInstaller, useInstallers, useRevokeInstaller, violationsOf } from './api.ts';
import { StepUp } from './StepUp.tsx';

/**
 * Who can install plugins (ADR-0103). Deliberately not a role: only an installer changes this,
 * after confirming it is them, and the last one cannot be removed.
 */
export function InstallersDialog({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const installers = useInstallers(opened);
  const grant = useGrantInstaller();
  const revoke = useRevokeInstaller();
  const [username, setUsername] = useState('');
  const [empty, setEmpty] = useState(false);
  const error = grant.error ?? revoke.error;
  const stale = needsReauthentication(error);
  const only = (installers.data ?? []).length <= 1;

  return (
    <Modal opened={opened} onClose={onClose} title="Who can install plugins" size="lg">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Installing a plugin runs its code inside Studio, so this is kept apart from roles: no role, however
          broad, lets anyone install. Changes take effect on the person's next request.
        </Text>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>User</Table.Th>
              <Table.Th>Added</Table.Th>
              <Table.Th>By</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(installers.data ?? []).map((i) => (
              <Table.Tr key={i.userId}>
                <Table.Td>{i.username}</Table.Td>
                <Table.Td>{new Date(i.grantedAt).toLocaleDateString()}</Table.Td>
                <Table.Td>{i.grantedBy ?? '—'}</Table.Td>
                <Table.Td>
                  <Button
                    size="xs"
                    variant="subtle"
                    color="red"
                    disabled={only}
                    title={only ? 'The last installer cannot be removed; add another first.' : undefined}
                    loading={revoke.isPending && revoke.variables === i.userId}
                    onClick={() => revoke.mutate(i.userId)}
                    aria-label={`Remove ${i.username}`}
                  >
                    Remove
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        {only ? (
          <Text size="xs" c="dimmed">
            The only installer cannot be removed; add another first.
          </Text>
        ) : null}
        <StepUp returnTo={`${window.location.pathname}?tab=plugins`} />
        {error && !stale ? (
          <Alert color="red" variant="light" role="alert">
            {violationsOf(error).map((v) => v.message).join(' ') || error.message}
          </Alert>
        ) : null}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (!username.trim()) {
              setEmpty(true);
              return;
            }
            grant.mutate(username.trim(), { onSuccess: () => setUsername('') });
          }}
        >
          <Group align="flex-end" gap="xs">
            <TextInput
              label="Add an installer"
              description="Their Studio username"
              value={username}
              onChange={(e) => {
                setUsername(e.currentTarget.value);
                setEmpty(false);
              }}
              error={empty ? 'Enter a username.' : undefined}
              w={280}
            />
            <Button type="submit" loading={grant.isPending}>
              Add
            </Button>
          </Group>
        </form>
      </Stack>
    </Modal>
  );
}
