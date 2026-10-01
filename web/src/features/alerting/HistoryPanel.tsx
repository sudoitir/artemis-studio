import { useMemo, useState } from 'react';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Pager } from '../../ui/Pager.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useAlertHistory, type AlertFiringView } from './api.ts';
import { historyColumns } from './columns.ts';

const PAGE_SIZE = 50;

const rowKey = (f: AlertFiringView) => String(f.seq);

/** Every past firing and resolution for this cluster, newest first (alerting spec). */
export function HistoryPanel({ clusterId }: Readonly<{ clusterId: string }>) {
  // Absolute timestamps here read the display zone from module state, so this
  // subscribes the view to a zone change (`app/timezone.ts`).
  const zone = useDisplayZone();
  const columns = useMemo(() => historyColumns(zone), [zone]);
  const [page, setPage] = useState(1);
  const history = useAlertHistory(clusterId, page, PAGE_SIZE);

  return (
    <>
      <DataTable
        variant="static"
        label="Alert history"
        storageKey="alerting.history"
        columns={columns}
        data={history.data?.data ?? []}
        rowKey={rowKey}
        loading={history.isPending}
        error={
          history.isError ? <ErrorState error={history.error} onRetry={() => void history.refetch()} /> : undefined
        }
        empty={
          <EmptyState
            kind="empty"
            title="No firings recorded yet"
            description="A firing is recorded here when a rule on this cluster fires, and again when it resolves. None has fired yet."
          />
        }
      />
      {history.data ? (
        <Pager page={page} pageSize={PAGE_SIZE} total={history.data.count ?? 0} onChange={setPage} label="firings" />
      ) : null}
    </>
  );
}
