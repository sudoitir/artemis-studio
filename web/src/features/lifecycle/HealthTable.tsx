import { Alert, Loader, Stack, Text } from '@mantine/core';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useStorageHealth, type TableView } from './api.ts';
import { bytes, count, nextStep } from './words.ts';

function growth(t: TableView): string {
  if (t.growthBytes == null) return 'unknown';
  return `${t.growthBytes >= 0 ? '+' : ''}${bytes(t.growthBytes)}`;
}

/** Every table's size, growth, dead tuples, vacuum and partitions (data-lifecycle spec). */
export function HealthTable() {
  const health = useStorageHealth();

  if (health.isLoading) return <Loader size="sm" aria-label="Loading storage health" />;
  if (health.error) {
    return (
      <Alert color="red" title="Could not read storage health">
        {health.error.message}. {nextStep(health.error)}
      </Alert>
    );
  }

  const rows = [...(health.data?.tables ?? [])].sort(
    (a, b) => Number(b.problems.length > 0) - Number(a.problems.length > 0) || b.bytes - a.bytes,
  );
  const unhealthy = rows.filter((t) => t.problems.length > 0).length;

  const columns: GridColumn<TableView>[] = [
    {
      id: 'table',
      header: 'Table',
      accessor: (t) => (t.schema === 'public' ? t.name : `${t.schema}.${t.name}`),
    },
    {
      id: 'state',
      header: 'State',
      accessor: (t) => (t.problems.length ? `Unhealthy: ${t.problems.join('; ')}` : 'Healthy'),
    },
    { id: 'size', header: 'Size', width: 100, numeric: true, accessor: (t) => bytes(t.bytes) },
    { id: 'growth', header: '7-day growth', width: 110, numeric: true, accessor: growth },
    {
      id: 'dead',
      header: 'Dead rows',
      width: 130,
      numeric: true,
      accessor: (t) => `${t.deadPercent}% of ${count(t.rows + t.deadRows)}`,
    },
    {
      id: 'vacuum',
      header: 'Last vacuum',
      width: 170,
      accessor: (t) => (t.lastVacuum ? absoluteLabel(t.lastVacuum) : 'never'),
    },
    {
      id: 'partitions',
      header: 'Partitions',
      width: 100,
      accessor: (t) =>
        !t.partitioned ? 'n/a' : t.missingPartitions.length ? `missing ${t.missingPartitions.join(', ')}` : 'ready',
    },
  ];

  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed" aria-live="polite">
        {unhealthy === 0
          ? `All ${rows.length} tables are healthy.`
          : `${unhealthy} of ${rows.length} tables need attention. The Storage health alert rule reports them.`}{' '}
        Figures are Postgres statistics, so row counts are estimates.
      </Text>
      <VirtualTable
        label="Tables"
        storageKey="data-health"
        columns={columns}
        data={rows}
        rowKey={(t) => `${t.schema}.${t.name}`}
        emptyLabel="No table statistics yet. Postgres collects them as tables are used."
      />
    </Stack>
  );
}
