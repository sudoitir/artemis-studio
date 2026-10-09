import { useEffect, useMemo } from 'react';
import { Button, Modal, Stack, Text, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';

import type { ApiError } from '../../kernel/api/request.ts';
import { DialogActions } from '../../ui/DialogActions.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { ChangePreview } from './api.ts';
import { reviewColumns, type ReviewRow } from './reviewColumns.ts';

/** What the review asks of its submit: apply now, or request approval with a reason. */
export type ReviewMode = 'apply' | 'request';

const REASON_NEEDED = 'Give a reason: the approval policy asks for one, and approvers read it to decide.';

/**
 * The draft before it goes: every change as Setting | Current | New, and, when a policy holds it, who decides
 * and a reason for them. The dialog stays open while the request is in flight and shows a failure in place.
 */
export function ReviewDialog({
  opened,
  onClose,
  rows,
  mode,
  preview,
  reasonRequired,
  pending,
  error,
  onSubmit,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  rows: ReviewRow[];
  mode: ReviewMode;
  preview: ChangePreview | undefined;
  /** A reason is required: the preview said so, or the server refused a request without one. */
  reasonRequired: boolean;
  pending: boolean;
  error: ApiError | null;
  onSubmit: (reason: string | undefined) => void;
}>) {
  const form = useForm({
    initialValues: { reason: '' },
    validateInputOnBlur: true,
    validate: { reason: (value) => (reasonRequired && !value.trim() ? REASON_NEEDED : null) },
  });
  // A dialog opened again starts with an empty reason, not the one written for an earlier draft.
  useEffect(() => {
    if (opened) form.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  const columns = useMemo(reviewColumns, []);
  const count = rows.length === 1 ? '1 change' : `${rows.length} changes`;
  const requesting = mode === 'request';
  const submit = form.onSubmit(
    ({ reason }) => onSubmit(requesting ? reason.trim() || undefined : undefined),
    () => form.getInputNode('reason')?.focus(),
  );

  return (
    <Modal
      opened={opened}
      onClose={() => {
        if (!pending) onClose();
      }}
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
      size="xl"
      title={requesting ? `Request approval for ${count}` : `Review ${count}`}
      closeButtonProps={{ 'aria-label': 'Close the review' }}
    >
      <form noValidate onSubmit={submit}>
        <Stack gap="md">
          {requesting ? (
            <Notice tone="info" title="Needs approval">
              {preview?.policyLabel ? `The policy “${preview.policyLabel}” holds` : 'An approval policy holds'} these
              changes until a second person approves them. Nothing changes until then, and you can cancel the request
              while it waits.
            </Notice>
          ) : (
            <Text size="sm">These settings change together as soon as you apply them, with no restart.</Text>
          )}
          <DataTable
            variant="static"
            columnsMenu={false}
            label={requesting ? 'Changes to request' : 'Changes to apply'}
            columns={columns}
            data={rows}
            rowKey={(row) => row.key}
            height={{ maxRows: rows.length }}
            empty={null}
          />
          {requesting ? (
            <Textarea
              label="Reason"
              description="Approvers read this. Say what the change is for and why now."
              autosize
              minRows={2}
              maxRows={6}
              withAsterisk={reasonRequired}
              // Focus enters on the one thing to write, not on the dialog's close button.
              data-autofocus
              {...form.getInputProps('reason')}
            />
          ) : null}
          {error ? (
            <ErrorState variant="inline" error={error} next="Your changes are still in the draft. Try again." />
          ) : null}
          <DialogActions>
            <Button variant="default" onClick={onClose} disabled={pending}>
              Back to editing
            </Button>
            <Button type="submit" loading={pending}>
              {requesting ? 'Request approval' : `Apply ${count}`}
            </Button>
          </DialogActions>
        </Stack>
      </form>
    </Modal>
  );
}
