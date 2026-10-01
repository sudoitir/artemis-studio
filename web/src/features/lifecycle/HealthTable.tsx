import { useMemo } from 'react';
import { Stack, Text } from '@mantine/core';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useStorageHealth, type TableView } from './api.ts';
import { healthColumns } from './columns.ts';

const rowKey = (t: TableView) => `${t.schema}.${t.name}`;

/** Every table's size, growth, dead tuples, vacuum and partitions (data-lifecycle spec). */
export function HealthTable() {
  const zone = useDisplayZone();
  const health = useStorageHealth();
  const columns = useMemo(() => healthColumns(zone), [zone]);

  const tables = health.data?.tables;
  const rows = useMemo(
    () =>
      [...(tables ?? [])].sort(
        (a, b) => Number(b.problems.length > 0) - Number(a.problems.length > 0) || b.bytes - a.bytes,
      ),
    [tables],
  );
  const unhealthy = rows.filter((t) => t.problems.length > 0).length;

  return (
    <Stack gap="sm">
      <DataTable
        variant="static"
        label="Tables"
        storageKey="data-health"
        columns={columns}
        data={rows}
        rowKey={rowKey}
        loading={health.isLoading}
        error={health.error ? <ErrorState error={health.error} onRetry={() => void health.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No table statistics yet"
            description="This lists each table's size, growth, dead rows, vacuum and partitions, read from the statistics Postgres collects as tables are used. It has none for Studio's database yet, so check back once Studio has been running for a while."
          />
        }
      />
      {/* Below the table, so the sentence that arrives with the data moves nothing above it. */}
      <Text size="sm" c="dimmed" role="status">
        {tables
          ? `${
              unhealthy === 0
                ? `All ${rows.length} tables are healthy.`
                : `${unhealthy} of ${rows.length} tables need attention. The Storage health alert rule reports them.`
            } Figures are Postgres statistics, so row counts are estimates.`
          : ''}
      </Text>
    </Stack>
  );
}
