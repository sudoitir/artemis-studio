import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useForm } from '@mantine/form';
import { useDebouncedValue } from '@mantine/hooks';

import { useChangePreview, type Setting, type SettingChange } from './api.ts';
import { DraftContext, type DraftValues, type SettingsDraft } from './draftContext.ts';
import { besideField, changeOf, fieldOf, localError } from './model.ts';

/** How long typing pauses before the draft is previewed: long enough not to ask on every key. */
const PREVIEW_DEBOUNCE_MS = 400;

const fieldsOf = (settings: Record<string, Setting>) =>
  Object.entries(settings).map(([key, setting]) => fieldOf(key, setting));

const pathOf = (index: number) => `fields.${index}.value`;

// Defined once, so the form's validators keep their identity across renders and the draft can be memoized.
const VALIDATE = { fields: { value: localErrorAt } };

/**
 * One draft of setting changes for the whole Settings page, so an edit in one category survives opening
 * another and every change is applied together. A setting's value is in the form; whether it differs from
 * what the server holds is derived, never stored, so a refresh from the server can never leave a stale
 * change behind.
 */
export function SettingsDraftProvider({
  settings,
  children,
}: Readonly<{ settings: Record<string, Setting>; children: ReactNode }>) {
  const form = useForm<DraftValues>({
    initialValues: { fields: fieldsOf(settings) },
    validateInputOnBlur: true,
    validate: VALIDATE,
  });
  // Settings whose value lost focus since it last changed: only those show what the preview said about them.
  const [visited, setVisited] = useState<ReadonlySet<string>>(new Set());
  const [applyErrors, setApplyErrors] = useState<Record<string, string>>({});

  // A refresh from the server keeps the operator's edits and takes the new value of everything else.
  const server = useRef(settings);
  useEffect(() => {
    const previous = server.current;
    if (previous === settings) return;
    server.current = settings;
    const current = new Map(form.getValues().fields.map((f) => [f.key, f]));
    form.setValues({
      fields: Object.entries(settings).map(([key, setting]) => {
        const kept = current.get(key);
        // An edit the server now holds, or a reset of a setting nobody overrides any more, is done.
        return kept && changeOf(kept, previous[key]) && changeOf(kept, setting) ? kept : fieldOf(key, setting);
      }),
    });
    // `form` is a new object every render; its methods are stable.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings]);

  const fields = form.values.fields;
  const index = useMemo(() => new Map(fields.map((f, i) => [f.key, i])), [fields]);

  const changes = useMemo(
    () => fields.map((f) => changeOf(f, settings[f.key])).filter((c): c is SettingChange => c !== null),
    [fields, settings],
  );
  const changedIn = useMemo(() => {
    const counts = new Map<string, number>();
    for (const change of changes) {
      const category = settings[change.key]?.category;
      if (category) counts.set(category, (counts.get(category) ?? 0) + 1);
    }
    return counts;
  }, [changes, settings]);

  const [debounced] = useDebouncedValue(changes, PREVIEW_DEBOUNCE_MS);
  const previewQuery = useChangePreview(debounced);
  const previewCurrent = debounced === changes && !previewQuery.isPlaceholderData && !previewQuery.isFetching;
  const preview = changes.length > 0 ? previewQuery.data : undefined;

  const changedKeys = useMemo(() => new Set(changes.map((c) => c.key)), [changes]);
  const { errors, setFieldValue, setValues, validate, validateField } = form;
  const previewError = changes.length > 0 ? previewQuery.error : null;

  // `form` is a new object every render, so the draft depends on the parts of it that change only with state.
  const draft = useMemo<SettingsDraft>(() => {
    const errorOf = (key: string): string | undefined => {
      const i = index.get(key);
      const local = i === undefined ? undefined : errors[pathOf(i)];
      if (typeof local === 'string') return local;
      if (!changedKeys.has(key)) return undefined;
      const server = (visited.has(key) ? preview?.fieldErrors[key] : undefined) ?? applyErrors[key];
      return server === undefined ? undefined : besideField(key, server);
    };
    const invalidKeys = fields.map((f) => f.key).filter((key) => errorOf(key) !== undefined);

    const forget = (key: string) => {
      setVisited((current) => without(current, key));
      setApplyErrors((current) => omit(current, key));
    };

    return {
      settings,
      focus: (key) => {
        const i = index.get(key);
        if (i !== undefined) document.querySelector<HTMLElement>(`[data-path="${pathOf(i)}"]`)?.focus();
      },
      field: (key) => {
        const i = index.get(key);
        return i === undefined ? undefined : { field: fields[i], path: pathOf(i) };
      },
      changes,
      changedIn,
      errorOf,
      invalidKeys,
      preview,
      previewError,
      previewing: changes.length > 0 && !previewCurrent,
      previewCurrent,
      edit: (key, value) => {
        const i = index.get(key);
        if (i === undefined) return;
        setFieldValue(`fields.${i}`, { ...fields[i], value, reset: false });
        forget(key);
      },
      blur: (key) => {
        const i = index.get(key);
        if (i !== undefined) validateField(pathOf(i));
        setVisited((current) => new Set(current).add(key));
      },
      stageReset: (key) => {
        const i = index.get(key);
        const setting = settings[key];
        if (i === undefined || !setting) return;
        setFieldValue(`fields.${i}`, { ...fields[i], value: setting.defaultValue, reset: true });
        forget(key);
      },
      undo: (key) => {
        const i = index.get(key);
        const setting = settings[key];
        if (i === undefined || !setting) return;
        setFieldValue(`fields.${i}`, fieldOf(key, setting));
        forget(key);
      },
      revealAll: (latest) => {
        setVisited(new Set(changedKeys));
        const local = new Set(Object.keys(validate().errors).map((path) => fields[Number(path.split('.')[1])]?.key));
        const server = { ...(latest ?? preview)?.fieldErrors, ...applyErrors };
        return fields.map((f) => f.key).filter((key) => local.has(key) || (changedKeys.has(key) && key in server));
      },
      setApplyErrors,
      discard: (fresh) => {
        if (fresh) server.current = fresh;
        setValues({ fields: fieldsOf(server.current) });
        setVisited(new Set());
        setApplyErrors({});
      },
    };
  }, [
    settings,
    fields,
    index,
    changes,
    changedIn,
    changedKeys,
    errors,
    visited,
    applyErrors,
    preview,
    previewError,
    previewCurrent,
    setFieldValue,
    setValues,
    validate,
    validateField,
  ]);

  return <DraftContext.Provider value={draft}>{children}</DraftContext.Provider>;
}

function localErrorAt(value: string, values: DraftValues, path: string) {
  const i = Number(path.split('.')[1]);
  return localError(values.fields[i]?.kind ?? '', value);
}

function without(set: ReadonlySet<string>, key: string): ReadonlySet<string> {
  if (!set.has(key)) return set;
  const next = new Set(set);
  next.delete(key);
  return next;
}

function omit(record: Record<string, string>, key: string): Record<string, string> {
  if (!(key in record)) return record;
  const rest = { ...record };
  delete rest[key];
  return rest;
}
