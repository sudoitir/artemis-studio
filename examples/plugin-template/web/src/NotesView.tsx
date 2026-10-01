import { useMemo } from 'react';
import { Anchor } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';
import { DataTable, EmptyState, ErrorState, Page, PageHeader, type Column } from '@artemis-studio/plugin-sdk';

import { useRecentNotes, type Note } from './api.ts';

/** The plugin's page in a cluster: the latest notes across its queues. */
export function NotesView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const notes = useRecentNotes(clusterId);

  const columns = useMemo<Column<Note>[]>(
    () => [
      {
        id: 'queue',
        header: 'Queue',
        accessor: (n) => n.queue,
        kind: 'identifier',
        priority: 'essential',
        cell: (n) => (
          <Anchor component={Link} to={`/clusters/${clusterId}/queues`} search={{ queue: n.queue } as never}>
            {n.queue}
          </Anchor>
        ),
      },
      { id: 'note', header: 'Note', accessor: (n) => n.body, kind: 'text', priority: 'essential', wrap: true },
      { id: 'author', header: 'By', accessor: (n) => n.author, kind: 'text', priority: 'high' },
    ],
    [clusterId],
  );

  return (
    <Page fill>
      <PageHeader title="Notes" description="The latest notes operators left on the queues of this cluster." />
      <DataTable
        variant="static"
        label="Recent notes"
        columns={columns}
        data={notes.data ?? []}
        rowKey={(n) => n.id}
        loading={notes.isPending}
        empty={
          <EmptyState
            kind="empty"
            title="No notes on this cluster"
            description="Open a queue and add one from its details."
          />
        }
        error={notes.isError ? <ErrorState error={notes.error} onRetry={() => void notes.refetch()} /> : undefined}
      />
    </Page>
  );
}
