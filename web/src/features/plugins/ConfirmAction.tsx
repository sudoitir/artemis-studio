import type { ReactNode } from 'react';
import { Stack } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { needsReauthentication } from '../../kernel/auth/api.ts';
import { useFreshSignIn } from '../../kernel/auth/freshSignIn.ts';
import { StepUp } from '../../kernel/auth/StepUp.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { Refusal } from './Refusal.tsx';

/**
 * One plugin lifecycle action, confirmed (non-negotiable #2, ADR-0103): what it affects first,
 * then a fresh sign-in, then — for anything that removes something — the plugin's id typed. The
 * button names the exact action and stays busy while it runs; a refusal says why, beside it.
 *
 * <p>The button is never silently dead: until the session is fresh it is disabled, and the reason
 * is stated beside it.
 */
export function ConfirmAction({
  opened,
  onClose,
  title,
  children,
  confirmLabel,
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
  danger?: boolean;
  pending: boolean;
  error: ApiError | null;
  /** Where a sign-in at the identity provider comes back to; defaults to this page. */
  returnTo?: string;
  onConfirm: () => void;
}>) {
  const fresh = useFreshSignIn();
  const refused = error !== null && !needsReauthentication(error);
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={title}
      consequence={
        <Stack gap="md">
          {children}
          <StepUp returnTo={returnTo ?? `${globalThis.location.pathname}${globalThis.location.search}`} />
          {refused ? <Refusal error={error} /> : null}
        </Stack>
      }
      confirmLabel={confirmLabel}
      tone={danger ? 'danger' : 'default'}
      pending={pending}
      blocked={fresh ? undefined : 'Confirm it is you above first.'}
      onConfirm={onConfirm}
    />
  );
}
