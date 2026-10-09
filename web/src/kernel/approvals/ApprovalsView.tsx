import { Button, Group, Tabs } from '@mantine/core';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { ApproverQuorumNotice } from './ApproverQuorumNotice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useDisplayZone } from '../time/timezone.ts';
import views from '../shell/Views.module.css';
import { useHeldList, type HeldScope } from './api.ts';
import { requestColumns } from './columns.tsx';

/** The open tab, as the URL names it. */
export type ApprovalsTab = 'waiting' | 'mine';

const SCOPE: Readonly<Record<ApprovalsTab, HeldScope>> = { waiting: 'DECIDABLE', mine: 'MINE' };

/**
 * Approval requests: those waiting for the signed-in user's decision, and the user's own with what became of
 * them. The open tab is in the URL (`?tab=mine`), so the view can be shared and restored.
 */
export function ApprovalsView() {
  const search = useSearch({ strict: false }) as { tab?: ApprovalsTab };
  const tab: ApprovalsTab = search.tab === 'mine' ? 'mine' : 'waiting';
  const navigate = useNavigate();
  const setTab = (next: string | null) =>
    void navigate({ to: '/approvals', search: next === 'mine' ? { tab: 'mine' } : {} } as never);

  return (
    <div className={views.page}>
      <Page>
        <PageHeader
          title="Approval requests"
          description="Operations held until a second person approves them: those waiting for you to decide, and your own."
        />
        <ApproverQuorumNotice />
        <Tabs value={tab} onChange={setTab} keepMounted={false}>
          <Tabs.List>
            <Tabs.Tab value="waiting">Waiting for me</Tabs.Tab>
            <Tabs.Tab value="mine">Mine</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="waiting" pt="md">
            <RequestList tab="waiting" />
          </Tabs.Panel>
          <Tabs.Panel value="mine" pt="md">
            <RequestList tab="mine" />
          </Tabs.Panel>
        </Tabs>
      </Page>
    </div>
  );
}

function RequestList({ tab }: Readonly<{ tab: ApprovalsTab }>) {
  const list = useHeldList(SCOPE[tab]);
  useDisplayZone();
  const rows = list.data?.pages.flatMap((page) => page.items) ?? [];
  const empty =
    tab === 'waiting' ? (
      <EmptyState
        kind="empty"
        title="Nothing waits for your decision"
        description="When someone's operation needs a second person and you may approve it, it is listed here and posted to your inbox."
      />
    ) : (
      <EmptyState
        kind="empty"
        title="You have no approval requests"
        description="When an operation you start needs a second person's approval, it is sent as a request and listed here with what became of it."
      />
    );
  return (
    <>
      <DataTable
        variant="static"
        label={tab === 'waiting' ? 'Requests waiting for your decision' : 'Your requests'}
        columns={requestColumns({ mine: tab === 'mine' })}
        data={rows}
        rowKey={(r) => r.id}
        storageKey={`approvals.${tab}`}
        loading={list.isPending}
        error={list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> : undefined}
        empty={empty}
      />
      {list.hasNextPage ? (
        <Group justify="center" mt="sm">
          <Button
            variant="default"
            size="xs"
            loading={list.isFetchingNextPage}
            onClick={() => void list.fetchNextPage()}
          >
            Load more
          </Button>
        </Group>
      ) : null}
    </>
  );
}
