import { useState, type ReactNode } from 'react';
import { Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { useFreshSignIn } from '../../kernel/auth/freshSignIn.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { Refusal } from './Notice.tsx';

/**
 * One plugin lifecycle action, confirmed (non-negotiable #2, ADR-0103): what it affects first,
 * then a fresh sign-in, then — for anything that removes something — the plugin's id typed. The
 * button names the exact action and stays busy while it runs; a refusal says why, beside it.
 *
 * <p>The button is never dead: pressed before the session is fresh, it says so beside the sign-in
 * above it and sends nothing.
 */
export function ConfirmAction({
  opened,
  onClose,
  title,
  children,
  confirmLabel,
  typeToConfirm,
  danger,
  pending,
  error,
  returnTo,
  onConfirm,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  /** Required for destructive actions: the plugin's id. */
  typeToConfirm?: string;
  danger?: boolean;
  pending: boolean;
  error: ApiError | null;
  /** Where a sign-in at the identity provider comes back to; defaults to this page. */
  returnTo?: string;
  onConfirm: () => void;
}>) {
  const fresh = useFreshSignIn();
  const [tried, setTried] = useState(false);
  const refused = error !== null && !needsReauthentication(error);
  const close = () => {
    setTried(false);
    onClose();
  };
  return (
    <ConfirmDialog
      opened={opened}
      onClose={close}
      title={title}
      consequence={
        <Stack gap="md">
          {children}
          <StepUp returnTo={returnTo ?? `${globalThis.location.pathname}${globalThis.location.search}`} />
          {refused ? <Refusal error={error} /> : null}
          <div role="status">
            {tried && !fresh ? <Text size="sm">Confirm it is you above first; nothing was sent.</Text> : null}
          </div>
        </Stack>
      }
      confirmLabel={confirmLabel}
      tone={danger ? 'danger' : 'default'}
      typedName={typeToConfirm}
      pending={pending}
      onConfirm={() => {
        if (!fresh) {
          setTried(true);
          return;
        }
        onConfirm();
      }}
    />
  );
}
