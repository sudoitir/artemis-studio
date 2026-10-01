import type { ReactNode } from 'react';
import { Button, Group, Modal, Stack, Text, useMantineTheme } from '@mantine/core';

import { ConfirmByTyping } from './ConfirmByTyping.tsx';

/**
 * Confirmation before an action that cannot be taken back (non-negotiable #2). The dialog states
 * the consequence first and names the action on its button.
 *
 * <ul>
 *   <li>With `typedName` the button arms only when that name is typed, through
 *       {@link ConfirmByTyping}; use it for anything that removes a resource.
 *   <li>Focus enters the dialog (the cancel button, or the name field when one is asked for),
 *       Escape closes it, and focus returns to the control that opened it.
 *   <li>While `pending` the button is busy and cannot be pressed again, and the dialog stays
 *       open: Escape, the overlay and Cancel are inert until the caller settles it.
 * </ul>
 */
export function ConfirmDialog({
  opened,
  onClose,
  title,
  consequence,
  confirmLabel,
  tone = 'default',
  typedName,
  pending = false,
  onConfirm,
}: ConfirmDialogProps) {
  const theme = useMantineTheme();
  const color = tone === 'danger' ? 'signal' : theme.primaryColor;
  const cancel = (
    <Button variant="default" data-autofocus={typedName ? undefined : true} disabled={pending} onClick={onClose}>
      Cancel
    </Button>
  );
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={title}
      centered
      withCloseButton={false}
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
    >
      <Stack gap="md" aria-busy={pending}>
        <Text component="div" size="sm">
          {consequence}
        </Text>
        {typedName ? (
          <>
            <ConfirmByTyping
              token={typedName}
              confirmLabel={confirmLabel}
              color={color}
              loading={pending}
              onConfirm={onConfirm}
            />
            <Group justify="flex-end">{cancel}</Group>
          </>
        ) : (
          <Group justify="flex-end">
            {cancel}
            <Button color={color} loading={pending} onClick={onConfirm}>
              {confirmLabel}
            </Button>
          </Group>
        )}
      </Stack>
    </Modal>
  );
}

export type ConfirmDialogProps = Readonly<{
  opened: boolean;
  onClose: () => void;
  /** The action as a plain line, such as "Delete queue". */
  title: string;
  /** What it affects: the resource, the nodes and how much data goes. */
  consequence: ReactNode;
  /** The exact action, such as "Delete queue". */
  confirmLabel: string;
  /** `danger` for an action that removes or overwrites; defaults to `default`. */
  tone?: 'default' | 'danger';
  /** The resource's name, typed to arm the button. Required for a removal. */
  typedName?: string;
  /** The action is running: the button is busy and the dialog cannot be dismissed. */
  pending?: boolean;
  onConfirm: () => void;
}>;
