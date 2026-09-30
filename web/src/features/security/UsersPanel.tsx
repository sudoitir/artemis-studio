import { useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Modal,
  PasswordInput,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { needsReauthentication } from '../../kernel/auth/api.ts';
import { StepUpPrompt } from '../../kernel/auth/StepUp.tsx';
import { ConfirmByTyping } from '../../ui/ConfirmByTyping.tsx';
import { EffectivePermissionsDrawer } from './EffectivePermissionsDrawer.tsx';
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

interface Outcome {
  text: string;
  failed: boolean;
}

/** User accounts and their role grants (authorization spec). Requires `user:admin`. */
export function UsersPanel() {
  const users = useUsers();
  const unlock = useUnlockUser();
  const reset = useResetSecondFactors();
  const [unlockOutcome, setUnlockOutcome] = useState<Outcome | null>(null);
  const [resetting, setResetting] = useState<UserView | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [grantingFor, setGrantingFor] = useState<string | null>(null);
  const [previewing, setPreviewing] = useState<{ id: string; username: string } | null>(null);
  const [inspectingSessions, setInspectingSessions] = useState<{ id: string; username: string } | null>(null);

  const list = users.data ?? [];

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {list.length} user{list.length === 1 ? '' : 's'}
        </Text>
        <Button size="xs" onClick={() => setCreateOpen(true)}>
          New user
        </Button>
      </Group>

      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Username</Table.Th>
            <Table.Th>Provider</Table.Th>
            <Table.Th>Two-step verification</Table.Th>
            <Table.Th>Grants</Table.Th>
            <Table.Th>Enabled</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {list.map((u) => (
            <UserRow
              key={u.id}
              user={u}
              unlock={unlock}
              onUnlockOutcome={setUnlockOutcome}
              onReset={() => setResetting(u)}
              onGrant={() => setGrantingFor(u.id)}
              onPreview={() => setPreviewing(u)}
              onSessions={() => setInspectingSessions(u)}
            />
          ))}
        </Table.Tbody>
      </Table>

      <div aria-live="polite">
        {unlock.isPending ? (
          <Text size="sm" c="dimmed">
            Unlocking {list.find((u) => u.id === unlock.variables)?.username}…
          </Text>
        ) : null}
        {!unlock.isPending && reset.isPending ? (
          <Text size="sm" c="dimmed">
            Resetting two-step verification of {resetting?.username}…
          </Text>
        ) : null}
        {!unlock.isPending && !reset.isPending && unlockOutcome ? (
          <Text size="sm" c={unlockOutcome.failed ? 'red' : 'dimmed'}>
            {unlockOutcome.text}
          </Text>
        ) : null}
      </div>

      <EffectivePermissionsDrawer user={previewing} onClose={() => setPreviewing(null)} />
      <UserSessionsDrawer user={inspectingSessions} onClose={() => setInspectingSessions(null)} />

      <ResetModal
        user={resetting}
        reset={reset}
        onClose={() => setResetting(null)}
        onReset={(user) =>
          setUnlockOutcome({ text: `Reset two-step verification of ${user.username}.`, failed: false })
        }
      />
      <NewUserModal opened={createOpen} onClose={() => setCreateOpen(false)} />
      <GrantModal userId={grantingFor} onClose={() => setGrantingFor(null)} />
    </Stack>
  );
}

