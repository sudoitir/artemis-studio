import { Anchor, Group, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { approvalPath } from '../../ui/held.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { Ago } from '../time/Ago.tsx';
import { useServerNow } from '../time/time.ts';
import { useHeldList } from './api.ts';
import classes from './Approvals.module.css';
import { CancelRequest } from './CancelRequest.tsx';
import { STATE } from './words.ts';

/** How many of the user's requests the Account page lists before pointing to the full list. */
const SHOWN = 10;

/**
 * The Account page's "My requests": the signed-in user's latest approval requests with their state, each linked
 * to its page, and Cancel on those still waiting.
 */
export function MyRequests() {
  const list = useHeldList('MINE', SHOWN);
  const now = useServerNow(30_000);

  if (list.isPending) return <LoadingState label="Loading your requests" blockSize="6rem" />;
  if (list.isError) return <ErrorState variant="inline" error={list.error} onRetry={() => void list.refetch()} />;
  const items = list.data.pages[0]?.items ?? [];
  if (items.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="You have no approval requests"
        description="When an operation you start needs a second person's approval, it is sent as a request and listed here with what became of it."
      />
    );
  }
  return (
    <>
      <ul className={classes.requests} aria-label="Your requests">
        {items.map((item) => {
          const state = STATE[item.state];
          return (
            <li key={item.id} className={classes.request}>
              <div className={classes.requestWords}>
                <Anchor component={Link} to={approvalPath(item.id)} size="sm">
                  {item.summary}
                </Anchor>
                <Group gap="xs" mt={4} wrap="wrap">
                  <StatusBadge tone={state.tone}>{state.word}</StatusBadge>
                  <Text size="xs" c="dimmed">
                    Requested <Ago at={item.requestedAt} now={now} />
                  </Text>
                </Group>
              </div>
              {item.state === 'HELD' ? <CancelRequest id={item.id} summary={item.summary} size="xs" /> : null}
            </li>
          );
        })}
      </ul>
      {list.data.pages[0]?.next ? (
        <Anchor component={Link} to="/approvals" search={{ tab: 'mine' } as never} size="sm">
          All your requests
        </Anchor>
      ) : null}
    </>
  );
}
