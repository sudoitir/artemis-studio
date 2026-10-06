import { ActionIcon, Button, Group, Stack, Switch, Text } from '@mantine/core';
import { IconPencil, IconTrash, IconX } from '@tabler/icons-react';

import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { AccessCheckView, EffectivePermissionView, GroupMappingView, RoleView, UserView } from './api.ts';
import classes from './Security.module.css';
import { factorWords, grantText, sourceText, twoStepText, type ScopeLabel } from './words.ts';

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

/** What a users table's row controls need from their panel: what is busy right now, and what a click does. */
export interface UserControls {
  /** The user whose enabled switch is saving, or undefined. */
  togglingId: string | undefined;
  /** The user being unlocked, or undefined; every other unlock is locked meanwhile. */
  unlockingId: string | undefined;
  onReset: (user: UserView) => void;
  onRemoveGrant: (user: UserView, grant: UserView['grants'][number]) => void;
  onGrant: (user: UserView) => void;
  onToggle: (user: UserView) => void;
  onUnlock: (user: UserView) => void;
  onSessions: (user: UserView) => void;
  onPermissions: (user: UserView) => void;
  /** A grant's scope in words, naming its environment or cluster. */
  scopeLabel: ScopeLabel;
}

/** The roles a user holds, each removable, and the way to grant another. */
export function UserGrants({ user: u, controls }: Readonly<{ user: UserView; controls: UserControls }>) {
  return (
    <ul className={classes.grants} aria-label={`Roles of ${u.username}`}>
      {u.grants.map((g) => (
        <li key={`${g.roleId}-${g.scopeType}-${g.scopeId ?? 'global'}`} className={classes.grant}>
          {grantText(g, controls.scopeLabel)}
          <ActionIcon
            variant="subtle"
            size="sm"
            aria-label={`Remove ${grantText(g, controls.scopeLabel)} from ${u.username}`}
            onClick={() => controls.onRemoveGrant(u, g)}
          >
            <IconX size="0.875rem" aria-hidden />
          </ActionIcon>
        </li>
      ))}
      <li>
        <Button
          variant="subtle"
          size="compact-xs"
          aria-label={`Grant a role to ${u.username}`}
          onClick={() => controls.onGrant(u)}
        >
          Grant a role
        </Button>
      </li>
    </ul>
  );
}

/** The enabled switch. */
export function UserEnabled({ user: u, controls }: Readonly<{ user: UserView; controls: UserControls }>) {
  return (
    <Switch
      checked={!u.disabled}
      disabled={controls.togglingId === u.id}
      onChange={() => controls.onToggle(u)}
      size="sm"
      aria-label={`${u.disabled ? 'Enable' : 'Disable'} ${u.username}`}
    />
  );
}

/** Unlock, sessions and effective permissions. */
export function UserActions({ user: u, controls }: Readonly<{ user: UserView; controls: UserControls }>) {
  return (
    <span className={classes.controls}>
      {u.lockedUntil ? (
        <Button
          size="xs"
          variant="default"
          aria-label={`Unlock ${u.username}`}
          loading={controls.unlockingId === u.id}
          disabled={controls.unlockingId !== undefined}
          onClick={() => controls.onUnlock(u)}
        >
          Unlock
        </Button>
      ) : null}
      <Button
        size="xs"
        variant="subtle"
        aria-label={`Sessions of ${u.username}`}
        onClick={() => controls.onSessions(u)}
      >
        Sessions
      </Button>
      <Button
        size="xs"
        variant="subtle"
        aria-label={`Access check of ${u.username}`}
        onClick={() => controls.onPermissions(u)}
      >
        Access check
      </Button>
    </span>
  );
}

/** A role's Edit, and its Delete unless it is built in. */
export function RoleActions({
  role: r,
  onEdit,
  onDelete,
}: Readonly<{ role: RoleView; onEdit: (role: RoleView) => void; onDelete: (role: RoleView) => void }>) {
  return (
    <span className={classes.controls}>
      <ActionIcon variant="subtle" onClick={() => onEdit(r)} aria-label={`Edit ${r.name}`}>
        <IconPencil size="1rem" aria-hidden />
      </ActionIcon>
      {r.builtin ? null : (
        <ActionIcon variant="subtle" onClick={() => onDelete(r)} aria-label={`Delete ${r.name}`}>
          <IconTrash size="1rem" aria-hidden />
        </ActionIcon>
      )}
    </span>
  );
}

/** The delete for one group mapping. */
export function MappingActions({
  mapping: m,
  onDelete,
}: Readonly<{ mapping: GroupMappingView; onDelete: (mapping: GroupMappingView) => void }>) {
  return (
    <ActionIcon variant="subtle" onClick={() => onDelete(m)} aria-label={`Delete mapping for ${m.groupName}`}>
      <IconTrash size="1rem" aria-hidden />
    </ActionIcon>
  );
}

/** Every source that allows a permission, one to a line; what a permission that is not allowed lacks is not said. */
export function AccessSources({ view, scopeLabel }: Readonly<{ view: AccessCheckView; scopeLabel: ScopeLabel }>) {
  if (view.sources.length === 0)
    return (
      <Text size="sm" c="dimmed">
        None
      </Text>
    );
  return (
    <ul className={classes.sources} aria-label={`Sources of ${view.action}`}>
      {view.sources.map((s, i) => (
        <li key={`${s.type}-${s.roleName}-${s.teamId ?? s.scopeId ?? ''}-${i}`}>{sourceText(s, scopeLabel)}</li>
      ))}
    </ul>
  );
}
