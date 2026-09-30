import { useState } from 'react';
import { Alert, Loader, Stack, Text } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { absoluteLabel } from '../../kernel/time/time.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useStores, type StoreView } from './api.ts';
import { PolicyDialog } from './PolicyDialog.tsx';
import { bytes, count, nextStep, quotaWords, retentionWords } from './words.ts';

function lastPurge(s: StoreView): string {
  if (s.lastPurgeAt == null) return s.retention === 'forever' ? 'Never: kept forever' : 'Not yet';
  const at = absoluteLabel(s.lastPurgeAt);
  return s.lastPurgeError ? `${at}: failed, ${s.lastPurgeError}` : `${at}: ${count(s.lastPurged)} rows`;
}

/** Every store that grows with use, its policy and what it holds (data-lifecycle spec). */
export function RetentionTable() {
  const stores = useStores();
  const { can, loading } = useCan();
  const [editing, setEditing] = useState<StoreView | null>(null);
  const canWrite = loading || can('data:write');

  if (stores.isLoading) return <Loader size="sm" aria-label="Loading stores" />;
  if (stores.error) {
    return (
      <Alert color="red" title="Could not load the stores">
        {stores.error.message}. {nextStep(stores.error)}
      </Alert>
    );
  }

  const columns: GridColumn<StoreView>[] = [
    {
      id: 'store',
      header: 'Store',
      accessor: (s) =>
        [s.label, s.source !== 'core' ? `from ${s.source}` : null, s.overWarning ? 'over its quota warning' : null]
          .filter(Boolean)
          .join(' · '),
    },
    { id: 'retention', header: 'Retention', width: 130, accessor: (s) => retentionWords(s.retention) },
    {
      id: 'rows',
      header: 'Rows',
      width: 120,
      numeric: true,
      accessor: (s) => (s.usageError ? 'unreadable' : count(s.rows)),
    },
    {
      id: 'size',
      header: 'Size',
      width: 110,
      numeric: true,
      accessor: (s) => (s.usageError ? 'unreadable' : bytes(s.bytes)),
    },
    { id: 'quota', header: 'Quota', width: 200, accessor: (s) => quotaWords(s) },
    { id: 'purge', header: 'Last purge', accessor: (s) => lastPurge(s) },
  ];

  const rows = stores.data?.stores ?? [];
  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        Every store is purged to its retention by the nightly housekeeping run, in small batches, on one instance.
        {canWrite ? ' Select a store to change its policy.' : ' Changing a policy needs the data:write permission.'}
      </Text>
      <VirtualTable
        label="Stores"
        storageKey="data-stores"
        columns={columns}
        data={rows}
        rowKey={(s) => s.id}
        onRowClick={canWrite ? setEditing : undefined}
        emptyLabel="No store is registered. Stores come with Studio's features and with plugins that keep data."
      />
      <PolicyDialog store={editing} onClose={() => setEditing(null)} />
    </Stack>
  );
}
