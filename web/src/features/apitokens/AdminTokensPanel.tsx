import { useState } from 'react';
import { Drawer } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { useCan } from '../../kernel/auth/useCan.ts';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import linkClasses from '../../ui/InlineLink.module.css';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useAdminRevokeToken, useAdminTokens, type TokenView } from './api.ts';
import { adminKeyColumns } from './columns.ts';
import { TokenUsagePanel } from './TokenParts.tsx';

const TOKEN_ADMIN = 'token:admin';

const REVOKE: ActionVerb = { verb: 'Revoke', past: 'Revoked', progressive: 'Revoking' };

const rowKey = (t: TokenView) => t.id;

/**
 * Every user's API keys, metadata only, so a leaked key can be revoked without its owner
 * (identity-and-sessions spec, ADR-0136). Keys are minted and rotated only by their owners, on the
 * account page; there is no such action here.
 */
export function AdminTokensPanel() {
  const { can, loading } = useCan();
  if (!loading && !can(TOKEN_ADMIN)) {
    return (
      <Section title="API keys">
        <EmptyState
          kind="empty"
          title="You cannot see other users' keys"
          description={
            <>
              The key inventory needs the global permission &ldquo;See and revoke every user&apos;s API tokens&rdquo; (
              {TOKEN_ADMIN}). Ask an administrator to add it to one of your roles.
            </>
          }
        />
      </Section>
    );
  }
  return <Inventory />;
}

function Inventory() {
  const tokens = useAdminTokens();
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [revoking, setRevoking] = useState<TokenView | null>(null);
  const [revokeOpen, setRevokeOpen] = useState(false);
  const [usageOf, setUsageOf] = useState<TokenView | null>(null);

  const columns = adminKeyColumns({
    controls: {
      onUsage: setUsageOf,
      onRevoke: (t) => {
        setRevoking(t);
        setRevokeOpen(true);
      },
    },
  });

  return (
    <Section
      title="API keys"
      description="Every user's keys. A key unused for longer than the stale period (API tokens, in a cluster's Settings) is flagged."
    >
      <DataTable
        variant="static"
        label="Every user's API keys"
        storageKey="apitokens.admin"
        columns={columns}
        data={tokens.data ?? []}
        rowKey={rowKey}
        loading={tokens.isPending}
        error={tokens.isError ? <ErrorState error={tokens.error} onRetry={() => void tokens.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No user has a key yet"
            description={
              <>
                Users mint keys for scripts and assistants on their{' '}
                <Link to="/account" className={linkClasses.link}>
                  account page
                </Link>
                .
              </>
            }
          />
        }
      />

      <RevokeKey token={revoking} opened={revokeOpen} onClose={() => setRevokeOpen(false)} />
      <Drawer
        opened={usageOf !== null}
        onClose={() => setUsageOf(null)}
        position="right"
        title={usageOf ? `Usage of ${usageOf.name} (${usageOf.owner})` : ''}
      >
        {usageOf ? <TokenUsagePanel scope="admin" tokenId={usageOf.id} /> : null}
      </Drawer>
    </Section>
  );
}

/** States what revoking does to the owner before it can be armed, then asks for the key's name. */
function RevokeKey({
  token,
  opened,
  onClose,
}: Readonly<{ token: TokenView | null; opened: boolean; onClose: () => void }>) {
  const revoke = useAdminRevokeToken();
  const close = () => {
    revoke.reset();
    onClose();
  };

  const confirm = (t: TokenView) =>
    revoke.mutate(t.id, {
      onSuccess: () => {
        close();
        notify.succeeded({ action: REVOKE, subject: `${t.owner}'s key "${t.name}"` });
      },
      onError: (error) =>
        notify.settle(error, {
          action: REVOKE,
          subject: `${t.owner}'s key "${t.name}"`,
          cause: error.message,
          next: 'The key still works. Try again.',
          onHeld: close,
        }),
    });

  return (
    <ConfirmDialog
      opened={opened}
      onClose={close}
      title={token ? `Revoke ${token.owner}'s key ${token.name}` : 'Revoke key'}
      tone="danger"
      pending={revoke.isPending}
      confirmLabel="Revoke key"
      consequence={
        token
          ? `Every request with this key is refused from the next one on, and ${token.owner} sees it as revoked. The revocation is audited. It cannot be undone; ${token.owner} can mint a new key.`
          : ''
      }
      onConfirm={() => token && confirm(token)}
    />
  );
}
