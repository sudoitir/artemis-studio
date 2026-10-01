import { ActionIcon, Group, Stack, Text, Tooltip } from '@mantine/core';
import { IconChartBar, IconRefresh, IconTrash } from '@tabler/icons-react';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { TokenView } from './api.ts';
import { overlapNote, tokenState } from './format.ts';
import actionClasses from './TokenActions.module.css';
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

/** What a key's row controls do; the view owns the dialogs they open. */
export interface KeyControls {
  onUsage: (token: TokenView) => void;
  onRotate: (token: TokenView) => void;
  onRevoke: (token: TokenView) => void;
}

/** Usage, Rotate and Revoke for one of the signed-in user's keys; the last two are for a live key only. */
export function KeyActions({ token, controls }: Readonly<{ token: TokenView; controls: KeyControls }>) {
  const live = tokenState(token) === 'Active';
  return (
    <span className={actionClasses.controls}>
      <Tooltip label="Usage">
        <ActionIcon variant="subtle" onClick={() => controls.onUsage(token)} aria-label={`Usage of ${token.name}`}>
          <IconChartBar size="1rem" aria-hidden />
        </ActionIcon>
      </Tooltip>
      <Tooltip label="Rotate">
        <ActionIcon
          variant="subtle"
          disabled={!live}
          onClick={() => controls.onRotate(token)}
          aria-label={`Rotate ${token.name}`}
        >
          <IconRefresh size="1rem" aria-hidden />
        </ActionIcon>
      </Tooltip>
      <Tooltip label="Revoke">
        <ActionIcon
          variant="subtle"
          disabled={!live}
          onClick={() => controls.onRevoke(token)}
          aria-label={`Revoke ${token.name}`}
        >
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      </Tooltip>
    </span>
  );
}

/** Usage and Revoke for any user's key, named with its owner so an administrator revokes the right one. */
export function AdminKeyActions({
  token,
  controls,
}: Readonly<{ token: TokenView; controls: Pick<KeyControls, 'onUsage' | 'onRevoke'> }>) {
  return (
    <span className={actionClasses.controls}>
      <Tooltip label="Usage">
        <ActionIcon variant="subtle" onClick={() => controls.onUsage(token)} aria-label={`Usage of ${token.name}`}>
          <IconChartBar size="1rem" aria-hidden />
        </ActionIcon>
      </Tooltip>
      <Tooltip label="Revoke">
        <ActionIcon
          variant="subtle"
          disabled={tokenState(token) !== 'Active'}
          onClick={() => controls.onRevoke(token)}
          aria-label={`Revoke ${token.name} of ${token.owner}`}
        >
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      </Tooltip>
    </span>
  );
}
