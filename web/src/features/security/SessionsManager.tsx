import { useState } from 'react';
import { Button, Group, Stack, Text } from '@mantine/core';

import { useLogout } from '../../kernel/auth/api.ts';
import { useServerNow } from '../../kernel/time/time.ts';
import { Ago } from '../../kernel/time/Ago.tsx';
import { useEndOtherSessions, useEndSession, useSessions, type AccountSessionView } from './api.ts';
import { describeClient } from '../../kernel/auth/clientLabel.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Row, Rows } from '../../ui/ListRows.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';

interface Outcome {
  text: string;
  failed: boolean;
}

/** About four rows: the list holds this height for any number of sessions and scrolls past it. */
const LIST_BLOCK = '12rem';
/** The list, the button under it (an `xs` button, 1.875rem) and the status line, with the two gaps between them. */
const FRAME_BLOCK = `calc(${LIST_BLOCK} + 1.875rem + 2 * var(--mantine-spacing-md))`;

const describeSession = (s: AccountSessionView) =>
  `${describeClient(s.userAgent)} at ${s.clientAddress ?? 'an unknown address'}`;

/** Ending sessions, one or every other, and saying how it went. */
function useSessionEnding(userId: string | undefined) {
  const admin = userId !== undefined;
  const end = useEndSession(userId);
  const endOthers = useEndOtherSessions(userId);
  const logout = useLogout();
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  function endOne(s: AccountSessionView) {
    setOutcome(null);
    const label = describeSession(s);
    if (s.current) {
      logout.mutate(undefined, {
        onSuccess: () => globalThis.location.assign('/login'),
        onError: (e) => setOutcome({ text: `Could not sign you out. ${e.message} Try again.`, failed: true }),
      });
      return;
    }
    end.mutate(s.handle, {
      onSuccess: () => setOutcome({ text: `${admin ? 'Ended' : 'Signed out'} ${label}.`, failed: false }),
      onError: (e) =>
        setOutcome(
          e.status === 404
            ? { text: `${label} had already ended.`, failed: false }
            : { text: `Could not end ${label}. ${failureAdvice(e.status, e.message)}`, failed: true },
        ),
    });
  }

  function endAllOthers() {
    setOutcome(null);
    endOthers.mutate(undefined, {
      onSuccess: ({ ended }) => {
        const noun = `${admin ? '' : 'other '}session${ended === 1 ? '' : 's'}`;
        const done = admin ? 'Ended' : 'Signed out';
        setOutcome({
          text: ended === 0 ? `There were no ${noun} to end.` : `${done} ${ended} ${noun}.`,
          failed: false,
        });
      },
      onError: (e) =>
        setOutcome({ text: `Could not end the sessions. ${failureAdvice(e.status, e.message)}`, failed: true }),
    });
  }

  return {
    outcome,
    busy: end.isPending || endOthers.isPending || logout.isPending,
    endingAll: endOthers.isPending,
    isEnding: (s: AccountSessionView) =>
      (end.isPending && end.variables === s.handle) || (s.current && logout.isPending),
    endOne,
    endAllOthers,
  };
}

/**
 * The signed-in sessions of the caller, or of the user `userId` names when an administrator looks
 * (ADR-0145), each ended with one action. The caller's own current session is marked in words and
 * ends by signing out, wherever it is listed. Every outcome is announced, and a failure says why and
 * what to do.
 */
export function SessionsManager({ userId }: Readonly<{ userId?: string }>) {
  const admin = userId !== undefined;
  const sessions = useSessions(userId);
  const ending = useSessionEnding(userId);

  // The frame, and the failure that can replace it, hold the height of the list and its button, so nothing
  // below moves when either arrives.
  if (sessions.isPending) return <LoadingState label="Loading sessions" blockSize={FRAME_BLOCK} />;
  if (sessions.isError) {
    return <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} blockSize={FRAME_BLOCK} />;
  }

  const list = sessions.data;
  const others = list.filter((s) => !s.current).length;
  return (
    <Stack gap="md">
      <SessionRows list={list} admin={admin} ending={ending} />

      <EndOthers others={others} admin={admin} ending={ending} />

      <Status ending={ending} admin={admin} />
    </Stack>
  );
}

function EndOthers({
  others,
  admin,
  ending,
}: Readonly<{ others: number; admin: boolean; ending: ReturnType<typeof useSessionEnding> }>) {
  return (
    <Group gap="sm">
      <Button
        size="xs"
        variant="default"
        loading={ending.endingAll}
        disabled={ending.busy || others === 0}
        onClick={ending.endAllOthers}
      >
        {admin ? 'End all sessions' : 'Sign out all other sessions'}
        {others > 0 ? ` (${others})` : ''}
      </Button>
      {others === 0 ? (
        <Text size="xs" c="dimmed">
          {admin ? 'This user has no other sessions.' : 'You are not signed in anywhere else.'}
        </Text>
      ) : null}
    </Group>
  );
}

function Status({ ending, admin }: Readonly<{ ending: ReturnType<typeof useSessionEnding>; admin: boolean }>) {
  return (
    <div aria-live="polite">
      {ending.busy ? (
        <Text size="sm" c="dimmed">
          {admin ? 'Ending' : 'Signing out of'} the session{ending.endingAll ? 's' : ''}…
        </Text>
      ) : null}
      {!ending.busy && ending.outcome ? (
        <Group gap="xs" wrap="nowrap" align="flex-start">
          {ending.outcome.failed ? <StatusBadge tone="danger">Failed</StatusBadge> : null}
          <Text size="sm" c={ending.outcome.failed ? undefined : 'dimmed'}>
            {ending.outcome.text}
          </Text>
        </Group>
      ) : null}
    </div>
  );
}

function SessionRows({
  list,
  admin,
  ending,
}: Readonly<{ list: AccountSessionView[]; admin: boolean; ending: ReturnType<typeof useSessionEnding> }>) {
  const now = useServerNow(30_000);
  if (list.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title={admin ? 'This user is not signed in anywhere' : 'No sessions are listed'}
        description={
          admin
            ? 'A session is listed here from the moment they sign in.'
            : 'A session is listed here from the moment you sign in.'
        }
      />
    );
  }
  return (
    <Rows label={admin ? 'Sessions of this user' : 'Your sessions'} bounded blockSize={LIST_BLOCK}>
      {list.map((s) => (
        <Row
          key={s.handle}
          title={
            <Group gap="xs" wrap="wrap">
              <Text size="sm" fw={500} title={s.userAgent ?? undefined}>
                {describeClient(s.userAgent)}
              </Text>
              {s.current ? <StatusBadge>This session</StatusBadge> : null}
            </Group>
          }
          facts={
            <>
              {s.clientAddress ?? 'Address unknown'} · signed in <Ago at={s.signedInAt} now={now} /> · last active{' '}
              <Ago at={s.lastActivityAt} now={now} />
            </>
          }
          action={
            <Button
              size="xs"
              variant="subtle"
              aria-label={
                s.current ? 'Sign out of this session' : `${admin ? 'End' : 'Sign out'} ${describeSession(s)}`
              }
              loading={ending.isEnding(s)}
              disabled={ending.busy}
              onClick={() => ending.endOne(s)}
            >
              {admin && !s.current ? 'End' : 'Sign out'}
            </Button>
          }
        />
      ))}
    </Rows>
  );
}

/** What went wrong and what to do about it, by cause. */
function failureAdvice(status: number, message: string): string {
  if (status === 403) return 'You need the user:admin permission for this. Ask an administrator.';
  return `${message} Try again.`;
}
