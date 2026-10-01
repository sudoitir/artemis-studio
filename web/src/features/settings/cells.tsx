import { Stack, Text } from '@mantine/core';

import { When } from '../../kernel/time/When.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { NodeHealth, ReplicaHealth } from './api.ts';
import { REPLICA_STATES, figureText, lagText } from './healthWords.ts';
import classes from './Settings.module.css';

/** State goes in words; the colour only underlines a problem. */
export function Verdict({ degraded }: Readonly<{ degraded: boolean }>) {
  return <StatusBadge tone={degraded ? 'danger' : 'neutral'}>{degraded ? 'Degraded' : 'Healthy'}</StatusBadge>;
}

/** A moment as how long ago it was and the exact time, or that it never happened. */
export function Moment({ at, now }: Readonly<{ at: string | null | undefined; now: number }>) {
  if (!at) {
    return (
      <Text size="sm" c="dimmed">
        Never
      </Text>
    );
  }
  return <When at={at} now={now} />;
}

/** A measurement with its unit, or that it is unavailable: never a zero standing in for no reading. */
export function Figure({ value, unit }: Readonly<{ value: number | null | undefined; unit?: string }>) {
  const unavailable = value === null || value === undefined;
  return (
    <Text size="sm" c={unavailable ? 'dimmed' : undefined} className={classes.figure}>
      {figureText(value, unit)}
    </Text>
  );
}

export function Lag({ seconds }: Readonly<{ seconds: number | null | undefined }>) {
  return (
    <Text size="sm" c={seconds === null || seconds === undefined ? 'dimmed' : undefined} className={classes.figure}>
      {lagText(seconds)}
    </Text>
  );
}

/** A replica's host, that it is the one answering, and the start of its id. */
export function ReplicaName({ replica: r }: Readonly<{ replica: ReplicaHealth }>) {
  return (
    <Stack gap={0}>
      <Text size="sm">
        {r.host} {r.self ? <StatusBadge>This replica</StatusBadge> : null}
      </Text>
      <Text size="xs" c="dimmed" className={classes.figure}>
        {r.id.slice(0, 8)}
      </Text>
    </Stack>
  );
}

export function ReplicaState({ state }: Readonly<{ state: ReplicaHealth['state'] }>) {
  const { label, detail } = REPLICA_STATES[state];
  return (
    <Stack gap={0}>
      <Text size="sm">{label}</Text>
      {detail ? (
        <Text size="xs" c="dimmed">
          {detail}
        </Text>
      ) : null}
    </Stack>
  );
}

export function ReplicaHealthCell({ replica }: Readonly<{ replica: ReplicaHealth }>) {
  if (replica.state === 'STOPPED') {
    return (
      <Text size="sm" c="dimmed">
        Not running
      </Text>
    );
  }
  return <Verdict degraded={replica.degraded} />;
}

/** A node's name, with the management address it is called on beneath. */
export function NodeName({ node: n }: Readonly<{ node: NodeHealth }>) {
  return (
    <Stack gap={0}>
      <Text size="sm">{n.name}</Text>
      <Text size="xs" c="dimmed" className={classes.figure}>
        {n.node ?? 'No management address'}
      </Text>
    </Stack>
  );
}

/** When a node last failed, and what it said. */
export function LastFailure({ node: n, now }: Readonly<{ node: NodeHealth; now: number }>) {
  return (
    <>
      <Moment at={n.lastFailure} now={now} />
      {n.lastError ? (
        <Text size="xs" c="dimmed">
          {n.lastError}
        </Text>
      ) : null}
    </>
  );
}
