import type { ReactNode } from 'react';
import { Alert, Button, Group, Modal, Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { needsReauthentication, violationsOf } from './api.ts';
import { useFreshSignIn } from './freshSignIn.ts';
import { StepUp } from './StepUp.tsx';

/**
 * One plugin lifecycle action, confirmed (non-negotiable #2, ADR-0103): what it affects first,
 * then a fresh sign-in, then — for anything that removes something — the plugin's id typed. The
 * button names the exact action and stays busy while it runs; a refusal says why, beside it.
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
  onConfirm,
}: {
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
  onConfirm: () => void;
}) {
  const fresh = useFreshSignIn();
  const refusal = error && !needsReauthentication(error)
    ? violationsOf(error).map((v) => [v.message, v.fix].filter(Boolean).join(' ')).join(' ') || error.message
    : null;
  return (
    <Modal opened={opened} onClose={onClose} title={title}>
      <Stack gap="md">
        {children}
        <StepUp returnTo={`${window.location.pathname}${window.location.search}`} />
        {refusal ? (
          <Alert color="red" variant="light" role="alert" title="Not done">
            {refusal}
          </Alert>
        ) : null}
        {typeToConfirm ? (
          <ConfirmByTyping
            token={typeToConfirm}
            confirmLabel={confirmLabel}
            color={danger ? 'red' : 'blue'}
            loading={pending}
            disabled={!fresh}
            onConfirm={onConfirm}
          />
        ) : (
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button color={danger ? 'red' : undefined} loading={pending} disabled={!fresh} onClick={onConfirm}>
              {confirmLabel}
            </Button>
          </Group>
        )}
        {!fresh ? (
          <Text size="xs" c="dimmed">
            Confirm it is you above first.
          </Text>
        ) : null}
      </Stack>
    </Modal>
  );
}
