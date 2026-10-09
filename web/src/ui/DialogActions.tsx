import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, Group } from '@mantine/core';

import { Notice } from './Notice.tsx';

/**
 * The row of buttons that ends a dialog: aligned to the inline end, the dismissing button first and the
 * primary action last, every button at the default size. The primary names its action ("Create user",
 * "Rotate key"), never "OK" or "Submit".
 *
 * Where a dialog sits: a confirmation, whose content is fixed, is `centered`; a form, a review or anything
 * that grows while it is used keeps Mantine's default top offset, so the dialog never moves under the cursor
 * as a message or a section appears.
 */
export function DialogActions({ children, className }: Readonly<{ children: ReactNode; className?: string }>) {
  return (
    <Group justify="flex-end" gap="sm" wrap="nowrap" className={className}>
      {children}
    </Group>
  );
}

/**
 * Keeps a form dialog's unsaved input from being lost by a stray click or key. While `dirty`, a click outside
 * does nothing, and Escape or the close button asks inside the dialog whether to discard; a second Escape
 * keeps editing. Spread `modalProps` on the `Modal` and render `prompt` at the top of its body.
 */
export function useDiscardGuard(dirty: boolean, onClose: () => void) {
  const [asking, setAsking] = useState(false);
  if (asking && !dirty) setAsking(false);
  const request = () => {
    if (dirty) setAsking((current) => !current);
    else onClose();
  };
  const prompt = asking ? (
    <DiscardPrompt
      onKeep={() => setAsking(false)}
      onDiscard={() => {
        setAsking(false);
        onClose();
      }}
    />
  ) : null;
  return { modalProps: { onClose: request, closeOnClickOutside: !dirty }, prompt };
}

function DiscardPrompt({ onKeep, onDiscard }: Readonly<{ onKeep: () => void; onDiscard: () => void }>) {
  const keep = useRef<HTMLButtonElement>(null);
  useEffect(() => keep.current?.focus(), []);
  return (
    <Notice
      tone="warning"
      title="Discard changes?"
      action={
        <DialogActions>
          <Button ref={keep} variant="default" onClick={onKeep}>
            Keep editing
          </Button>
          <Button color="signal" onClick={onDiscard}>
            Discard changes
          </Button>
        </DialogActions>
      }
    >
      What you entered in this dialog has not been saved. Closing it now loses it.
    </Notice>
  );
}
