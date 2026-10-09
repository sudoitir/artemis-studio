import { useMemo } from 'react';
import { Button, Group, Modal, Stack, Text } from '@mantine/core';

import type { ApiError } from '../../kernel/api/request.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import { DataTable } from '../../ui/table/index.ts';
import type { ChangePreview } from './api.ts';
import { reviewColumns, type ReviewRow } from './reviewColumns.ts';

/** What the review asks of its submit: apply now, or request approval. */
export type ReviewMode = 'apply' | 'request';

/**
 * The draft before it goes: every change as Setting | Current | New, and, when a policy holds it, that a second
 * person decides. The dialog stays open while the request is in flight and shows a failure in place.
 */
export function ReviewDialog({
  opened,
  onClose,
  rows,
  mode,
  preview,
  pending,
  error,
  onSubmit,
}: Readonly<{
  opened: boolean;
  onClose: () => void;
  rows: ReviewRow[];
  mode: ReviewMode;
  preview: ChangePreview | undefined;
  pending: boolean;
  error: ApiError | null;
  onSubmit: () => void;
}>) {
  const columns = useMemo(reviewColumns, []);
  const count = rows.length === 1 ? '1 change' : `${rows.length} changes`;
  const requesting = mode === 'request';

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
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit();
        }}
      >
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
          {error ? (
            <ErrorState variant="inline" error={error} next="Your changes are still in the draft. Try again." />
          ) : null}
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={onClose} disabled={pending}>
              Back to editing
            </Button>
            <Button type="submit" loading={pending} data-autofocus>
              {requesting ? 'Request approval' : `Apply ${count}`}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
