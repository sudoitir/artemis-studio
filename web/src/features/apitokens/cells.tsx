import { Group, Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { TokenView } from './api.ts';
import { overlapNote, tokenState } from './format.ts';
import classes from './TokenParts.module.css';

const TONES = { Active: 'neutral', Expired: 'warning', Revoked: 'danger' } as const;

/** Revoked, expired or active, in words; colour only where something is wrong. `stale` adds that it has gone unused. */
export function TokenStatus({ token, stale = false }: Readonly<{ token: TokenView; stale?: boolean }>) {
  const state = tokenState(token);
  return (
    <Group gap="xs">
      <StatusBadge tone={TONES[state]}>{state}</StatusBadge>
      {stale ? <StatusBadge tone="warning">Stale</StatusBadge> : null}
    </Group>
  );
}

/** A key's name, with the prefix that identifies it beneath. */
export function KeyName({ token }: Readonly<{ token: TokenView }>) {
  return (
    <Stack gap={0}>
      <Text size="sm">{token.name}</Text>
      <Text size="xs" className={classes.prefix}>
        {token.prefix}
      </Text>
    </Stack>
  );
}

/** When a key expires, and while an old secret still works, until when. */
export function Expiry({ token, label }: Readonly<{ token: TokenView; label: string }>) {
  const note = overlapNote(token);
  return (
    <Stack gap={0}>
      <Text size="sm">{label}</Text>
      {note ? (
        <Text size="xs" c="dimmed">
          {note}
        </Text>
      ) : null}
    </Stack>
  );
}
