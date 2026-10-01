import { Button, Group, Stack, Text } from '@mantine/core';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { EffectivePermissionView, RoleView, UserView } from './api.ts';
import classes from './Security.module.css';
import { factorWords, twoStepText } from './words.ts';

/** A user's name, with what is wrong with the account beside it: a lock, or a password to change. */
export function UserName({ user }: Readonly<{ user: UserView }>) {
  return (
    <Stack gap={2} align="flex-start">
      <Text size="sm">{user.username}</Text>
      {user.mustChangePassword ? (
        <Text size="xs" c="dimmed">
          must change password
        </Text>
      ) : null}
      {user.lockedUntil ? (
        <StatusBadge tone="warning">
          {`Locked until ${new Date(user.lockedUntil).toLocaleTimeString([], { timeStyle: 'short' })}`}
        </StatusBadge>
      ) : null}
    </Stack>
  );
}

/** What a user's second step is, in words, with the way to reset it beside it when there is something to reset. */
export function TwoStepStatus({ user, onReset }: Readonly<{ user: UserView; onReset: () => void }>) {
  const statusId = `two-step-${user.id}`;
  const text = twoStepText(user);
  const nothing = factorWords(user).length === 0;
  const warn = user.passwordAccount && nothing && user.secondFactorRequired;
  return (
    <Stack gap={2} align="flex-start">
      {warn ? (
        <StatusBadge tone="warning">{text}</StatusBadge>
      ) : (
        <Text id={statusId} size="sm" c={user.passwordAccount && !nothing ? undefined : 'dimmed'}>
          {text}
        </Text>
      )}
      {nothing ? null : (
        <Button
          variant="subtle"
          size="compact-xs"
          aria-label={`Reset two-step verification of ${user.username}`}
          aria-describedby={statusId}
          onClick={onReset}
        >
          Reset two-step verification
        </Button>
      )}
    </Stack>
  );
}

/** A role's name, with that it is built in beside it. */
export function RoleName({ role }: Readonly<{ role: RoleView }>) {
  return (
    <Group gap="xs">
      <Text size="sm">{role.name}</Text>
      {role.builtin ? <StatusBadge>built-in</StatusBadge> : null}
    </Group>
  );
}

/** A permission, with what it lets the holder do beneath it. */
export function PermissionName({ permission }: Readonly<{ permission: EffectivePermissionView }>) {
  return (
    <Stack gap={0}>
      <Text size="sm" className={classes.code}>
        {permission.action}
      </Text>
      {permission.description ? (
        <Text size="xs" c="dimmed">
          {permission.description}
        </Text>
      ) : null}
    </Stack>
  );
}

/** The role a permission came through, and the wildcard when it came through one. */
export function GrantedThrough({ permission }: Readonly<{ permission: EffectivePermissionView }>) {
  return (
    <Stack gap={0}>
      <Text size="sm">{permission.roleName}</Text>
      {permission.via === permission.action ? null : (
        <Text size="xs" c="dimmed">
          through <span className={classes.code}>{permission.via}</span>
        </Text>
      )}
    </Stack>
  );
}

/** Whether a permission acts at its scope, and when it does not, why. */
export function Effect({ permission }: Readonly<{ permission: EffectivePermissionView }>) {
  if (permission.effective) return <Text size="sm">Granted</Text>;
  return (
    <Stack gap={2} align="flex-start">
      <StatusBadge tone="warning">No effect at this scope</StatusBadge>
      <Text size="xs">{permission.reason}</Text>
    </Stack>
  );
}