function UserRow({
  user: u,
  unlock,
  onUnlockOutcome,
  onReset,
  onGrant,
  onPreview,
  onSessions,
}: Readonly<{
  user: UserView;
  unlock: ReturnType<typeof useUnlockUser>;
  onUnlockOutcome: (outcome: Outcome | null) => void;
  onReset: () => void;
  onGrant: () => void;
  onPreview: () => void;
  onSessions: () => void;
}>) {
  const removeGrant = useRemoveGrant();
  const setDisabled = useSetUserDisabled();
  return (
    <Table.Tr>
      <Table.Td>
        <Text size="sm">{u.username}</Text>
        {u.mustChangePassword ? (
          <Text size="xs" c="dimmed">
            must change password
          </Text>
        ) : null}
        {u.lockedUntil ? (
          <Badge size="xs" variant="light" color="red">
            Locked until {new Date(u.lockedUntil).toLocaleTimeString([], { timeStyle: 'short' })}
          </Badge>
        ) : null}
      </Table.Td>
      <Table.Td>
        <Badge size="xs" variant="light">
          {u.providerId}
        </Badge>
      </Table.Td>
      <Table.Td>
        <TwoStepStatus user={u} onReset={onReset} />
      </Table.Td>
      <Table.Td>
        <Group gap={4} wrap="wrap">
          {u.grants.map((g) => (
            <Badge
              key={`${g.roleId}-${g.scopeType}-${g.scopeId ?? 'global'}`}
              size="xs"
              variant="outline"
              style={{ cursor: 'pointer' }}
              rightSection="×"
              onClick={() =>
                removeGrant.mutate({
                  userId: u.id,
                  roleId: g.roleId,
                  scopeType: g.scopeType,
                  scopeId: g.scopeId ?? undefined,
                })
              }
            >
              {g.roleName}
              {g.scopeType !== 'GLOBAL' ? ` (${g.scopeType.toLowerCase()})` : ''}
            </Badge>
          ))}
          <Badge size="xs" variant="light" color="pine" style={{ cursor: 'pointer' }} onClick={onGrant}>
            + grant
          </Badge>
        </Group>
      </Table.Td>
      <Table.Td>
        <Switch
          checked={!u.disabled}
          onChange={() => setDisabled.mutate({ userId: u.id, disabled: !u.disabled })}
          size="sm"
          aria-label={`${u.disabled ? 'Enable' : 'Disable'} ${u.username}`}
        />
      </Table.Td>
      <Table.Td>
        <Group gap="xs" wrap="nowrap">
          {u.lockedUntil ? (
            <Button
              size="xs"
              variant="light"
              aria-label={`Unlock ${u.username}`}
              loading={unlock.isPending && unlock.variables === u.id}
              disabled={unlock.isPending}
              onClick={() => {
                onUnlockOutcome(null);
                unlock.mutate(u.id, {
                  onSuccess: () => onUnlockOutcome({ text: `Unlocked ${u.username}.`, failed: false }),
                  onError: (e) =>
                    onUnlockOutcome({ text: `Could not unlock ${u.username}. ${e.message} Try again.`, failed: true }),
                });
              }}
            >
              Unlock
            </Button>
          ) : null}
          <Button size="xs" variant="subtle" aria-label={`Sessions of ${u.username}`} onClick={onSessions}>
            Sessions
          </Button>
          <Button size="xs" variant="subtle" onClick={onPreview}>
            Effective permissions
          </Button>
        </Group>
      </Table.Td>
    </Table.Tr>
  );
}

function ResetModal({
  user,
  reset,
  onClose,
  onReset,
}: Readonly<{
  user: UserView | null;
  reset: ReturnType<typeof useResetSecondFactors>;
  onClose: () => void;
  onReset: (user: UserView) => void;
}>) {
  return (
    <Modal
      opened={user !== null}
      onClose={() => {
        onClose();
        reset.reset();
      }}
      title={user ? `Reset two-step verification of ${user.username}` : ''}
    >
      {user ? (
        <Stack gap="md">
          <Text size="sm">
            This removes {user.username}'s authenticator app, passkeys, recovery codes and trusted devices, revokes
            their API keys and signs them out everywhere.
          </Text>
          <Text size="sm" c="dimmed">
            {user.secondFactorRequired
              ? 'Their role requires two-step verification, so they set it up again the next time they sign in.'
              : 'Signing in then needs only their password.'}
          </Text>
          <StepUpPrompt error={reset.error} returnTo={`${globalThis.location.pathname}${globalThis.location.search}`} />
          {reset.error && !needsReauthentication(reset.error) ? (
            <Alert color="red" variant="light" role="alert" title="Not reset">
              {resetFailure(reset.error)}
            </Alert>
          ) : null}
          <ConfirmByTyping
            token={user.username}
            confirmLabel="Reset two-step verification"
            loading={reset.isPending}
            onConfirm={() =>
              reset.mutate(user.id, {
                onSuccess: () => {
                  onReset(user);
                  onClose();
                },
              })
            }
          />
        </Stack>
      ) : null}
    </Modal>
  );
}

