import { useEffect, useRef, useState } from 'react';
import { ActionIcon, Anchor, Button, Indicator, Popover, VisuallyHidden } from '@mantine/core';
import { IconBell } from '@tabler/icons-react';
import { Link } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { useServerNow } from '../time/time.ts';
import { useUserStream } from '../stream/useUserStream.ts';
import { useInboxCount, useLatestNotices, useMarkRead, type InboxCount, type InboxItem, unreadLabel } from './api.ts';
import { NoticeRow } from './NoticeRow.tsx';
import classes from './Inbox.module.css';

/** The bell's accessible name, which carries the count a sighted user reads off the badge. */
function bellName(count: InboxCount | undefined): string {
  if (!count) return 'Notifications';
  return count.unread === 0 ? 'Notifications, none unread' : `Notifications, ${unreadLabel(count)} unread`;
}

/**
 * What to announce when the count changes while the operator is on a page: a rise is a new notice. A fall
 * is the operator's own doing (or another tab's) and is not announced.
 */
function useArrivalAnnouncement(count: InboxCount | undefined): string {
  const previous = useRef<number | null>(null);
  const [message, setMessage] = useState('');
  useEffect(() => {
    if (!count) return;
    const before = previous.current;
    previous.current = count.unread;
    if (before === null || count.unread <= before) return;
    const arrived = count.unread - before;
    setMessage(`${arrived === 1 ? 'A new notice' : `${arrived} new notices`}. ${unreadLabel(count)} unread.`);
  }, [count]);
  return message;
}

/**
 * The header's bell: the unread count, kept live by the user's stream on every page, and a popover with the
 * latest notices, "Mark all read" and the way to the inbox. Opening a notice marks it read and follows its
 * link inside the app. A new notice is announced politely.
 */
export function InboxBell() {
  const stream = useUserStream();
  const count = useInboxCount(stream === 'live');
  const [opened, setOpened] = useState(false);
  const latest = useLatestNotices(opened);
  const markRead = useMarkRead();
  const now = useServerNow(30_000);
  const announcement = useArrivalAnnouncement(count.data);

  const unread = count.data?.unread ?? 0;
  const items = latest.data?.items ?? [];
  const newest = items[0];

  const open = (item: InboxItem) => {
    if (!item.readAt) markRead.mutate({ ids: [item.id] });
    if (item.link) setOpened(false);
  };

  let body;
  if (latest.isPending) {
    body = <LoadingState label="Loading notifications" blockSize="8rem" />;
  } else if (latest.isError) {
    body = <ErrorState variant="inline" error={latest.error} onRetry={() => void latest.refetch()} />;
  } else if (items.length === 0) {
    body = (
      <EmptyState
        kind="empty"
        title="No notifications"
        description="Studio and its plugins post here when something needs you, such as a request waiting for your approval."
      />
    );
  } else {
    body = (
      <ul className={`${classes.list} ${classes.latest}`} aria-label="Latest notifications">
        {items.map((item) => (
          <NoticeRow key={item.id} item={item} now={now} onOpen={open} />
        ))}
      </ul>
    );
  }

  const name = bellName(count.data);
  return (
    <>
      {/* The badge sits outside the popover, so the popover's target, which carries its expanded state, is the button. */}
      <Indicator
        inline
        size={16}
        offset={4}
        disabled={unread === 0}
        label={<span aria-hidden>{unreadLabel(count.data)}</span>}
      >
        <Popover opened={opened} onChange={setOpened} position="bottom-end" shadow="md" trapFocus returnFocus>
          <Popover.Target>
            <ActionIcon variant="subtle" color="graphite" aria-label={name} onClick={() => setOpened((o) => !o)}>
              <IconBell size={18} aria-hidden />
            </ActionIcon>
          </Popover.Target>
          <Popover.Dropdown aria-label="Notifications">
            <div className={classes.dropdown}>
              <div className={classes.dropdownHeader}>
                <span className={classes.dropdownTitle}>Notifications</span>
                <Button
                  size="xs"
                  variant="subtle"
                  disabled={unread === 0 || !newest}
                  loading={markRead.isPending}
                  onClick={() => newest && markRead.mutate({ upTo: newest.id })}
                >
                  Mark all read
                </Button>
              </div>
              {body}
              <div className={classes.dropdownFooter}>
                <Anchor component={Link} to="/inbox" size="sm" onClick={() => setOpened(false)}>
                  Open inbox
                </Anchor>
              </div>
            </div>
          </Popover.Dropdown>
        </Popover>
      </Indicator>
      <VisuallyHidden role="status" aria-live="polite">
        {announcement}
      </VisuallyHidden>
    </>
  );
}
