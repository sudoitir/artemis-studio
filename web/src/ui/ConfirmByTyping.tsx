import { useState, type ReactNode, type SubmitEvent } from 'react';
import { Button, Code, Stack, TextInput } from '@mantine/core';

import { DialogActions } from './DialogActions.tsx';

/**
 * Typed-name confirmation for a destructive action (non-negotiable #2). The
 * button arms only on an exact match of {@code token}, and Enter in the field
 * confirms once it is armed. Given `dismiss`, the confirm button sits beside it
 * in one {@link DialogActions} row, at the same size.
 */
export function ConfirmByTyping({
  token,
  label,
  confirmLabel,
  loading,
  disabled,
  describedBy,
  tone = 'danger',
  dismiss,
  onConfirm,
}: Readonly<{
  token: string;
  label?: string;
  confirmLabel: string;
  loading?: boolean;
  disabled?: boolean;
  /** The id of text that says why the button is disabled, for assistive technology. */
  describedBy?: string;
  /** `danger` for an action that removes or overwrites (the default here); `default` for one that does not. */
  tone?: 'default' | 'danger';
  /** The button that dismisses without acting, placed before the confirm button in one row. */
  dismiss?: ReactNode;
  onConfirm: () => void;
}>) {
  const [typed, setTyped] = useState('');
  const armed = typed === token && !disabled;
  const size = dismiss ? 'sm' : 'xs';

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    // A dialog is portalled out of any form it was opened from, but React still bubbles the submit to it.
    event.stopPropagation();
    if (armed && !loading) onConfirm();
  };

  const confirm = (
    <Button
      type="submit"
      size={dismiss ? undefined : 'xs'}
      color={tone === 'danger' ? 'signal' : undefined}
      disabled={!armed}
      aria-describedby={describedBy}
      loading={loading}
    >
      {confirmLabel}
    </Button>
  );

  return (
    <form noValidate onSubmit={submit}>
      <Stack gap={dismiss ? 'md' : 'xs'}>
        <TextInput
          label={
            label ?? (
              <>
                Type <Code>{token}</Code> to confirm
              </>
            )
          }
          aria-label={label ?? `Type "${token}" to confirm`}
          value={typed}
          onChange={(e) => setTyped(e.currentTarget.value)}
          size={size}
          autoComplete="off"
          spellCheck={false}
        />
        {dismiss ? (
          <DialogActions>
            {dismiss}
            {confirm}
          </DialogActions>
        ) : (
          confirm
        )}
      </Stack>
    </form>
  );
}
