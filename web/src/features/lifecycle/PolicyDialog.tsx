import { useState } from 'react';
import { Alert, Button, Checkbox, Group, Modal, NumberInput, Stack, Text, TextInput } from '@mantine/core';

import { usePreview, useUpdatePolicy, type StoreView } from './api.ts';
import { bytes, count, quotaUnit, retentionWords } from './words.ts';

const DURATION = /^\d+[dhm]$/;

/**
 * One store's policy. The retention is checked on blur against the syntax and, by Preview or Save,
 * against the store's bounds on the server, which names the allowed range when it refuses.
 */
export function PolicyDialog({ store, onClose }: { store: StoreView | null; onClose: () => void }) {
  return (
    <Modal opened={store !== null} onClose={onClose} title={store ? `${store.label} policy` : ''}>
      {store && <PolicyForm key={store.id} store={store} onClose={onClose} />}
    </Modal>
  );
}

function PolicyForm({ store, onClose }: { store: StoreView; onClose: () => void }) {
  const foreverAllowed = store.maxRetention === 'forever';
  const [forever, setForever] = useState(store.retention === 'forever');
  const [retention, setRetention] = useState(() => {
    if (store.retention !== 'forever') return store.retention;
    return store.defaultRetention === 'forever' ? store.minRetention : store.defaultRetention;
  });
  const [retentionError, setRetentionError] = useState<string | null>(null);
  const [quota, setQuota] = useState<number>(store.quota);
  const [warn, setWarn] = useState<number>(store.quotaWarnPercent);
  const preview = usePreview();
  const update = useUpdatePolicy();

  const value = forever ? 'forever' : retention.trim();
  const range = `${retentionWords(store.minRetention)} to ${retentionWords(store.maxRetention)}`;

  function checkSyntax(): boolean {
    const ok = forever || DURATION.test(retention.trim());
    setRetentionError(ok ? null : 'Use a number and a unit: 30d, 72h or 90m.');
    return ok;
  }

  return (
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
        label="Retention"
        description="Data older than this is purged by the next housekeeping run."
        value={retention}
        disabled={forever}
        error={retentionError}
        onChange={(e) => {
          setRetention(e.currentTarget.value);
          preview.reset();
        }}
        onBlur={checkSyntax}
      />
      <NumberInput
        label={`Quota (${quotaUnit(store)})`}
        description="0 means no quota."
        min={0}
        allowDecimal={false}
        value={quota}
        onChange={(v) => setQuota(Number(v) || 0)}
      />
      <NumberInput
        label="Warn at (% of quota)"
        min={1}
        max={100}
        allowDecimal={false}
        value={warn}
        onChange={(v) => setWarn(Number(v) || 0)}
      />

      <div aria-live="polite">
        {preview.data && (
          <Alert color="gray" title="Preview">
            {value === 'forever'
              ? 'Nothing would be purged.'
              : `The next purge would remove about ${count(preview.data.rows)} rows (${bytes(preview.data.bytes)}).`}
          </Alert>
        )}
        {preview.error && (
          <Alert color="red" title="Preview failed">
            {preview.error.message}
          </Alert>
        )}
        {update.error && (
          <Alert color="red" title="Not saved">
            {update.error.message}
          </Alert>
        )}
      </div>

      <Group justify="flex-end">
        <Button
          variant="default"
          loading={preview.isPending}
          onClick={() => checkSyntax() && preview.mutate({ id: store.id, retention: value })}
        >
          Preview
        </Button>
        <Button
          loading={update.isPending}
          onClick={() =>
            checkSyntax() &&
            update.mutate(
              { id: store.id, body: { retention: value, quota, quotaWarnPercent: warn } },
              { onSuccess: onClose },
            )
          }
        >
          Save
        </Button>
      </Group>
    </Stack>
  );
}
