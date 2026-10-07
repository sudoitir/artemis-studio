import { ActionIcon, Button, SegmentedControl, Tooltip } from '@mantine/core';
import { IconX } from '@tabler/icons-react';
import { useNavigate, useSearch } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Toolbar } from '../../ui/Toolbar.tsx';
import { useServerNow } from '../time/time.ts';
import {
  useDismissNotice,
  useInboxCount,
  useInboxList,
  useMarkRead,
  type InboxFilter,
  type InboxItem,
  unreadLabel,
} from './api.ts';
import { NoticeRow } from './NoticeRow.tsx';
import classes from './Inbox.module.css';
import views from '../shell/Views.module.css';

const DISMISS: ActionVerb = { verb: 'Dismiss', past: 'Dismissed', progressive: 'Dismissing' };
const MARK_READ: ActionVerb = { verb: 'Mark', past: 'Marked', progressive: 'Marking' };

/**
 * The signed-in user's inbox: every notice Studio and its plugins posted to them that is still kept, newest
 * first, a page at a time. Unread or all is in the URL (`?filter=unread`), so the view can be shared and
 * restored. Opening a notice marks it read; dismissing one deletes it.
 */
export function InboxView() {
  const search = useSearch({ strict: false }) as { filter?: InboxFilter };
  const filter: InboxFilter = search.filter === 'unread' ? 'unread' : 'all';
  const navigate = useNavigate();
  const list = useInboxList(filter);
  const count = useInboxCount(true);
  const markRead = useMarkRead();
  const dismiss = useDismissNotice();
  const now = useServerNow(30_000);

  const items = list.data?.pages.flatMap((page) => page.items) ?? [];
  const newest = items[0];
  const unread = count.data?.unread ?? 0;

  const setFilter = (next: string) =>
    void navigate({ to: '/inbox', search: next === 'unread' ? { filter: 'unread' } : {} } as never);

  const open = (item: InboxItem) => {
    if (!item.readAt) markRead.mutate({ ids: [item.id] });
  };

  const markAll = () => {
    if (!newest) return;
    const subject = 'every notice read';
    markRead.mutate(
      { upTo: newest.id },
      {
        onError: (error) =>
          notify.settle(error, {
            action: MARK_READ,
            subject,
            cause: error.message,
            next: 'They are still unread. Try again.',
          }),
      },
    );
  };

  const remove = (item: InboxItem) => {
    const subject = `notice "${item.title}"`;
    dismiss.mutate(item.id, {
      onSuccess: () => notify.succeeded({ action: DISMISS, subject }),
      onError: (error) =>
        notify.settle(error, {
          action: DISMISS,
          subject,
          cause: error.message,
          next: 'It is still in your inbox. Try again.',
        }),
    });
  };

  let body;
  if (list.isPending) {
    body = <LoadingState label="Loading your inbox" blockSize="16rem" />;
  } else if (list.isError) {
    body = <ErrorState error={list.error} onRetry={() => void list.refetch()} blockSize="16rem" />;
  } else if (items.length === 0 && filter === 'unread') {
    body = (
      <EmptyState
        kind="filtered"
        title="No unread notices"
        description="Everything in your inbox has been read."
        onClearFilters={() => setFilter('all')}
      />
    );
  } else if (items.length === 0) {
    body = (
      <EmptyState
        kind="empty"
        title="Your inbox is empty"
        description="Studio and its plugins post notices here when something needs you, such as a request waiting for your approval. Read notices are kept for 30 days, others for 90."
      />
    );
  } else {
    body = (
      <>
        <ul className={classes.list} aria-label={filter === 'unread' ? 'Unread notices' : 'Notices'}>
          {items.map((item) => (
            <NoticeRow
              key={item.id}
              item={item}
              now={now}
              onOpen={open}
              showBody
              actions={
                <Tooltip label="Dismiss">
                  <ActionIcon
                    variant="subtle"
                    color="graphite"
                    aria-label={`Dismiss "${item.title}"`}
                    loading={dismiss.isPending && dismiss.variables === item.id}
                    onClick={() => remove(item)}
                  >
                    <IconX size={16} aria-hidden />
                  </ActionIcon>
                </Tooltip>
              }
            />
          ))}
        </ul>
        {list.hasNextPage ? (
          <div className={classes.more}>
            <Button
              variant="default"
              size="xs"
              loading={list.isFetchingNextPage}
              onClick={() => void list.fetchNextPage()}
            >
              Load more
            </Button>
          </div>
        ) : null}
      </>
    );
  }

  return (
    <div className={views.page}>
      <div className={views.narrow}>
        <Page>
          <PageHeader title="Inbox" description="Notices for you from Studio and its plugins, newest first." />
          <Toolbar
            label="Inbox"
            start={
              <SegmentedControl
                size="xs"
                aria-label="Show"
                value={filter}
                onChange={setFilter}
                data={[
                  { value: 'all', label: 'All' },
                  { value: 'unread', label: count.data ? `Unread (${unreadLabel(count.data)})` : 'Unread' },
                ]}
              />
            }
            end={
              <Button
                size="xs"
                variant="default"
                disabled={unread === 0 || !newest}
                loading={markRead.isPending && markRead.variables && 'upTo' in markRead.variables}
                onClick={markAll}
              >
                Mark all read
              </Button>
            }
          />
          {body}
        </Page>
      </div>
    </div>
  );
}
