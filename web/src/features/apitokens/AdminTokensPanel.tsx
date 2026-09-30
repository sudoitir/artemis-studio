import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Drawer,
  Group,
  Modal,
  Stack,
  Table,
  Text,
  Tooltip,
  VisuallyHidden,
} from '@mantine/core';
import { IconChartBar, IconTrash } from '@tabler/icons-react';

import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { useCan } from '../../kernel/auth/useCan.ts';
import { serverNow } from '../../kernel/time/time.ts';
import { useAdminRevokeToken, useAdminTokens, type TokenView } from './api.ts';
import { formatInstant } from './format.ts';
import { TokenStatus, TokenUsagePanel } from './TokenParts.tsx';

const TOKEN_ADMIN = 'token:admin';

/**
 * Every user's API keys, metadata only, so a leaked key can be revoked without its owner
 * (identity-and-sessions spec, ADR-0134). Keys are minted and rotated only by their owners, on the
 * account page; there is no such action here.
 */
export function AdminTokensPanel() {
  const { can, loading } = useCan();
  if (!loading && !can(TOKEN_ADMIN)) {
    return (
      <Alert color="gray" title="You cannot see other users' keys">
        The key inventory needs the global permission &ldquo;See and revoke every user&apos;s API tokens&rdquo; (
        {TOKEN_ADMIN}). Ask an administrator to add it to one of your roles.
      </Alert>
    );
  }
  return <Inventory />;
}

function Inventory() {
  const tokens = useAdminTokens();
  const [revoking, setRevoking] = useState<TokenView | null>(null);
  const [usageOf, setUsageOf] = useState<TokenView | null>(null);

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        Every user&apos;s keys. A key unused for longer than the stale period in Operational configuration is flagged.
      </Text>
      {tokens.isError ? (
        <Alert color="red" title="The key inventory could not be loaded">
          {tokens.error.message} Reload the page to try again.
        </Alert>
      ) : tokens.data?.length === 0 ? (
        <Text size="sm" c="dimmed">
          No user has a key yet. Users mint keys for scripts and assistants on their account page.
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Owner</Table.Th>
              <Table.Th>Name</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Permissions</Table.Th>
              <Table.Th>MCP tools</Table.Th>
              <Table.Th>Expires</Table.Th>
              <Table.Th>Last used</Table.Th>
              <Table.Th>
                <VisuallyHidden>Actions</VisuallyHidden>
              </Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(tokens.data ?? []).map((t) => (
              <Table.Tr key={t.id}>
                <Table.Td>
                  <Text size="sm">{t.owner}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{t.name}</Text>
                  <Text size="xs" ff="monospace" c="dimmed">
                    {t.prefix}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <TokenStatus token={t} />
                    {t.stale ? (
                      <Badge size="xs" color="yellow" variant="light">
                        stale
                      </Badge>
                    ) : null}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">
                    {t.grants.length === 0
                      ? 'None'
                      : t.grants.map((g) => (g.scopeType === 'GLOBAL' ? g.action : `${g.action} (cluster)`)).join(', ')}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    {t.mcpTools.length === 0 ? 'Every tool' : t.mcpTools.join(', ')}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">{formatInstant(t.expiresAt)}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    {formatInstant(t.lastUsedAt)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Tooltip label="Usage">
                      <ActionIcon variant="subtle" onClick={() => setUsageOf(t)} aria-label={`Usage of ${t.name}`}>
                        <IconChartBar size={16} />
                      </ActionIcon>
                    </Tooltip>
                    {!t.revokedAt && Date.parse(t.expiresAt) > serverNow() ? (
                      <Tooltip label="Revoke">
                        <ActionIcon
                          variant="subtle"
                          color="red"
                          onClick={() => setRevoking(t)}
                          aria-label={`Revoke ${t.name} of ${t.owner}`}
                        >
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Tooltip>
                    ) : null}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <RevokeModal token={revoking} onClose={() => setRevoking(null)} />
      <Drawer
        opened={usageOf !== null}
        onClose={() => setUsageOf(null)}
        position="right"
        title={usageOf ? `Usage of ${usageOf.name} (${usageOf.owner})` : ''}
      >
        {usageOf ? <TokenUsagePanel scope="admin" tokenId={usageOf.id} /> : null}
      </Drawer>
    </Stack>
  );
}

function RevokeModal({ token, onClose }: { token: TokenView | null; onClose: () => void }) {
  const revoke = useAdminRevokeToken();
  return (
    <Modal opened={token !== null} onClose={onClose} title={token ? `Revoke ${token.owner}'s key ${token.name}` : ''}>
      {token ? (
        <Stack gap="sm">
          <Text size="sm">
            Every request with this key is refused from the next one on, and {token.owner} sees it as revoked. The
            revocation is audited. It cannot be undone; {token.owner} can mint a new key.
          </Text>
          {revoke.isError ? (
            <Alert color="red" title="The key was not revoked">
              {revoke.error.message}
            </Alert>
          ) : null}
          <ConfirmByTyping
            token={token.name}
            confirmLabel="Revoke key"
            loading={revoke.isPending}
            onConfirm={() => revoke.mutate(token.id, { onSuccess: onClose })}
          />
        </Stack>
      ) : null}
    </Modal>
  );
}
