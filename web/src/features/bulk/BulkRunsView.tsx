import { useMemo } from 'react';
import { Anchor, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Page } from '../../ui/Page.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useBulkRuns, type BulkRunView } from './api.ts';
import { runColumns } from './columns.ts';

const rowKey = (r: BulkRunView) => r.id;

/** Past and running bulk runs on a cluster, newest first. */
export function BulkRunsView() {
  const zone = useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const query = useBulkRuns(clusterId);
  const columns = useMemo(() => runColumns({ clusterId, zone }), [clusterId, zone]);

  return (
    <Page fill>
      <Title order={3}>Bulk runs</Title>
      <DataTable
        label="Bulk runs"
        storageKey="bulk.runs"
        height="fill"
        columns={columns}
        data={query.data ?? []}
        rowKey={rowKey}
        loading={query.isPending}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        empty={
          <EmptyState
            kind="empty"
            title="No bulk runs yet"
            description="A bulk run pauses, resumes, purges or deletes many queues at once, one queue at a time, after a preview you confirm. Nothing has been run on this cluster yet. Select queues on the Queues screen to start one, and it is listed here with its outcome."
            action={
              <Anchor component={Link} to={`/clusters/${clusterId}/queues`} size="sm">
                Go to Queues
              </Anchor>
            }
          />
        }
      />
    </Page>
  );
}
