import { createContext, useContext } from 'react';

import type { ChangePreview, Setting, SettingChange } from './api.ts';
import type { DraftField } from './model.ts';

export interface DraftValues {
  fields: DraftField[];
}

export interface SettingsDraft {
  settings: Record<string, Setting>;
  /** Moves focus to a setting's value input. */
  focus: (key: string) => void;
  /** The draft field of a setting, and the form path of its value. */
  field: (key: string) => { field: DraftField; path: string } | undefined;
  /** Every change the draft makes, in the server's order. */
  changes: SettingChange[];
  /** The number of changes in each category. */
  changedIn: ReadonlyMap<string, number>;
  /** The message beside a setting: what is wrong with its value, from the form, the preview or the last apply. */
  errorOf: (key: string) => string | undefined;
  /** The settings in the draft whose value is wrong, in the server's order. */
  invalidKeys: string[];
  /** What applying the draft would do, from the latest preview; undefined until one answers. */
  preview: ChangePreview | undefined;
  previewError: Error | null;
  previewing: boolean;
  /** Whether the preview describes the draft as it is now, rather than as it was a moment ago. */
  previewCurrent: boolean;
  edit: (key: string, value: string) => void;
  /** Validates a setting when it loses focus, and lets the preview's verdict on it show. */
  blur: (key: string) => void;
  stageReset: (key: string) => void;
  undo: (key: string) => void;
  /**
   * Shows every message the draft has, as on an attempt to apply it, and returns the settings that have one
   * in the server's order. `latest` is a preview fetched for the attempt, newer than the one kept here.
   */
  revealAll: (latest?: ChangePreview) => string[];
  setApplyErrors: (errors: Record<string, string>) => void;
  /** Drops every edit, starting over from `fresh` when given (just fetched) or the values last seen. */
  discard: (fresh?: Record<string, Setting>) => void;
}

export const DraftContext = createContext<SettingsDraft | null>(null);

export function useSettingsDraft(): SettingsDraft {
  const draft = useContext(DraftContext);
  if (!draft) throw new Error('useSettingsDraft is used outside SettingsDraftProvider');
  return draft;
}
