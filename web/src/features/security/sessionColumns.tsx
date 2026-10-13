import { Button, Group, Text } from '@mantine/core';

import { describeClient } from '../../kernel/auth/clientLabel.ts';
import { Ago } from '../../kernel/time/Ago.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import type { AccountSessionView } from './api.ts';

/** What a sessions table needs from its manager: who is looking, the clock, and the ending in progress. */
export interface SessionControls {
  admin: boolean;
  now: number;
  busy: boolean;
  isEnding: (s: AccountSessionView) => boolean;
  endOne: (s: AccountSessionView) => void;
}

export const describeSession = (s: AccountSessionView) =>
  `${describeClient(s.userAgent)} at ${s.clientAddress ?? 'an unknown address'}`;

const endLabel = (s: AccountSessionView, admin: boolean) => {
  if (s.current) {
    return 'Sign out here, ending this session';
  }
  const verb = admin ? 'End' : 'Sign out';
  return `${verb} ${describeSession(s)}`;
};

function sessionAction(s: AccountSessionView, admin: boolean): string {
  if (s.current) return 'Sign out here';
  return admin ? 'End' : 'Sign out';
}

/** The sessions' columns: the client names a session, and the action to end it is never hidden. */
export function sessionColumns(controls: SessionControls): Column<AccountSessionView>[] {
  const { admin, now } = controls;
  return [
    {
      id: 'client',
      header: 'Client',
      accessor: (s) => describeClient(s.userAgent),
      cell: (s) => (
        <Group gap="xs" wrap="nowrap">
          <Text size="sm" fw={500} truncate title={s.userAgent ?? undefined}>
            {describeClient(s.userAgent)}
          </Text>
          {s.current ? <StatusBadge>This session</StatusBadge> : null}
        </Group>
      ),
      kind: 'text',
      priority: 'essential',
      min: 24,
    },
    {
      id: 'address',
      header: 'Address',
      accessor: (s) => s.clientAddress ?? 'Address unknown',
      kind: 'code',
      priority: 'high',
    },
    {
      id: 'signedIn',
      header: 'Signed in',
      accessor: (s) => s.signedInAt,
      cell: (s) => <Ago at={s.signedInAt} now={now} />,
      kind: 'time',
      priority: 'low',
    },
    {
      id: 'lastActive',
      header: 'Last active',
      accessor: (s) => s.lastActivityAt,
      cell: (s) => <Ago at={s.lastActivityAt} now={now} />,
      kind: 'time',
      priority: 'high',
    },
    {
      id: 'end',
      header: 'Action',
      accessor: (s) => sessionAction(s, admin),
      cell: (s) => (
        <Button
          size="xs"
          // Signing out where you are ends what you are doing, so it reads as a button, not a quiet link.
          variant={s.current ? 'default' : 'subtle'}
          aria-label={endLabel(s, admin)}
          loading={controls.isEnding(s)}
          disabled={controls.busy}
          onClick={() => controls.endOne(s)}
        >
          {sessionAction(s, admin)}
        </Button>
      ),
      kind: 'status',
      priority: 'essential',
    },
  ];
}
