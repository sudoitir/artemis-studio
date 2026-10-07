import { useState } from 'react';
import { Button, Stack, Text } from '@mantine/core';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { useCancelHeld, type HeldOperationDetail } from './api.ts';
import { Recap } from './Recap.tsx';

const CANCEL: ActionVerb = { verb: 'Cancel', past: 'Cancelled', progressive: 'Cancelling' };

/**
 * The requester's "Cancel request", confirmed in a dialog that names the request and says what cancelling does.
 * With the request's `detail` the dialog repeats what it would have done; a list passes only its summary.
 */
export function CancelRequest({
  id,
  summary,
  detail,
  size = 'sm',
}: Readonly<{ id: string; summary: string; detail?: HeldOperationDetail; size?: 'xs' | 'sm' }>) {
  const [open, setOpen] = useState(false);
  const cancel = useCancelHeld();
  const confirm = () =>
    cancel.mutate(id, {
      onSuccess: () => {
        setOpen(false);
        notify.succeeded({ action: CANCEL, subject: `request "${summary}"` });
      },
    });
  return (
    <>
      <Button
        variant="default"
        size={size}
        aria-label={detail ? undefined : `Cancel request "${summary}"`}
        onClick={() => {
          cancel.reset();
          setOpen(true);
        }}
      >
        Cancel request
      </Button>
      <ConfirmDialog
        opened={open}
        onClose={() => setOpen(false)}
        title="Cancel this request?"
        confirmLabel="Cancel request"
        pending={cancel.isPending}
        consequence={
          <Stack gap="sm">
            {detail ? (
              <Recap detail={detail} />
            ) : (
              <Text size="sm" fw={600}>
                {summary}
              </Text>
            )}
            <Text size="sm">
              It will not run, and its approvers are told it was cancelled. To do it later, request it again.
            </Text>
            {cancel.error ? (
              <ErrorState
                variant="inline"
                error={cancel.error}
                next="The request is unchanged. Reload it to see whether it was decided meanwhile."
              />
            ) : null}
          </Stack>
        }
        onConfirm={confirm}
      />
    </>
  );
}