function NewUserModal({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  const createUser = useCreateUser();
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const policyReason = createUser.error?.type.endsWith('/password-policy') ? createUser.error.message : undefined;

  return (
    <Modal
      opened={opened}
      onClose={() => {
        onClose();
        createUser.reset();
      }}
      title="New user"
    >
      <Stack gap="sm">
        <TextInput label="Username" value={username} onChange={(e) => setUsername(e.currentTarget.value)} required />
        <TextInput label="Email" value={email} onChange={(e) => setEmail(e.currentTarget.value)} />
        <PasswordInput
          label="Initial password"
          value={password}
          onChange={(e) => setPassword(e.currentTarget.value)}
          description="The user will be required to change it on first login."
          error={policyReason}
          required
        />
        <Button
          loading={createUser.isPending}
          onClick={() =>
            createUser.mutate(
              { username, email: email || undefined, password },
              {
                onSuccess: () => {
                  onClose();
                  setUsername('');
                  setEmail('');
                  setPassword('');
                  notifications.show({ message: `Created ${username}`, color: 'green' });
                },
                onError: (e) => {
                  if (!e.type.endsWith('/password-policy')) notifications.show({ message: e.message, color: 'red' });
                },
              },
            )
          }
        >
          Create
        </Button>
      </Stack>
    </Modal>
  );
}

function GrantModal({ userId, onClose }: Readonly<{ userId: string | null; onClose: () => void }>) {
  const roles = useRoles();
  const addGrant = useAddGrant();
  const [roleId, setRoleId] = useState<string | null>(null);
  const roleOptions = (roles.data ?? []).map((r) => ({ value: r.id, label: r.name }));

  return (
    <Modal opened={userId !== null} onClose={onClose} title="Grant a role">
      <Stack gap="sm">
        <Select label="Role" data={roleOptions} value={roleId} onChange={setRoleId} placeholder="Select a role" />
        <Text size="xs" c="dimmed">
          Granted globally. Use the API to scope a grant to one environment or cluster.
        </Text>
        <Button
          disabled={!roleId}
          loading={addGrant.isPending}
          onClick={() => {
            if (!userId || !roleId) return;
            addGrant.mutate(
              { userId, body: { roleId, scopeType: 'GLOBAL' } },
              {
                onSuccess: () => {
                  onClose();
                  setRoleId(null);
                },
                onError: (e) => notifications.show({ message: e.message, color: 'red' }),
              },
            );
          }}
        >
          Grant
        </Button>
      </Stack>
    </Modal>
  );
}

const FACTOR_WORDS = { TOTP: 'Authenticator app', WEBAUTHN: 'Passkey' } as const;

/** What a user's second step is, in words, with the way to reset it beside it when there is something to reset. */
function TwoStepStatus({ user, onReset }: Readonly<{ user: UserView; onReset: () => void }>) {
  const factors = user.secondFactors.flatMap((f) =>
    f in FACTOR_WORDS ? [FACTOR_WORDS[f as keyof typeof FACTOR_WORDS]] : [],
  );
  const statusId = `two-step-${user.id}`;
  const local = user.providerId === 'local';
  const nothing = factors.length === 0;
  let status = (
    <Text id={statusId} size="sm">
      {factors.join(', ')}
    </Text>
  );
  if (!local) {
    status = (
      <Text id={statusId} size="sm" c="dimmed">
        Managed by their identity provider
      </Text>
    );
  } else if (nothing && user.secondFactorRequired) {
    status = (
      <Text id={statusId} size="sm" fw={600} style={{ color: 'var(--as-warning)' }}>
        Required, not set up
      </Text>
    );
  } else if (nothing) {
    status = (
      <Text id={statusId} size="sm" c="dimmed">
        Not set up
      </Text>
    );
  }
  return (
    <Stack gap={2} align="flex-start">
      {status}
      {nothing ? null : (
        <Anchor
          component="button"
          type="button"
          size="xs"
          aria-label={`Reset two-step verification of ${user.username}`}
          aria-describedby={statusId}
          onClick={onReset}
        >
          Reset two-step verification
        </Anchor>
      )}
    </Stack>
  );
}

/** What a refused reset means for the administrator, and what to do about it. */
function resetFailure(error: { type: string; message: string }): string {
  if (error.type.endsWith('/self-reset')) {
    return 'You cannot reset your own two-step verification here. Sign in with one of your recovery codes instead.';
  }
  if (error.type.endsWith('/mfa-required')) {
    return 'This user must hold a second factor, so your own session has to have verified one. Sign out, sign in with your second factor, then try again.';
  }
  return `${error.message} Try again.`;
}
