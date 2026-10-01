import { useState } from 'react';
import { ActionIcon, Button, Drawer, Modal, Stack, Text, Tooltip } from '@mantine/core';
import { IconChartBar, IconRefresh, IconTrash } from '@tabler/icons-react';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import { useRevokeToken, useRotateToken, useTokens, type CreatedTokenView, type TokenView } from './api.ts';
import { keyColumns } from './columns.ts';
import { instantLabel, tokenState } from './format.ts';
import { MintKeyForm } from './MintKeyForm.tsx';
import { OneTimeSecret, TokenUsagePanel } from './TokenParts.tsx';
import classes from './TokenActions.module.css';

const ROTATE: ActionVerb = { verb: 'Rotate', past: 'Rotated', progressive: 'Rotating' };
const REVOKE: ActionVerb = { verb: 'Revoke', past: 'Revoked', progressive: 'Revoking' };

const rowKey = (t: TokenView) => t.id;

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
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [revoking, setRevoking] = useState<TokenView | null>(null);
  const [revokeOpen, setRevokeOpen] = useState(false);
  const [usageOf, setUsageOf] = useState<TokenView | null>(null);

  const closeCreate = () => {
    setCreating(false);
    setMinted(null);
  };

  const columns = keyColumns({
    actions: (t) => {
      const live = tokenState(t) === 'Active';
      return (
        <span className={classes.controls}>
          <Tooltip label="Usage">
            <ActionIcon variant="subtle" onClick={() => setUsageOf(t)} aria-label={`Usage of ${t.name}`}>
              <IconChartBar size="1rem" aria-hidden />
            </ActionIcon>
          </Tooltip>
          <Tooltip label="Rotate">
            <ActionIcon
              variant="subtle"
              disabled={!live}
              onClick={() => setRotating(t)}
              aria-label={`Rotate ${t.name}`}
            >
              <IconRefresh size="1rem" aria-hidden />
            </ActionIcon>
          </Tooltip>
          <Tooltip label="Revoke">
            <ActionIcon
              variant="subtle"
              disabled={!live}
              onClick={() => {
                setRevoking(t);
                setRevokeOpen(true);
              }}
              aria-label={`Revoke ${t.name}`}
            >
              <IconTrash size="1rem" aria-hidden />
            </ActionIcon>
          </Tooltip>
        </span>
      );
    },
  });

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        Keys authenticate as you, narrowed to no more than your own grants.
      </Text>

      <DataTable
        variant="static"
        // A long list scrolls in place, at the height its loading rows had, so the page below holds still.
        height={{ maxRows: 8 }}
        label="API keys"
        storageKey="apitokens.own"
        columns={columns}
        data={tokens.data ?? []}
        rowKey={rowKey}
        loading={tokens.isPending}
        error={tokens.isError ? <ErrorState error={tokens.error} onRetry={() => void tokens.refetch()} /> : undefined}
        toolbar={{ start: <Button onClick={() => setCreating(true)}>New key</Button> }}
        empty={
          <EmptyState
            kind="empty"
            title="You have no keys"
            description="A key lets a script or an assistant act as you, with the permissions you choose, until it expires or you revoke it."
          />
        }
      />

      <Modal opened={creating} onClose={closeCreate} title="New API key" size="lg">
        {minted ? <OneTimeSecret value={minted.value} /> : <MintKeyForm onMinted={setMinted} />}
      </Modal>

      <RotateModal token={rotating} onClose={() => setRotating(null)} />
      <RevokeKey token={revoking} opened={revokeOpen} onClose={() => setRevokeOpen(false)} />

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

function RotateModal({ token, onClose }: Readonly<{ token: TokenView | null; onClose: () => void }>) {
  const rotate = useRotateToken();
  const close = () => {
    rotate.reset();
    onClose();
  };
  const rotated = rotate.data;

  const confirm = (t: TokenView) =>
    rotate.mutate(t.id, {
      onSuccess: () => notify.succeeded({ action: ROTATE, subject: `key "${t.name}"` }),
      onError: (error) =>
        notify.failed({
          action: ROTATE,
          subject: `key "${t.name}"`,
          cause: error.message,
          next: 'The current secret still works. Try again.',
        }),
    });

  return (
    <Modal opened={token !== null} onClose={close} title={token ? `Rotate ${token.name}` : ''}>
      {rotated ? (
        <OneTimeSecret
          value={rotated.value}
          note={`The old secret keeps working until ${instantLabel(rotated.token.previousValidUntil)}. Replace it everywhere before then.`}
        />
      ) : (
        <Stack gap="sm">
          <Text size="sm">
            A new secret replaces this key&apos;s current one. The current secret keeps working for the rotation
            overlap, so you can update whatever uses it. The key keeps its permissions and its expiry.
          </Text>
          <Button loading={rotate.isPending} onClick={() => token && confirm(token)}>
            Rotate
          </Button>
        </Stack>
      )}
    </Modal>
  );
}

/** States what revoking stops before it can be armed, then asks for the key's name. */
function RevokeKey({
  token,
  opened,
  onClose,
}: Readonly<{ token: TokenView | null; opened: boolean; onClose: () => void }>) {
  const revoke = useRevokeToken();
  const close = () => {
    revoke.reset();
    onClose();
  };

  const confirm = (t: TokenView) =>
    revoke.mutate(t.id, {
      onSuccess: () => {
        close();
        notify.succeeded({ action: REVOKE, subject: `key "${t.name}"` });
      },
      onError: (error) =>
        notify.failed({
          action: REVOKE,
          subject: `key "${t.name}"`,
          cause: error.message,
          next: 'The key still works. Try again.',
        }),
    });

  return (
    <ConfirmDialog
      opened={opened}
      onClose={close}
      title={token ? `Revoke ${token.name}` : 'Revoke key'}
      tone="danger"
      typedName={token?.name}
      pending={revoke.isPending}
      confirmLabel="Revoke key"
      consequence="Every script or assistant using this key stops working with its next request. This cannot be undone."
      onConfirm={() => token && confirm(token)}
    />
  );
}
