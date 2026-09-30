import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Button,
  Drawer,
  Group,
  Modal,
  Stack,
  Table,
  Text,
  Tooltip,
  VisuallyHidden,
} from '@mantine/core';
import { IconChartBar, IconRefresh, IconTrash } from '@tabler/icons-react';

import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { serverNow } from '../../kernel/time/time.ts';
import { useRevokeToken, useRotateToken, useTokens, type CreatedTokenView, type TokenView } from './api.ts';
import { MintKeyForm } from './MintKeyForm.tsx';
import { formatInstant } from './format.ts';
import { OneTimeSecret, TokenStatus, TokenUsagePanel } from './TokenParts.tsx';

/**
 * Personal API keys (ADR-0039), and the only place a key is minted or rotated (ADR-0136). A key is
 * the credential the MCP surface authenticates with (ADR-0046), which is why minting asks for its
 * grants: a key created with no grants can sign in and do nothing.
 */
export function ApiKeysPanel() {
  const tokens = useTokens();
  const [creating, setCreating] = useState(false);
  const [minted, setMinted] = useState<CreatedTokenView | null>(null);
  const [rotating, setRotating] = useState<TokenView | null>(null);
  const [revoking, setRevoking] = useState<TokenView | null>(null);
  const [usageOf, setUsageOf] = useState<TokenView | null>(null);

  const closeCreate = () => {
    setCreating(false);
    setMinted(null);
  };

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          Keys authenticate as you, narrowed to no more than your own grants.
        </Text>
        <Button size="xs" onClick={() => setCreating(true)}>
          New key
        </Button>
      </Group>

      {tokens.isError ? (
        <Alert color="red" title="Your keys could not be loaded">
          <Text size="sm">{tokens.error.message}</Text>
          <Text size="sm">Reload the page to try again.</Text>
        </Alert>
      ) : tokens.data?.length === 0 ? (
        <Text size="sm" c="dimmed">
          You have no keys. A key lets a script or an assistant act as you, with the permissions you choose, until it
          expires or you revoke it.
        </Text>
      ) : (
        <KeysTable tokens={tokens.data ?? []} onRotate={setRotating} onRevoke={setRevoking} onUsage={setUsageOf} />
      )}

      <Modal opened={creating} onClose={closeCreate} title="New API key" size="lg">
        {minted ? <OneTimeSecret value={minted.value} /> : <MintKeyForm onMinted={setMinted} />}
      </Modal>

      <RotateModal token={rotating} onClose={() => setRotating(null)} />
      <RevokeModal token={revoking} onClose={() => setRevoking(null)} />

      <Drawer
        opened={usageOf !== null}
        onClose={() => setUsageOf(null)}
        position="right"
        title={usageOf ? `Usage of ${usageOf.name}` : ''}
      >
        {usageOf ? <TokenUsagePanel scope="own" tokenId={usageOf.id} /> : null}
      </Drawer>
    </Stack>
  );
}

function KeysTable({
  tokens,
  onRotate,
  onRevoke,
  onUsage,
}: {
  tokens: TokenView[];
  onRotate: (t: TokenView) => void;
  onRevoke: (t: TokenView) => void;
  onUsage: (t: TokenView) => void;
}) {
  return (
    <Table>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Name</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Expires</Table.Th>
          <Table.Th>Last used</Table.Th>
          <Table.Th>MCP tools</Table.Th>
          <Table.Th>
            <VisuallyHidden>Actions</VisuallyHidden>
          </Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {tokens.map((t) => (
          <Table.Tr key={t.id}>
            <Table.Td>
              <Text size="sm">{t.name}</Text>
              <Text size="xs" ff="monospace" c="dimmed">
                {t.prefix}
              </Text>
            </Table.Td>
            <Table.Td>
              <TokenStatus token={t} />
            </Table.Td>
            <Table.Td>
              <Text size="xs">{formatInstant(t.expiresAt)}</Text>
              {t.previousValidUntil && Date.parse(t.previousValidUntil) > serverNow() ? (
                <Text size="xs" c="dimmed">
                  Old secret works until {formatInstant(t.previousValidUntil)}
                </Text>
              ) : null}
            </Table.Td>
            <Table.Td>
              <Text size="xs" c="dimmed">
                {formatInstant(t.lastUsedAt)}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="xs" c="dimmed">
                {t.mcpTools.length === 0 ? 'Every tool' : t.mcpTools.join(', ')}
              </Text>
            </Table.Td>
            <Table.Td>
              <Group gap={4} wrap="nowrap">
                <Tooltip label="Usage">
                  <ActionIcon variant="subtle" onClick={() => onUsage(t)} aria-label={`Usage of ${t.name}`}>
                    <IconChartBar size={16} />
                  </ActionIcon>
                </Tooltip>
                {!t.revokedAt && Date.parse(t.expiresAt) > serverNow() ? (
                  <>
                    <Tooltip label="Rotate">
                      <ActionIcon variant="subtle" onClick={() => onRotate(t)} aria-label={`Rotate ${t.name}`}>
                        <IconRefresh size={16} />
                      </ActionIcon>
                    </Tooltip>
                    <Tooltip label="Revoke">
                      <ActionIcon
                        variant="subtle"
                        color="red"
                        onClick={() => onRevoke(t)}
                        aria-label={`Revoke ${t.name}`}
                      >
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Tooltip>
                  </>
                ) : null}
              </Group>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function RotateModal({ token, onClose }: { token: TokenView | null; onClose: () => void }) {
  const rotate = useRotateToken();
  const close = () => {
    rotate.reset();
    onClose();
  };
  const rotated = rotate.data;
  return (
    <Modal opened={token !== null} onClose={close} title={token ? `Rotate ${token.name}` : ''}>
      {rotated ? (
        <OneTimeSecret
          value={rotated.value}
          note={`The old secret keeps working until ${formatInstant(rotated.token.previousValidUntil)}. Replace it everywhere before then.`}
        />
      ) : (
        <Stack gap="sm">
          <Text size="sm">
            A new secret replaces this key&apos;s current one. The current secret keeps working for the rotation
            overlap, so you can update whatever uses it. The key keeps its permissions and its expiry.
          </Text>
          {rotate.isError ? (
            <Alert color="red" title="The key was not rotated">
              {rotate.error.message}
            </Alert>
          ) : null}
          <Button loading={rotate.isPending} onClick={() => token && rotate.mutate(token.id)}>
            Rotate
          </Button>
        </Stack>
      )}
    </Modal>
  );
}

function RevokeModal({ token, onClose }: { token: TokenView | null; onClose: () => void }) {
  const revoke = useRevokeToken();
  return (
    <Modal opened={token !== null} onClose={onClose} title={token ? `Revoke ${token.name}` : ''}>
      {token ? (
        <Stack gap="sm">
          <Text size="sm">
            Every script or assistant using this key stops working with its next request. This cannot be undone.
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
