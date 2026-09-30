import { useState } from 'react';
import { Alert, Badge, Button, Group, Loader, Stack, Table, Text, VisuallyHidden } from '@mantine/core';

import { useLogout } from '../../kernel/auth/api.ts';
import { useServerNow } from '../../kernel/time/time.ts';
import { When } from '../../kernel/time/When.tsx';
import { useEndOtherSessions, useEndSession, useSessions, type AccountSessionView } from './api.ts';
import { describeClient } from '../../kernel/auth/clientLabel.ts';

interface Outcome {
  text: string;
  failed: boolean;
}

const describeSession = (s: AccountSessionView) =>
  `${describeClient(s.userAgent)} at ${s.clientAddress ?? 'an unknown address'}`;

/**
 * The signed-in sessions of the caller, or of the user `userId` names when an administrator looks
 * (ADR-0144), each ended with one action. The caller's own current session is marked in words and
 * ends by signing out, wherever it is listed. Every outcome is announced, and a failure says why and
 * what to do.
 */
export function SessionsManager({ userId }: { userId?: string }) {
  const admin = userId !== undefined;
  const sessions = useSessions(userId);
  const end = useEndSession(userId);
  const endOthers = useEndOtherSessions(userId);
  const logout = useLogout();
  const now = useServerNow(30_000);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const busy = end.isPending || endOthers.isPending || logout.isPending;
  const list = sessions.data ?? [];
  const others = list.filter((s) => !s.current);
  const endOthersLabel = admin ? 'End all sessions' : 'Sign out all other sessions';
  const noOthersReason = admin ? 'This user has no other sessions.' : 'You are not signed in anywhere else.';

  function endOne(s: AccountSessionView) {
    setOutcome(null);
    const label = describeSession(s);
    if (s.current) {
      logout.mutate(undefined, {
        onSuccess: () => window.location.assign('/login'),
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
        setOutcome({
          text: ended === 0 ? `There were no ${noun} to end.` : `${admin ? 'Ended' : 'Signed out'} ${ended} ${noun}.`,
          failed: false,
        });
      },
      onError: (e) =>
        setOutcome({ text: `Could not end the sessions. ${failureAdvice(e.status, e.message)}`, failed: true }),
    });
  }

  if (sessions.isPending) return <Loader size="sm" aria-label="Loading sessions" />;
  if (sessions.isError) {
    return (
      <Alert color="red" variant="light" title="Could not load the sessions" role="alert">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{sessions.error.message} Check your connection and try again.</Text>
          <Button size="xs" variant="light" onClick={() => void sessions.refetch()}>
            Retry
          </Button>
        </Stack>
      </Alert>
    );
  }

  return (
    <Stack gap="md">
      {list.length === 0 ? (
        <Text size="sm">
          {admin
            ? 'This user is not signed in anywhere. A session is listed here from the moment they sign in.'
            : 'No sessions are listed.'}
        </Text>
      ) : (
        <Table verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Client</Table.Th>
              <Table.Th>Signed in</Table.Th>
              <Table.Th>Last active</Table.Th>
              <Table.Th>
                <VisuallyHidden>Action</VisuallyHidden>
              </Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {list.map((s) => (
              <Table.Tr key={s.handle}>
                <Table.Td>
                  <Group gap="xs" wrap="wrap">
                    <Text size="sm" fw={500} title={s.userAgent ?? undefined}>
                      {describeClient(s.userAgent)}
                    </Text>
                    {s.current ? (
                      <Badge size="xs" variant="default">
                        This session
                      </Badge>
                    ) : null}
                  </Group>
                  <Text size="xs" c="dimmed">
                    {s.clientAddress ?? 'Address unknown'}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <When at={s.signedInAt} now={now} />
                </Table.Td>
                <Table.Td>
                  <When at={s.lastActivityAt} now={now} />
                </Table.Td>
                <Table.Td>
                  <Button
                    size="xs"
                    variant="default"
                    aria-label={
                      s.current ? 'Sign out of this session' : `${admin ? 'End' : 'Sign out'} ${describeSession(s)}`
                    }
                    loading={(end.isPending && end.variables === s.handle) || (s.current && logout.isPending)}
                    disabled={busy}
                    onClick={() => endOne(s)}
                  >
                    {admin && !s.current ? 'End' : 'Sign out'}
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <Group gap="sm">
        <Button
          size="xs"
          variant="light"
          loading={endOthers.isPending}
          disabled={busy || others.length === 0}
          onClick={endAllOthers}
        >
          {endOthersLabel}
          {others.length > 0 ? ` (${others.length})` : ''}
        </Button>
        {others.length === 0 ? (
          <Text size="xs" c="dimmed">
            {noOthersReason}
          </Text>
        ) : null}
      </Group>

      <div aria-live="polite">
        {busy ? (
          <Text size="sm" c="dimmed">
            {admin ? 'Ending' : 'Signing out of'} the session{endOthers.isPending ? 's' : ''}…
          </Text>
        ) : outcome ? (
          <Text size="sm" c={outcome.failed ? 'red' : 'dimmed'}>
            {outcome.text}
          </Text>
        ) : null}
      </div>
    </Stack>
  );
}

/** What went wrong and what to do about it, by cause. */
function failureAdvice(status: number, message: string): string {
  if (status === 403) return 'You need the user:admin permission for this. Ask an administrator.';
  return `${message} Try again.`;
}
