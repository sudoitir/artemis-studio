import { useState } from 'react';
import { Button, Group, Text, VisuallyHidden } from '@mantine/core';
import { useBlocker } from '@tanstack/react-router';

import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { heldOf } from '../../ui/held.ts';
import { Notice } from '../../ui/Notice.tsx';
import { useSettingsDraft } from './draftContext.ts';
import { plural } from './model.ts';
import { ReviewDialog } from './ReviewDialog.tsx';
import type { ApplyFlow } from './useApplyFlow.ts';
import classes from './Settings.module.css';

/**
 * The sticky footer that appears with the first edit: how much is unsaved and where, what applying it would
 * do, and Discard, Review and the primary action. It also guards leaving the page with unsaved edits.
 */
export function ApplyBar({ state }: Readonly<{ state: ApplyFlow }>) {
  const draft = useSettingsDraft();
  const [announcement, setAnnouncement] = useState('');
  const { primary, apply, review, setReview, openReview, submit, rows } = state;
  const count = draft.changes.length;
  const dirty = count > 0;

  // Changing tab or search stays on this page and keeps the draft; anything else asks first.
  const blocker = useBlocker({
    shouldBlockFn: ({ current, next }) => dirty && current.pathname !== next.pathname,
    enableBeforeUnload: () => dirty,
    withResolver: true,
  });

  const outcome = draft.previewCurrent ? draft.preview?.outcome : undefined;
  const denied = draft.preview?.outcome === 'DENY' ? draft.preview : undefined;
  const hold = draft.preview?.outcome === 'HOLD';
  const invalid = draft.invalidKeys.length;

  let status: string;
  if (invalid > 0) {
    status = `${plural(invalid, 'value needs', 'values need')} fixing before the changes can apply.`;
  } else if (draft.previewError) {
    status = 'Studio could not check whether these changes need approval. Applying still asks the server.';
  } else if (!outcome) {
    status = 'Checking what applying would do…';
  } else if (outcome === 'HOLD') {
    status = draft.preview?.policyLabel
      ? `Needs approval under “${draft.preview.policyLabel}”.`
      : 'Needs approval by a second person.';
  } else if (outcome === 'RUN') {
    status = 'Applies at once, with no restart.';
  } else {
    status = 'Not allowed.';
  }

  const discard = () => {
    draft.discard();
    setAnnouncement(`Discarded ${plural(count, 'unsaved change', 'unsaved changes')}.`);
  };

  return (
    <>
      <VisuallyHidden role="status">{announcement}</VisuallyHidden>
      {dirty ? (
        <section className={classes.footer} aria-label="Unsaved changes">
          {denied ? (
            <Notice tone="warning" title="These changes are not allowed">
              {denied.denyReason ?? 'An approval policy refuses this change set.'} Undo the changes it refuses, or
              discard the draft.
            </Notice>
          ) : null}
          <div className={classes.footerRow}>
            <div className={classes.footerSummary}>
              <Text size="sm" fw={600}>
                {plural(count, 'unsaved change', 'unsaved changes')} in{' '}
                {plural(draft.changedIn.size, 'category', 'categories')}
              </Text>
              <Text size="xs" c="dimmed" role="status">
                {status}
              </Text>
            </div>
            <Group gap="sm" wrap="nowrap">
              <Button variant="subtle" size="sm" onClick={discard} disabled={apply.isPending}>
                Discard
              </Button>
              <Button variant="default" size="sm" onClick={openReview} disabled={Boolean(denied) || apply.isPending}>
                Review
              </Button>
              <Button size="sm" onClick={primary} loading={apply.isPending} disabled={Boolean(denied)}>
                {hold ? 'Request approval…' : `Apply ${plural(count, 'change', 'changes')}`}
              </Button>
            </Group>
          </div>
        </section>
      ) : null}
      <ReviewDialog
        opened={review !== null}
        onClose={() => setReview(null)}
        rows={rows}
        mode={review?.mode ?? 'apply'}
        preview={draft.preview}
        reasonRequired={review?.reasonRequired ?? false}
        pending={apply.isPending}
        error={review && apply.error && !heldOf(apply.error) ? apply.error : null}
        onSubmit={submit}
      />
      <ConfirmDialog
        opened={blocker.status === 'blocked'}
        onClose={() => blocker.reset?.()}
        title="Leave with unsaved changes?"
        consequence={
          <Text size="sm">
            {plural(count, 'setting change is', 'setting changes are')} not applied yet. Leaving discards{' '}
            {count === 1 ? 'it' : 'them'}; stay to apply or review {count === 1 ? 'it' : 'them'} first.
          </Text>
        }
        confirmLabel="Discard and leave"
        dismissLabel="Stay on this page"
        tone="danger"
        onConfirm={() => blocker.proceed?.()}
      />
    </>
  );
}
