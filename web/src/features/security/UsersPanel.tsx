import { useState } from 'react';
import { Button, Modal, PasswordInput, Select, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';

import { needsReauthentication, useMe } from '../../kernel/auth/api.ts';
import { StepUpPrompt } from '../../kernel/auth/StepUp.tsx';
import { ConfirmDialog } from '../../ui/ConfirmDialog.tsx';
import { DialogActions, useDiscardGuard } from '../../ui/DialogActions.tsx';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { focusFirstInvalid } from '../../ui/formErrors.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { userColumns } from './columns.ts';
import { AccessCheckDrawer } from './AccessCheckDrawer.tsx';
import { withNotice } from './outcomes.ts';
import { UserSessionsDrawer } from './UserSessionsDrawer.tsx';
import { ScopeFields } from './ScopeFields.tsx';
import { GLOBAL_SCOPE, scopeBody, scopeError, useScopeLabel, type GrantScope } from './scope.ts';
import { grantText, type ScopeLabel } from './words.ts';
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

const UNLOCK: ActionVerb = { verb: 'Unlock', past: 'Unlocked', progressive: 'Unlocking' };
const RESET: ActionVerb = { verb: 'Reset', past: 'Reset', progressive: 'Resetting' };
const ENABLE: ActionVerb = { verb: 'Enable', past: 'Enabled', progressive: 'Enabling' };
const DISABLE: ActionVerb = { verb: 'Disable', past: 'Disabled', progressive: 'Disabling' };
const REMOVE: ActionVerb = { verb: 'Remove', past: 'Removed', progressive: 'Removing' };
const GRANT: ActionVerb = { verb: 'Grant', past: 'Granted', progressive: 'Granting' };
const CREATE: ActionVerb = { verb: 'Create', past: 'Created', progressive: 'Creating' };

type UserGrant = UserView['grants'][number];

const rowKey = (u: UserView) => u.id;

/** User accounts and their role grants (authorization spec). Requires `user:admin`. */
export function UsersPanel() {
  const users = useUsers();
  const unlock = useUnlockUser();
  const reset = useResetSecondFactors();
  const setDisabled = useSetUserDisabled();
  const [resetting, setResetting] = useState<UserView | null>(null);
  const [resetOpen, setResetOpen] = useState(false);
  // The dialog keeps what it was about while it fades out, so its words do not change under the reader.
  const [disabling, setDisabling] = useState<UserView | null>(null);
  const [disableOpen, setDisableOpen] = useState(false);
  const [removing, setRemoving] = useState<{ user: UserView; grant: UserGrant } | null>(null);
  const [removeOpen, setRemoveOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [grantingFor, setGrantingFor] = useState<UserView | null>(null);
  const [previewing, setPreviewing] = useState<{ id: string; username: string } | null>(null);
  const [inspectingSessions, setInspectingSessions] = useState<{ id: string; username: string } | null>(null);
  const scopeLabel = useScopeLabel();

  const change = (u: UserView, onDone?: () => void) =>
    setDisabled.mutate(
      { userId: u.id, disabled: !u.disabled },
      withNotice(
        u.disabled ? ENABLE : DISABLE,
        `${u.username}`,
        `The account is still ${u.disabled ? 'disabled' : 'enabled'}. Try again.`,
        onDone,
      ),
    );
  // Enabling gives access back and asks nothing; disabling signs someone out, so it is confirmed first.
  const toggle = (u: UserView) => {
    if (u.disabled) {
      change(u);
      return;
    }
    setDisabling(u);
    setDisableOpen(true);
  };

  // Built each render: the cells carry what is busy right now.
  const columns = userColumns({
    controls: {
      togglingId: setDisabled.isPending ? setDisabled.variables?.userId : undefined,
      unlockingId: unlock.isPending ? unlock.variables : undefined,
      onReset: (u) => {
        setResetting(u);
        setResetOpen(true);
      },
      onRemoveGrant: (u, g) => {
        setRemoving({ user: u, grant: g });
        setRemoveOpen(true);
      },
      onGrant: setGrantingFor,
      onToggle: toggle,
      onUnlock: (u) => unlock.mutate(u.id, withNotice(UNLOCK, u.username, 'The account is still locked. Try again.')),
      onSessions: setInspectingSessions,
      onPermissions: setPreviewing,
      scopeLabel,
    },
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

      <AccessCheckDrawer user={previewing} onClose={() => setPreviewing(null)} />
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
      <DisableDialog
        user={disabling}
        opened={disableOpen}
        pending={setDisabled.isPending}
        onClose={() => setDisableOpen(false)}
        onConfirm={(u) => change(u, () => setDisableOpen(false))}
      />
      <RemoveGrantDialog
        removing={removing}
        scopeLabel={scopeLabel}
        opened={removeOpen}
        onClose={() => setRemoveOpen(false)}
      />
      <NewUserModal opened={createOpen} onClose={() => setCreateOpen(false)} />
      <GrantModal user={grantingFor} onClose={() => setGrantingFor(null)} />
    </Section>
  );
}

/** States what disabling an account stops; your own account cannot be disabled from here. */
function DisableDialog({
  user,
  opened,
  pending,
  onClose,
  onConfirm,
}: Readonly<{
  user: UserView | null;
  opened: boolean;
  pending: boolean;
  onClose: () => void;
  onConfirm: (user: UserView) => void;
}>) {
  const me = useMe().data;
  const self = user !== null && me?.id === user.id;
  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={user ? `Disable ${user.username}?` : 'Disable account?'}
      tone="danger"
      pending={pending}
      blocked={
        self
          ? 'You cannot disable your own account: it would sign you out with no way back in. Ask another administrator.'
          : undefined
      }
      confirmLabel="Disable account"
      consequence={
        user
          ? `${user.username} is signed out everywhere and cannot sign in, their API keys stop working and their trusted devices are forgotten. Enabling the account again restores sign-in.`
          : ''
      }
      onConfirm={() => user && onConfirm(user)}
    />
  );
}

/** States what removing a role takes from the user; it is granted again as easily, so it asks once. */
function RemoveGrantDialog({
  removing,
  opened,
  onClose,
  scopeLabel,
}: Readonly<{
  removing: { user: UserView; grant: UserGrant } | null;
  opened: boolean;
  onClose: () => void;
  scopeLabel: ScopeLabel;
}>) {
  const removeGrant = useRemoveGrant();
  const confirm = ({ user, grant }: { user: UserView; grant: UserGrant }) =>
    removeGrant.mutate(
      {
        userId: user.id,
        roleId: grant.roleId,
        scopeType: grant.scopeType,
        scopeId: grant.scopeId ?? undefined,
      },
      withNotice(
        REMOVE,
        `${grantText(grant, scopeLabel)} from ${user.username}`,
        'They still hold the role. Try again.',
        onClose,
      ),
    );

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={
        removing ? `Remove ${grantText(removing.grant, scopeLabel)} from ${removing.user.username}` : 'Remove role'
      }
      tone="danger"
      pending={removeGrant.isPending}
      confirmLabel="Remove role"
      consequence={
        removing
          ? `${removing.user.username} loses the permissions that ${grantText(removing.grant, scopeLabel)} gave them. You can grant it again.`
          : ''
      }
      onConfirm={() => removing && confirm(removing)}
    />
  );
}

/** What a refused reset means for the administrator, and what to do about it, in one line. */
function resetFailure(error: { type: string; message: string }): string {
  if (error.type.endsWith('/self-reset')) {
    return 'You cannot reset your own two-step verification here. Sign in with one of your recovery codes instead.';
  }
  if (error.type.endsWith('/mfa-required')) {
    return 'This user must hold a second factor, so your own session has to have verified one. Sign out, sign in with your second factor, then try again.';
  }
  return 'Nothing was reset. Try again.';
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
    });
  };

  return (
    <ConfirmDialog
      opened={opened}
      onClose={onClose}
      title={user ? `Reset two-step verification of ${user.username}` : 'Reset two-step verification'}
      tone="danger"
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
            {/* A stale sign-in is answered by the prompt above, not by a failure. */}
            {reset.error && !needsReauthentication(reset.error) ? (
              <ErrorState variant="inline" error={reset.error} next={resetFailure(reset.error)} />
            ) : null}
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
  const form = useForm({
    initialValues: { username: '', email: '', password: '' },
    validateInputOnBlur: true,
    validate: {
      username: (v) => (v.trim() ? null : 'Enter the username they sign in with.'),
      password: (v) => (v ? null : 'Enter an initial password.'),
    },
  });

  const close = () => {
    onClose();
    createUser.reset();
    form.reset();
  };
  const guard = useDiscardGuard(form.isDirty(), close);

  const submit = form.onSubmit(({ username, email, password }) => {
    const subject = `user ${username.trim()}`;
    createUser.mutate(
      { username: username.trim(), email: email || undefined, password },
      {
        onSuccess: () => {
          notify.succeeded({ action: CREATE, subject });
          close();
        },
        onError: (error) => {
          // A refused password is explained beside its field.
          if (error.type.endsWith('/password-policy')) {
            form.setErrors({ password: error.message });
            form.getInputNode('password')?.focus();
          } else {
            notify.settle(error, {
              action: CREATE,
              subject,
              cause: error.message,
              next: 'No user was created. Try again.',
              onHeld: close,
            });
          }
        },
      },
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <Modal opened={opened} {...guard.modalProps} title="New user">
      <form noValidate onSubmit={submit}>
        <Stack gap="sm">
          {guard.prompt}
          <TextInput label="Username" {...form.getInputProps('username')} required />
          <TextInput label="Email" {...form.getInputProps('email')} />
          <PasswordInput
            label="Initial password"
            description="The user will be required to change it on first login."
            {...form.getInputProps('password')}
            required
          />
          <DialogActions>
            <Button variant="default" onClick={guard.modalProps.onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={createUser.isPending}>
              Create user
            </Button>
          </DialogActions>
        </Stack>
      </form>
    </Modal>
  );
}

function GrantModal({ user, onClose }: Readonly<{ user: UserView | null; onClose: () => void }>) {
  const roles = useRoles();
  const addGrant = useAddGrant();
  const form = useForm<{ roleId: string | null; scope: GrantScope }>({
    initialValues: { roleId: null, scope: GLOBAL_SCOPE },
    validateInputOnBlur: true,
    validate: { roleId: (v) => (v ? null : 'Choose the role to grant.'), scope: scopeError },
  });
  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));

  const close = () => {
    onClose();
    form.reset();
  };
  const guard = useDiscardGuard(form.isDirty(), close);

  const submit = form.onSubmit(({ roleId, scope }) => {
    if (!user || !roleId) return;
    const role = roleOptions.find((r) => r.value === roleId)?.label ?? 'the role';
    addGrant.mutate(
      { userId: user.id, body: { roleId, ...scopeBody(scope) } },
      withNotice(GRANT, `${role} to ${user.username}`, 'They do not hold the role. Try again.', close),
    );
  }, focusFirstInvalid(form.getInputNode));

  return (
    <Modal
      opened={user !== null}
      {...guard.modalProps}
      title={user ? `Grant a role to ${user.username}` : 'Grant a role'}
    >
      <form noValidate onSubmit={submit}>
        <Stack gap="sm">
          {guard.prompt}
          <Select
            label="Role"
            data={roleOptions}
            {...form.getInputProps('roleId')}
            placeholder="Select a role"
            required
          />
          <ScopeFields {...form.getInputProps('scope')} />
          <DialogActions>
            <Button variant="default" onClick={guard.modalProps.onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={addGrant.isPending}>
              Grant role
            </Button>
          </DialogActions>
        </Stack>
      </form>
    </Modal>
  );
}
