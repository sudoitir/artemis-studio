import { useState } from 'react';
import { Button, Drawer, Modal, Stack, Text } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DialogActions } from '../../ui/DialogActions.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { DataTable } from '../../ui/table/index.ts';
import { useRevokeToken, useRotateToken, useTokens, type CreatedTokenView, type TokenView } from './api.ts';
import { keyColumns } from './columns.ts';
import { instantLabel } from './format.ts';
import { MintKeyForm } from './MintKeyForm.tsx';
import { OneTimeSecret, TokenUsagePanel } from './TokenParts.tsx';

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
    controls: {
      onUsage: setUsageOf,
      onRotate: setRotating,
      onRevoke: (t) => {
        setRevoking(t);
        setRevokeOpen(true);
      },
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

      <Modal
        opened={creating}
        onClose={closeCreate}
        title="New API key"
        size="lg"
        // While the secret shows, only "I've copied the key" closes the dialog.
        closeOnEscape={!minted}
        closeOnClickOutside={!minted}
        withCloseButton={!minted}
      >
        {minted ? (
          <OneTimeSecret value={minted.value} onDone={closeCreate} />
        ) : (
          <MintKeyForm onMinted={setMinted} onCancel={closeCreate} />
        )}
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
        notify.settle(error, {
          action: ROTATE,
          subject: `key "${t.name}"`,
          cause: error.message,
          next: 'The current secret still works. Try again.',
        }),
    });

  return (
    <Modal
      opened={token !== null}
      onClose={close}
      title={token ? `Rotate ${token.name}` : ''}
      closeOnEscape={!rotated && !rotate.isPending}
      closeOnClickOutside={!rotated && !rotate.isPending}
      withCloseButton={!rotated}
      centered={!rotated}
    >
      {rotated ? (
        <OneTimeSecret
          value={rotated.value}
          note={`The old secret keeps working until ${instantLabel(rotated.token.previousValidUntil)}. Replace it everywhere before then.`}
          onDone={close}
        />
      ) : (
        <Stack gap="sm">
          <Text size="sm">
            A new secret replaces this key&apos;s current one. The current secret keeps working for the rotation
            overlap, so you can update whatever uses it. The key keeps its permissions and its expiry.
          </Text>
          <DialogActions>
            <Button variant="default" disabled={rotate.isPending} onClick={close}>
              Cancel
            </Button>
            <Button loading={rotate.isPending} onClick={() => token && confirm(token)}>
              Rotate key
            </Button>
          </DialogActions>
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
        notify.settle(error, {
          action: REVOKE,
          subject: `key "${t.name}"`,
          cause: error.message,
          next: 'The key still works. Try again.',
          onHeld: close,
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
