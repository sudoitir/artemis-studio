import { useRef, useState } from 'react';
import { ActionIcon, Button, Modal, PasswordInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { IconX } from '@tabler/icons-react';

import { needsReauthentication } from '../../kernel/auth/api.ts';
import { StepUpPrompt } from '../../kernel/auth/StepUp.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { TwoStepStatus } from './cells.tsx';
import { userColumns } from './columns.ts';
import { EffectivePermissionsDrawer } from './EffectivePermissionsDrawer.tsx';
import { withNotice } from './outcomes.ts';
import { UserSessionsDrawer } from './UserSessionsDrawer.tsx';
import {
  useAddGrant,
  useCreateUser,
  useRemoveGrant,
  useResetSecondFactors,
  useRoles,
  useSetUserDisabled,
  useUnlockUser,
  useUsers,
  type UserView,
} from './api.ts';
import classes from './Security.module.css';

const UNLOCK: ActionVerb = { verb: 'Unlock', past: 'Unlocked', progressive: 'Unlocking' };
const RESET: ActionVerb = { verb: 'Reset', past: 'Reset', progressive: 'Resetting' };
const ENABLE: ActionVerb = { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' };
const DISABLE: ActionVerb = { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };
const GRANT: ActionVerb = { verb: 'Grant', past: 'Granted', progressive: 'Granting' };
const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };

type UserGrant = UserView['grants'][number];

const rowKey = (u: UserView) => u.id;

const grantLabel = (g: UserGrant) =>
  `${g.roleName}${g.scopeType === 'GLOBAL' ? '' : ` (${g.scopeType.toLowerCase()})`}`;

/** User accounts and their role grants (authorization spec). Requires `user:admin`. */
export function UsersPanel() {
  const users = useUsers();
  const unlock = useUnlockUser();
  const reset = useResetSecondFactors();
  const setDisabled = useSetUserDisabled();
  const [resetting, setResetting] = useState<UserView | null>(null);
  const [resetOpen, setResetOpen] = useState(false);
  const [removing, setRemoving] = useState<{ user: UserView; grant: UserGrant } | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [grantingFor, setGrantingFor] = useState<UserView | null>(null);
  const [previewing, setPreviewing] = useState<{ id: string; username: string } | null>(null);
  const [inspectingSessions, setInspectingSessions] = useState<{ id: string; username: string } | null>(null);

  const toggle = (u: UserView) =>
    setDisabled.mutate(
      { userId: u.id, disabled: !u.disabled },
      withNotice(
        u.disabled ? ENABLE : DISABLE,
        `${u.username}`,
        `The account is still ${u.disabled ? 'disabled' : 'enabled'}. Try again.`,
      ),
    );

  // Built each render: the cells carry what is busy right now.
  const columns = userColumns({
    twoStep: (u) => (
      <TwoStepStatus
        user={u}
        onReset={() => {
          setResetting(u);
          setResetOpen(true);
        }}
      />
    ),
    grants: (u) => (
      <ul className={classes.grants} aria-label={`Roles of ${u.username}`}>
        {u.grants.map((g) => (
          <li key={`${g.roleId}-${g.scopeType}-${g.scopeId ?? 'global'}`} className={classes.grant}>
            {grantLabel(g)}
            <ActionIcon
              variant="subtle"
              size="sm"
              aria-label={`Remove ${grantLabel(g)} from ${u.username}`}
              onClick={() => {
                setRemoving({ user: u, grant: g });
                setRemoveOpen(true);
              }}
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
            onClick={() => setGrantingFor(u)}
          >
            Grant a role
          </Button>
        </li>
      </ul>
    ),
    enabled: (u) => (
      <Switch
        checked={!u.disabled}
        disabled={setDisabled.isPending && setDisabled.variables?.userId === u.id}
        onChange={() => toggle(u)}
        size="sm"
        aria-label={`${u.disabled ? 'Enable' : 'Disable'} ${u.username}`}
      />
    ),
    actions: (u) => (
      <span className={classes.controls}>
        {u.lockedUntil ? (
          <Button
            size="xs"
            variant="default"
            aria-label={`Unlock ${u.username}`}
            loading={unlock.isPending && unlock.variables === u.id}
            disabled={unlock.isPending}
            onClick={() =>
              unlock.mutate(u.id, withNotice(UNLOCK, u.username, 'The account is still locked. Try again.'))
            }
          >
            Unlock
          </Button>
        ) : null}
        <Button
          size="xs"
          variant="subtle"
          aria-label={`Sessions of ${u.username}`}
          onClick={() => setInspectingSessions(u)}
        >
          Sessions
        </Button>
        <Button
          size="xs"
          variant="subtle"
          aria-label={`Effective permissions of ${u.username}`}
          onClick={() => setPreviewing(u)}
        >
          Effective permissions
        </Button>
      </span>
    ),
  });

  const count = users.data?.length;

  return (
    <Section title="Users" description="Accounts that can sign in, how each signs in, and the roles each holds.">
      <DataTable
        variant="static"
        label="Users"
        storageKey="security.users"
        columns={columns}
        data={users.data ?? []}
        rowKey={rowKey}
        loading={users.isPending}
        error={users.isError ? <ErrorState error={users.error} onRetry={() => void users.refetch()} /> : undefined}
        toolbar={{
          start: <Button onClick={() => setCreateOpen(true)}>New user</Button>,
          end:
            count === undefined ? undefined : (
              <Text size="sm" c="dimmed">
                {count} user{count === 1 ? '' : 's'}
              </Text>
            ),
        }}
        empty={
          <EmptyState
            kind="empty"
            title="No users"
            description="Users sign in with a local password or through an identity provider. Create one to give someone access."
          />
        }
      />

      <EffectivePermissionsDrawer user={previewing} onClose={() => setPreviewing(null)} />
      <UserSessionsDrawer user={inspectingSessions} onClose={() => setInspectingSessions(null)} />

      <ResetDialog
        user={resetting}
        opened={resetOpen}
        reset={reset}
        onClose={() => {
          setResetOpen(false);
          reset.reset();
        }}
      />
      <RemoveGrantDialog removing={removing} opened={removeOpen} onClose={() => setRemoveOpen(false)} />
      <NewUserModal opened={createOpen} onClose={() => setCreateOpen(false)} />
      <GrantModal user={grantingFor} onClose={() => setGrantingFor(null)} />
    </Section>
  );
}

/** States what removing a role takes from the user before it can be armed, then asks for their name. */
function RemoveGrantDialog({
  removing,
  opened,
  onClose,
}: Readonly<{ removing: { user: UserView; grant: UserGrant } | null; opened: boolean; onClose: () => void }>) {
  const removeGrant = useRemoveGrant();
  const confirm = ({ user, grant }: { user: UserView; grant: UserGrant }) =>
    removeGrant.mutate(
      {
        userId: user.id,
        roleId: grant.roleId,
        scopeType: grant.scopeType,
        scopeId: grant.scopeId ?? undefined,
      },
      withNotice(REMOVE, `${grantLabel(grant)} from ${user.username}`, 'They still hold the role. Try again.', onClose),
    );

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={removing ? `Remove ${grantLabel(removing.grant)} from ${removing.user.username}` : 'Remove role'}
      tone="danger"
      typedName={removing?.user.username}
      pending={removeGrant.isPending}
      confirmLabel="Remove role"
      consequence={
        removing
          ? `${removing.user.username} loses the permissions that ${grantLabel(removing.grant)} gave them. You can grant it again.`
          : ''
      }
      onConfirm={() => removing && confirm(removing)}
    />
  );
}

/** What a refused reset means for the administrator, and what to do about it. */
function resetFailure(error: { type: string; message: string }): { cause: string; next: string } {
  if (error.type.endsWith('/self-reset')) {
    return {
      cause: 'You cannot reset your own two-step verification here.',
      next: 'Sign in with one of your recovery codes instead.',
    };
  }
  if (error.type.endsWith('/mfa-required')) {
    return {
      cause: 'This user must hold a second factor, so your own session has to have verified one.',
      next: 'Sign out, sign in with your second factor, then try again.',
    };
  }
  return { cause: error.message, next: 'Nothing was reset. Try again.' };
}

function ResetDialog({
  user,
  opened,
  reset,
  onClose,
}: Readonly<{
  user: UserView | null;
  opened: boolean;
  reset: ReturnType<typeof useResetSecondFactors>;
  onClose: () => void;
}>) {
  const confirm = (u: UserView) => {
    const subject = `two-step verification of ${u.username}`;
    reset.mutate(u.id, {
      onSuccess: () => {
        notify.succeeded({ action: RESET, subject });
        onClose();
      },
      onError: (error) => {
        // A stale sign-in is answered by the prompt in the dialog, not by a failure.
        if (!needsReauthentication(error)) notify.failed({ action: RESET, subject, ...resetFailure(error) });
      },
    });
  };

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={user ? `Reset two-step verification of ${user.username}` : 'Reset two-step verification'}
      tone="danger"
      typedName={user?.username}
      pending={reset.isPending}
      confirmLabel="Reset two-step verification"
      consequence={
        user ? (
          <Stack gap="sm">
            <Text size="sm">
              This removes {user.username}&apos;s authenticator app, passkeys, recovery codes and trusted devices,
              revokes their API keys and signs them out everywhere.
            </Text>
            <Text size="sm" c="dimmed">
              {user.secondFactorRequired
                ? 'Their role requires two-step verification, so they set it up again the next time they sign in.'
                : 'Signing in then needs only their password.'}
            </Text>
            <StepUpPrompt
              error={reset.error}
              returnTo={`${globalThis.location.pathname}${globalThis.location.search}`}
            />
          </Stack>
        ) : (
          ''
        )
      }
      onConfirm={() => user && confirm(user)}
    />
  );
}

function NewUserModal({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  const createUser = useCreateUser();
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [usernameError, setUsernameError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const usernameInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const policyReason = createUser.error?.type.endsWith('/password-policy') ? createUser.error.message : undefined;

  const close = () => {
    onClose();
    createUser.reset();
    setUsernameError(null);
    setPasswordError(null);
  };

  const submit = () => {
    const nameMissing = !username.trim();
    const passwordMissing = !password;
    setUsernameError(nameMissing ? 'Enter the username they sign in with.' : null);
    setPasswordError(passwordMissing ? 'Enter an initial password.' : null);
    if (nameMissing) {
      usernameInput.current?.focus();
      return;
    }
    if (passwordMissing) {
      passwordInput.current?.focus();
      return;
    }
    const subject = `user ${username.trim()}`;
    createUser.mutate(
      { username: username.trim(), email: email || undefined, password },
      {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          close();
          setUsername('');
          setEmail('');
          setPassword('');
        },
        onError: (error) => {
          // A refused password is explained beside its field.
          if (error.type.endsWith('/password-policy')) passwordInput.current?.focus();
          else
            notify.failed({ action: CREATE, subject, cause: error.message, next: 'No user was created. Try again.' });
        },
      },
    );
  };

  return (
    <Modal opened={opened} onClose={close} title="New user">
      <Stack gap="sm">
        <TextInput
          ref={usernameInput}
          label="Username"
          value={username}
          onChange={(e) => setUsername(e.currentTarget.value)}
          onBlur={() => setUsernameError(username.trim() ? null : 'Enter the username they sign in with.')}
          error={usernameError}
          required
        />
        <TextInput label="Email" value={email} onChange={(e) => setEmail(e.currentTarget.value)} />
        <PasswordInput
          ref={passwordInput}
          label="Initial password"
          value={password}
          onChange={(e) => setPassword(e.currentTarget.value)}
          onBlur={() => setPasswordError(password ? null : 'Enter an initial password.')}
          description="The user will be required to change it on first login."
          error={policyReason ?? passwordError}
          required
        />
        <Button loading={createUser.isPending} onClick={submit}>
          Create
        </Button>
      </Stack>
    </Modal>
  );
}

function GrantModal({ user, onClose }: Readonly<{ user: UserView | null; onClose: () => void }>) {
  const roles = useRoles();
  const addGrant = useAddGrant();
  const [roleId, setRoleId] = useState<string | null>(null);
  const [roleError, setRoleError] = useState<string | null>(null);
  const roleInput = useRef<HTMLInputElement>(null);
  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));

  const close = () => {
    onClose();
    setRoleError(null);
  };

  const submit = () => {
    if (!user) return;
    if (!roleId) {
      setRoleError('Choose the role to grant.');
      roleInput.current?.focus();
      return;
    }
    const role = roleOptions.find((r) => r.value === roleId)?.label ?? 'the role';
    addGrant.mutate(
      { userId: user.id, body: { roleId, scopeType: 'GLOBAL' } },
      withNotice(GRANT, `${role} to ${user.username}`, 'They do not hold the role. Try again.', () => {
        close();
        setRoleId(null);
      }),
    );
  };

  return (
    <Modal opened={user !== null} onClose={close} title={user ? `Grant a role to ${user.username}` : 'Grant a role'}>
      <Stack gap="sm">
        <Select
          ref={roleInput}
          label="Role"
          data={roleOptions}
          value={roleId}
          onChange={(value) => {
            setRoleId(value);
            if (value) setRoleError(null);
          }}
          error={roleError}
          description="Granted globally. Use the API to scope a grant to one environment or cluster."
          placeholder="Select a role"
          required
        />
        <Button loading={addGrant.isPending} onClick={submit}>
          Grant
        </Button>
      </Stack>
    </Modal>
  );
}
