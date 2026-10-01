import { useMemo } from 'react';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useFiringAlerts, type AlertFiringView } from './api.ts';
import { firingColumns } from './columns.ts';

const rowKey = (f: AlertFiringView) => String(f.seq);

/** Currently firing alerts for this cluster, newest first (alerting spec). */
export function FiringPanel({ clusterId }: Readonly<{ clusterId: string }>) {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  const zone = useDisplayZone();
  const columns = useMemo(() => firingColumns(zone), [zone]);
  const firing = useFiringAlerts(clusterId);

  return (
    <DataTable
      variant="static"
      label="Firing alerts"
      storageKey="alerting.firing"
      columns={columns}
      data={firing.data ?? []}
      rowKey={rowKey}
      loading={firing.isPending}
      error={firing.isError ? <ErrorState error={firing.error} onRetry={() => void firing.refetch()} /> : undefined}
      empty={
        <EmptyState
          kind="empty"
          title="Nothing is firing"
          description='Every enabled rule is currently OK. A rule debounces through a "pending" state for its configured duration before it fires here.'
        />
      }
    />
  );
}
