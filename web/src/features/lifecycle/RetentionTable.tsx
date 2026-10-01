import { useMemo, useState } from 'react';
import { Stack, Text } from '@mantine/core';

import { useCan } from '../../kernel/auth/useCan.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useStores, type StoreView } from './api.ts';
import { storeColumns } from './columns.ts';
import { PolicyDialog } from './PolicyDialog.tsx';

const rowKey = (s: StoreView) => s.id;

/** Every store that grows with use, its policy and what it holds (data-lifecycle spec). */
export function RetentionTable() {
  const zone = useDisplayZone();
  const stores = useStores();
  const { can, loading } = useCan();
  const [editing, setEditing] = useState<StoreView | null>(null);
  const canWrite = loading || can('data:write');
  const columns = useMemo(() => storeColumns(zone), [zone]);

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        Every store is purged to its retention by the nightly housekeeping run, in small batches, on one instance.
        {canWrite ? ' Select a store to change its policy.' : ' Changing a policy needs the data:write permission.'}
      </Text>
      <DataTable
        label="Stores"
        storageKey="data-stores"
        height="fill"
        columns={columns}
        data={stores.data?.stores ?? []}
        rowKey={rowKey}
        loading={stores.isLoading}
        error={stores.error ? <ErrorState error={stores.error} onRetry={() => void stores.refetch()} /> : undefined}
        onRowClick={canWrite ? setEditing : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No store is registered"
            description="A store is a table that grows with use, such as recorded events or samples, and has a retention and a quota. Studio's features and the plugins that keep data each register theirs, and none has registered one here yet. Enable such a feature or install such a plugin, and its store is listed here to set its policy."
          />
        }
      />
      <PolicyDialog store={editing} onClose={() => setEditing(null)} />
    </Stack>
  );
}
