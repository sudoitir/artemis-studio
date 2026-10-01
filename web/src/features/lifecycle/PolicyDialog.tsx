import { Button, Checkbox, Group, Modal, NumberInput, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
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
  const preview = usePreview();
  const update = useUpdatePolicy();
  const form = useForm<{ forever: boolean; retention: string; quota: Figure; warn: Figure }>({
    initialValues: {
      forever: store.retention === 'forever',
      retention:
        store.retention !== 'forever'
          ? store.retention
          : store.defaultRetention === 'forever'
            ? store.minRetention
            : store.defaultRetention,
      quota: store.quota,
      warn: store.quotaWarnPercent,
    },
    validateInputOnBlur: true,
    validate: {
      retention: (v, values) =>
        values.forever || DURATION.test(v.trim()) ? null : 'Use a number and a unit: 30d, 72h or 90m.',
      quota: quotaProblem,
      warn: warnProblem,
    },
    // A preview describes the retention it was run with, not the one typed since.
    onValuesChange: () => preview.reset(),
  });
  const { forever, retention } = form.values;

  const value = forever ? 'forever' : retention.trim();
  const range = `${retentionWords(store.minRetention)} to ${retentionWords(store.maxRetention)}`;

  const save = form.onSubmit(({ quota, warn }) => {
    if (!isWhole(quota) || !isWhole(warn)) return;
    update.mutate(
      { id: store.id, body: { retention: value, quota, quotaWarnPercent: warn } },
      {
        onSuccess: () => {
          notify.succeeded({ action: SAVE, subject: `the ${store.label} policy` });
          onClose();
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  const runPreview = () => {
    if (form.validateField('retention').hasError) {
      form.getInputNode('retention')?.focus();
      return;
    }
    preview.mutate({ id: store.id, retention: value });
  };

  return (
    <form noValidate onSubmit={save}>
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          Allowed: {range}. Default: {retentionWords(store.defaultRetention)}.
        </Text>
        {foreverAllowed && (
          <Checkbox
            label="Keep everything (no purge)"
            {...form.getInputProps('forever', { type: 'checkbox' })}
            onChange={(e) => {
              form.setFieldValue('forever', e.currentTarget.checked);
              form.clearFieldError('retention');
            }}
          />
        )}
        <TextInput
          label="Retention"
          description="Data older than this is purged by the next housekeeping run."
          {...form.getInputProps('retention')}
          disabled={forever}
        />
        <NumberInput
          label={`Quota (${quotaUnit(store)})`}
          description="0 means no quota."
          min={0}
          clampBehavior="none"
          allowDecimal={false}
          {...form.getInputProps('quota')}
        />
        <NumberInput
          label="Warn at (% of quota)"
          min={1}
          max={100}
          clampBehavior="none"
          allowDecimal={false}
          {...form.getInputProps('warn')}
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
          <Button variant="default" loading={preview.isPending} onClick={runPreview}>
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
