import { useRef, useState, type RefObject } from 'react';
import { Button, Checkbox, Group, Modal, NumberInput, Stack, Text, TextInput } from '@mantine/core';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify } from '../../ui/notify.ts';
import { usePreview, useUpdatePolicy, type StoreView } from './api.ts';
import { bytes, count, quotaUnit, retentionWords } from './words.ts';

const DURATION = /^\d+[dhm]$/;

const SAVE = { verb: 'Save', past: 'Saved', progressive: 'Saving' } as const;

/** A number field's value: a number, or the empty string when the operator cleared it. */
type Figure = number | string;

const isWhole = (value: Figure): value is number => typeof value === 'number' && Number.isInteger(value);

function quotaProblem(quota: Figure): string | null {
  return isWhole(quota) && quota >= 0 ? null : 'Enter 0, or the quota as a whole number.';
}

function warnProblem(warn: Figure): string | null {
  return isWhole(warn) && warn >= 1 && warn <= 100 ? null : 'Enter a whole number from 1 to 100.';
}

/**
 * One store's policy. Each field is checked on blur, with its message beside it; the retention is
 * also checked against the store's bounds on the server by Preview or Save, which names the allowed
 * range when it refuses. Saving with a field wrong moves focus to the first one that is.
 */
export function PolicyDialog({ store, onClose }: Readonly<{ store: StoreView | null; onClose: () => void }>) {
  return (
    <Modal
      opened={store !== null}
      onClose={onClose}
      title={store ? `${store.label} policy` : ''}
      closeButtonProps={{ 'aria-label': 'Close the policy dialog' }}
    >
      {store && <PolicyForm key={store.id} store={store} onClose={onClose} />}
    </Modal>
  );
}

function PolicyForm({ store, onClose }: Readonly<{ store: StoreView; onClose: () => void }>) {
  const foreverAllowed = store.maxRetention === 'forever';
  const [forever, setForever] = useState(store.retention === 'forever');
  const [retention, setRetention] = useState(() => {
    if (store.retention !== 'forever') return store.retention;
    return store.defaultRetention === 'forever' ? store.minRetention : store.defaultRetention;
  });
  const [retentionError, setRetentionError] = useState<string | null>(null);
  const [quota, setQuota] = useState<Figure>(store.quota);
  const [quotaError, setQuotaError] = useState<string | null>(null);
  const [warn, setWarn] = useState<Figure>(store.quotaWarnPercent);
  const [warnError, setWarnError] = useState<string | null>(null);
  const retentionInput = useRef<HTMLInputElement>(null);
  const quotaInput = useRef<HTMLInputElement>(null);
  const warnInput = useRef<HTMLInputElement>(null);
  const preview = usePreview();
  const update = useUpdatePolicy();

  const value = forever ? 'forever' : retention.trim();
  const range = `${retentionWords(store.minRetention)} to ${retentionWords(store.maxRetention)}`;

  function checkRetention(): string | null {
    const problem = forever || DURATION.test(retention.trim()) ? null : 'Use a number and a unit: 30d, 72h or 90m.';
    setRetentionError(problem);
    return problem;
  }

  function checkQuota(): string | null {
    const problem = quotaProblem(quota);
    setQuotaError(problem);
    return problem;
  }

  function checkWarn(): string | null {
    const problem = warnProblem(warn);
    setWarnError(problem);
    return problem;
  }

  /** Checks the fields, all of them so every message shows, and focuses the first wrong one. */
  function valid(fields: ReadonlyArray<readonly [() => string | null, RefObject<HTMLInputElement | null>]>): boolean {
    const problems = fields.map(([check]) => check());
    const first = problems.findIndex(Boolean);
    if (first >= 0) fields[first][1].current?.focus();
    return first < 0;
  }

  const retentionField = [checkRetention, retentionInput] as const;
  const allFields = [retentionField, [checkQuota, quotaInput], [checkWarn, warnInput]] as const;

  function save() {
    if (!valid(allFields) || !isWhole(quota) || !isWhole(warn)) return;
    update.mutate(
      { id: store.id, body: { retention: value, quota, quotaWarnPercent: warn } },
      {
        onSuccess: () => {
          notify.succeeded({ action: SAVE, subject: `the ${store.label} policy` });
          onClose();
        },
      },
    );
  }

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
    >
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          Allowed: {range}. Default: {retentionWords(store.defaultRetention)}.
        </Text>
        {foreverAllowed && (
          <Checkbox
            label="Keep everything (no purge)"
            checked={forever}
            onChange={(e) => {
              setForever(e.currentTarget.checked);
              setRetentionError(null);
              preview.reset();
            }}
          />
        )}
        <TextInput
          ref={retentionInput}
          label="Retention"
          description="Data older than this is purged by the next housekeeping run."
          value={retention}
          disabled={forever}
          error={retentionError}
          onChange={(e) => {
            setRetention(e.currentTarget.value);
            preview.reset();
          }}
          onBlur={checkRetention}
        />
        <NumberInput
          ref={quotaInput}
          label={`Quota (${quotaUnit(store)})`}
          description="0 means no quota."
          min={0}
          allowDecimal={false}
          value={quota}
          error={quotaError}
          onChange={setQuota}
          onBlur={checkQuota}
        />
        <NumberInput
          ref={warnInput}
          label="Warn at (% of quota)"
          min={1}
          max={100}
          allowDecimal={false}
          value={warn}
          error={warnError}
          onChange={setWarn}
          onBlur={checkWarn}
        />

        <div aria-live="polite">
          {preview.data && (
            <Text size="sm">
              <strong>Preview.</strong>{' '}
              {value === 'forever'
                ? 'Nothing would be purged.'
                : `The next purge would remove about ${count(preview.data.rows)} rows (${bytes(preview.data.bytes)}).`}
            </Text>
          )}
        </div>
        {preview.error && <ErrorState variant="inline" error={preview.error} />}
        {update.error && <ErrorState variant="inline" error={update.error} />}

        <Group justify="flex-end">
          <Button
            variant="default"
            loading={preview.isPending}
            onClick={() => valid([retentionField]) && preview.mutate({ id: store.id, retention: value })}
          >
            Preview
          </Button>
          <Button type="submit" loading={update.isPending}>
            Save
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
