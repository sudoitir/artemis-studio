import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';

import { problemSlug } from '../../kernel/approvals/api.ts';
import { ApiError } from '../../kernel/api/request.ts';
import { heldOf } from '../../ui/held.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { previewQuery, SETTINGS_KEY, useApplyChanges, type ChangePreview, type SettingsResponse } from './api.ts';
import { useSettingsDraft } from './draftContext.ts';
import { changedTo, plural, type Category } from './model.ts';
import type { ReviewMode } from './ReviewDialog.tsx';
import type { ReviewRow } from './reviewColumns.ts';
import type { SettingsSearch } from './SettingsPage.tsx';

const APPLY: ActionVerb = { verb: 'Apply', past: 'Applied', progressive: 'Applying' };

/**
 * Applying the draft. The primary action follows the latest preview: it applies at once when the change
 * would run, opens the review to ask for approval when a policy holds it, and is disabled with the policy's
 * reason when it would be denied. An invalid value stops it, opens the value's category and focuses it. Enter
 * in a field opens the review instead, so a keystroke never applies the draft unseen.
 */
export function useApplyFlow(categories: Category[], onSettled: () => void) {
  const draft = useSettingsDraft();
  const apply = useApplyChanges();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [review, setReview] = useState<{ mode: ReviewMode; reasonRequired: boolean } | null>(null);
  const count = draft.changes.length;
  const subject = plural(count, 'setting change', 'setting changes');

  // Opens the category of the first invalid value and puts the cursor in it.
  const focusInvalid = (keys: string[]) => {
    const key = keys[0];
    const category = key ? draft.settings[key]?.category : undefined;
    if (!key || !category) return;
    void navigate({
      to: '.',
      search: (prev: SettingsSearch) => ({ ...prev, tab: category, q: undefined, modified: undefined }),
      replace: true,
    }).then(() => requestAnimationFrame(() => draft.focus(key)));
  };

  const submit = (reason: string | undefined) => {
    const changes = draft.changes;
    apply.mutate(
      { changes, reason },
      {
        onSuccess: () => {
          setReview(null);
          draft.discard(qc.getQueryData<SettingsResponse>(SETTINGS_KEY)?.settings);
          notify.succeeded({ action: APPLY, subject: plural(changes.length, 'setting change', 'setting changes') });
          onSettled();
        },
        onError: (error) => {
          const held = heldOf(error);
          if (held) {
            setReview(null);
            draft.discard(qc.getQueryData<SettingsResponse>(SETTINGS_KEY)?.settings);
            notify.held(held);
            onSettled();
            return;
          }
          if (error instanceof ApiError && error.fieldErrors.length > 0) {
            setReview(null);
            const errors = Object.fromEntries(error.fieldErrors.map((e) => [e.field, e.message]));
            draft.setApplyErrors(errors);
            focusInvalid(changes.map((c) => c.key).filter((key) => key in errors));
            return;
          }
          if (problemSlug(error) === 'approval-reason-required') {
            setReview({ mode: 'request', reasonRequired: true });
            return;
          }
          // In the review, the failure shows in place; from the footer, it is a toast.
          if (!review) {
            notify.settle(error, {
              action: APPLY,
              subject,
              cause: error.message,
              next: 'Your changes are still in the draft. Try again.',
            });
          }
        },
      },
    );
  };

  const primary = async () => {
    if (count === 0 || apply.isPending) return;
    let preview: ChangePreview | undefined = draft.previewCurrent ? draft.preview : undefined;
    if (!preview) {
      try {
        preview = await qc.query(previewQuery(draft.changes));
      } catch {
        // The preview is advice; the server decides when the change set arrives.
        preview = undefined;
      }
    }
    const invalid = draft.revealAll(preview);
    if (invalid.length > 0) {
      focusInvalid(invalid);
      return;
    }
    if (preview?.outcome === 'DENY') return;
    if (preview?.outcome === 'HOLD') {
      apply.reset();
      setReview({ mode: 'request', reasonRequired: preview.reasonRequired });
      return;
    }
    submit(undefined);
  };

  const openReview = () => {
    apply.reset();
    const hold = draft.preview?.outcome === 'HOLD';
    setReview({ mode: hold ? 'request' : 'apply', reasonRequired: hold && Boolean(draft.preview?.reasonRequired) });
  };

  // Enter in a field never applies the whole draft: it shows every change first, after the values are valid.
  const reviewFromField = () => {
    if (count === 0 || apply.isPending) return;
    const invalid = draft.revealAll();
    if (invalid.length > 0) {
      focusInvalid(invalid);
      return;
    }
    if (draft.previewCurrent && draft.preview?.outcome === 'DENY') return;
    openReview();
  };

  const titles = new Map(categories.map((c) => [c.id, c.title]));
  const rows: ReviewRow[] = draft.changes.flatMap((change) => {
    const setting = draft.settings[change.key];
    if (!setting) return [];
    return [
      {
        key: change.key,
        setting: setting.label,
        category: titles.get(setting.category) ?? setting.categoryTitle,
        current: setting.value,
        next: changedTo(change, setting),
      },
    ];
  });

  // `primary` is the footer's primary button alone; Enter in a field is `reviewFromField`.
  return { primary: () => void primary(), apply, review, setReview, openReview, reviewFromField, submit, rows };
}

export type ApplyFlow = ReturnType<typeof useApplyFlow>;
